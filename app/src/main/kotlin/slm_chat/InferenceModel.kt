package slm_chat

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * InferenceModel — 完全同時実行版 (ネイティブ close の競合対策済み)
 *
 * 重要: session.close() 等のネイティブ側リソース削除は nativeCloseExecutor に直列化して投げています。
 *        cancelRequest / future-listener / callback のどれが先に来ても close は一回だけ行われます。
 */
class InferenceModel private constructor(appCtx: Context) {

    companion object {
        private const val TAG = "InferenceModel"
        private const val MODEL_ASSET_NAME = "gemma3-1b-it-int4.task"
        private const val MAX_TOKENS = 1024

        @Volatile private var instance: InferenceModel? = null

        fun getInstance(context: Context): InferenceModel {
            return instance ?: synchronized(this) {
                instance ?: InferenceModel(context).also {
                    instance = it
                    Log.i(TAG, "InferenceModel instance created: ${it.hashCode()}")
                }
            }
        }
    }

    data class PartialResult(val requestId: String, val text: String, val done: Boolean)

    // emit 用の CoroutineScope（listener スレッドをブロッキングしない）
    private val emitScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // single-thread executor for native close/unregister operations (serialize JNI actions)
    private val nativeCloseExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "InferenceModel-NativeClose")
    }

    private val context = appCtx.applicationContext

    private val _partialResults = MutableSharedFlow<PartialResult>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val partialResults: SharedFlow<PartialResult> = _partialResults.asSharedFlow()

    // LLM engine
    private val llm: LlmInference

    // 管理用マップ: requestId -> RequestState
    private data class RequestState(
        val requestId: String,
        val session: LlmInferenceSession,
        val future: ListenableFuture<String>,
        val doneEmitted: AtomicBoolean = AtomicBoolean(false),
        val lastPartial: AtomicReference<String?> = AtomicReference(""),
        val closed: AtomicBoolean = AtomicBoolean(false) // guard close only once
    )
    private val requests = ConcurrentHashMap<String, RequestState>()

    init {
        Log.i(TAG, "init() thread=${Thread.currentThread().name}")
        val taskPath = ensureModelPresent(context, MODEL_ASSET_NAME)
        Log.i(TAG, "Model file available at: $taskPath")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(taskPath)
            .setMaxTokens(MAX_TOKENS)
            .build()
        try {
            llm = LlmInference.createFromOptions(context, options)
            Log.i(TAG, "LlmInference created: ${llm.hashCode()}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create LlmInference", e)
            throw e
        }
    }

    private fun newSession(
        topK: Int = 40,
        temperature: Float = 0.8f,
        randomSeed: Int? = null
    ): LlmInferenceSession {
        Log.d(TAG, "newSession() topK=$topK temperature=$temperature randomSeed=$randomSeed")
        val builder = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(topK)
            .setTemperature(temperature)
        if (randomSeed != null) builder.setRandomSeed(randomSeed)
        val s = LlmInferenceSession.createFromOptions(llm, builder.build())
        Log.i(TAG, "New session created: ${s.hashCode()}")
        return s
    }

    /**
     * Start a request concurrently. Returns requestId immediately.
     * The PartialResult flow will emit results with that requestId.
     */
    fun startRequest(prompt: String, topK: Int = 40, temperature: Float = 0.8f, randomSeed: Int? = null): String {
        val requestId = UUID.randomUUID().toString()
        Log.i(TAG, "startRequest(requestId=$requestId) thread=${Thread.currentThread().name}")
        Log.d(TAG, "Prompt (truncated 512): ${prompt.take(512).replace("\n", "\\n")}")

        val localSession = try {
            newSession(topK, temperature, randomSeed)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create session for request=$requestId", e)
            return requestId
        }

        try {
            localSession.addQueryChunk(prompt)
        } catch (e: Throwable) {
            Log.e(TAG, "addQueryChunk failed for request=$requestId session=${localSession.hashCode()}", e)
            try { localSession.close() } catch (_: Throwable) {}
            return requestId
        }

        val doneEmitted = AtomicBoolean(false)
        val lastPartial = AtomicReference<String?>("")
        val closed = AtomicBoolean(false)

        try {
            val future = localSession.generateResponseAsync { partial: String, done: Boolean ->
                try {
                    val th = Thread.currentThread().name
                    lastPartial.set(partial)
                    Log.d(TAG, "Callback request=$requestId thread=$th session=${localSession.hashCode()} partial.len=${partial.length} done=$done")
                    _partialResults.tryEmit(PartialResult(requestId, partial, done))

                    if (done) {
                        if (doneEmitted.compareAndSet(false, true)) {
                            Log.i(TAG, "done=true received request=$requestId -> scheduling close")
                            // schedule close only once
                            if (closed.compareAndSet(false, true)) {
                                nativeCloseExecutor.submit {
                                    try {
                                        localSession.close()
                                        Log.i(TAG, "Closed session ${localSession.hashCode()} for request=$requestId (callback path)")
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "Error closing session in callback for request=$requestId", t)
                                    }
                                }
                            } else {
                                Log.d(TAG, "Session already scheduled/closed for request=$requestId (callback path)")
                            }
                            // remove state if present
                            requests.remove(requestId)
                        } else {
                            Log.d(TAG, "duplicate done ignored for request=$requestId (callback)")
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Exception in callback for request=$requestId", e)
                }
            }

            val state = RequestState(requestId, localSession, future, doneEmitted, lastPartial, closed)
            requests[requestId] = state
            Log.i(TAG, "Stored request state request=$requestId session=${localSession.hashCode()} future=${future.hashCode()} totalRequests=${requests.size}")

            // Future listener: if callback never emitted done, fallback to final via future.get()
            future.addListener(Runnable {
                try {
                    Log.d(TAG, "Future listener invoked for request=$requestId future=${future.hashCode()} (isDone=${future.isDone} isCancelled=${future.isCancelled})")

                    if (state.doneEmitted.compareAndSet(false, true)) {
                        var finalText = state.lastPartial.get()
                        if (finalText.isNullOrEmpty()) {
                            try {
                                finalText = future.get(1, TimeUnit.SECONDS)
                                Log.i(TAG, "Obtained finalText from future.get() len=${finalText?.length ?: 0} request=$requestId")
                            } catch (te: TimeoutException) {
                                Log.w(TAG, "future.get() timed out for request=$requestId", te)
                            } catch (ee: ExecutionException) {
                                Log.w(TAG, "future.get() ExecutionException for request=$requestId", ee)
                                finalText = ee.cause?.toString() ?: ""
                            } catch (ie: InterruptedException) {
                                Log.w(TAG, "future.get() interrupted for request=$requestId", ie)
                                Thread.currentThread().interrupt()
                            } catch (t: Throwable) {
                                Log.w(TAG, "Unexpected error calling future.get() request=$requestId", t)
                            }
                        }

                        if (finalText.isNullOrEmpty()) {
                            Log.w(TAG, "finalText empty, emitting placeholder for request=$requestId")
                            _partialResults.tryEmit(PartialResult(requestId, "__NO_OUTPUT__", true))
                        } else {
                            chunkAndEmitFinal(requestId, finalText, emitDelayMs = 20L)
                        }
                    } else {
                        Log.d(TAG, "done already emitted for request=$requestId before future listener")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Exception in future listener for request=$requestId", e)
                } finally {
                    // schedule close only once, and remove request state
                    if (state.closed.compareAndSet(false, true)) {
                        nativeCloseExecutor.submit {
                            try {
                                state.session.let { s ->
                                    try { s.close() } catch (t: Throwable) { Log.w(TAG, "close in future listener", t) }
                                }
                                Log.i(TAG, "Closed session ${state.session.hashCode()} for request=$requestId (future listener path)")
                            } catch (t: Throwable) {
                                Log.w(TAG, "Error closing session in future listener for request=$requestId", t)
                            }
                        }
                    } else {
                        Log.d(TAG, "Session already scheduled/closed for request=$requestId (future listener)")
                    }
                    requests.remove(requestId)
                    Log.i(TAG, "Request completed/cleaned request=$requestId remaining=${requests.size}")
                }
            }, MoreExecutors.directExecutor())

        } catch (e: Throwable) {
            Log.e(TAG, "startRequest failed for request=$requestId", e)
            try { localSession.close() } catch (_: Throwable) {}
            requests.remove(requestId)
        }

        return requestId
    }

    /**
     * Cancel a specific request (best-effort).
     * After cancellation, a final event with done=true will NOT be emitted automatically (UI can treat cancel as done).
     */
    fun cancelRequest(requestId: String) {
        val state = requests.remove(requestId)
        if (state == null) {
            Log.w(TAG, "cancelRequest: no such requestId=$requestId")
            return
        }
        Log.i(TAG, "cancelRequest called for request=$requestId session=${state.session.hashCode()} future=${state.future.hashCode()}")

        // try to cancel generation and future first
        try {
            try { state.session.cancelGenerateResponseAsync() } catch (e: Throwable) { Log.w(TAG, "cancelGenerateResponseAsync threw for request=$requestId", e) }
            try { state.future.cancel(true) } catch (e: Throwable) { Log.w(TAG, "future.cancel threw for request=$requestId", e) }
        } catch (e: Throwable) {
            Log.w(TAG, "Exception during cancel request steps for request=$requestId", e)
        }

        // schedule close exactly once on nativeCloseExecutor
        if (state.closed.compareAndSet(false, true)) {
            nativeCloseExecutor.submit {
                try {
                    try { state.session.close() } catch (t: Throwable) { Log.w(TAG, "session.close threw in cancelRequest", t) }
                    Log.i(TAG, "Closed session ${state.session.hashCode()} for request=$requestId (cancelRequest path)")
                } catch (t: Throwable) {
                    Log.w(TAG, "Error when closing session in cancelRequest for request=$requestId", t)
                }
            }
        } else {
            Log.d(TAG, "Session already scheduled/closed for request=$requestId (cancelRequest)")
        }

        // optionally notify UI of cancellation
        _partialResults.tryEmit(PartialResult(requestId, "__CANCELLED__", true))
        Log.i(TAG, "cancelRequest completed request=$requestId")
    }

    /** Cancel all running requests */
    fun cancelAll() {
        val keys = requests.keys().toList()
        for (k in keys) cancelRequest(k)
    }

    /** Utility: await completion of a specific request (suspendable). Resolves when its future completes. */
    suspend fun awaitRequestCompletion(requestId: String) {
        val state = requests[requestId] ?: return
        val future = state.future
        return suspendCancellableCoroutine { cont ->
            try {
                future.addListener(Runnable {
                    if (!cont.isCompleted) cont.resume(Unit)
                }, MoreExecutors.directExecutor())
            } catch (e: Throwable) {
                if (!cont.isCompleted) cont.resumeWithException(e)
            }
            cont.invokeOnCancellation { future.cancel(true) }
        }
    }

    /** Is a request running? */
    fun isRunning(requestId: String): Boolean = requests.containsKey(requestId)

    /** Get current active request IDs */
    fun activeRequestIds(): List<String> = requests.keys().toList()

    /**
     * Chunk and emit final text for a given requestId (non-blocking).
     * Emits multiple PartialResult(requestId, chunk, done) where done=true for final chunk.
     */
    private fun chunkAndEmitFinal(requestId: String, finalText: String, emitDelayMs: Long = 20L) {
        emitScope.launch {
            try {
                val sentenceRegex = Regex("(?<=[。．！？!?]|\\.|\\!|\\?)\\s*")
                val pieces = sentenceRegex.split(finalText).map { it.trim() }.filter { it.isNotEmpty() }

                val chunks = if (pieces.isNotEmpty()) {
                    pieces
                } else {
                    val maxChunkLen = 300
                    finalText.chunked(maxChunkLen)
                }

                for ((i, chunk) in chunks.withIndex()) {
                    val isLast = (i == chunks.lastIndex)
                    try {
                        _partialResults.tryEmit(PartialResult(requestId, chunk, isLast))
                        Log.d(TAG, "chunkAndEmitFinal emitted request=$requestId chunk.len=${chunk.length} done=$isLast")
                    } catch (e: Throwable) {
                        Log.w(TAG, "chunkAndEmitFinal failed to emit chunk for request=$requestId", e)
                    }
                    if (emitDelayMs > 0 && !isLast) {
                        delay(emitDelayMs)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "chunkAndEmitFinal unexpected error for request=$requestId", e)
                try { _partialResults.tryEmit(PartialResult(requestId, finalText, true)) } catch (_: Throwable) {}
            }
        }
    }

    /** Clean shutdown */
    fun close() {
        Log.i(TAG, "close() called")
        cancelAll()
        try { llm.close() } catch (e: Throwable) { Log.w(TAG, "llm.close threw", e) }
        // shut down native close executor
        try {
            nativeCloseExecutor.shutdownNow()
        } catch (e: Throwable) {
            Log.w(TAG, "nativeCloseExecutor.shutdownNow threw", e)
        }
        instance = null
        Log.i(TAG, "InferenceModel closed")
    }

    private fun ensureModelPresent(context: Context, assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        try {
            if (!outFile.exists()) {
                Log.i(TAG, "Copying model from assets/models/$assetName to ${outFile.absolutePath}")
                context.assets.open("models/$assetName").use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Model copied to ${outFile.absolutePath}")
            } else {
                Log.i(TAG, "Model already present at ${outFile.absolutePath}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to ensure model present", e)
            throw e
        }
        return outFile.absolutePath
    }
}
