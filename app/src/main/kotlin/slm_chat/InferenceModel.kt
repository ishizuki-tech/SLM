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
 * InferenceModel - robust concurrent manager for MediaPipe LlmInference + sessions.
 *
 * Improvements:
 *  - Keeps a cumulative emitted string per request to allow correct dedupe.
 *  - Marks when the final emission sequence has started to prevent racing callback emits.
 *  - Ensures chunkAndEmitFinal only emits the missing suffix relative to what's already emitted.
 */
class InferenceModel private constructor(appCtx: Context) {

    companion object {
        private const val TAG = "InferenceModel"
        private const val MODEL_ASSET_NAME = "gemma3-1b-it-int4.task"
        private const val MAX_TOKENS = 2048

        @Volatile
        private var instance: InferenceModel? = null

        fun getInstance(context: Context): InferenceModel {
            return instance ?: synchronized(this) {
                instance ?: InferenceModel(context.applicationContext).also {
                    instance = it
                    Log.i(TAG, "InferenceModel instance created: ${it.hashCode()}")
                }
            }
        }
    }

    data class PartialResult(val requestId: String, val text: String, val done: Boolean)

    private val emitScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val nativeCloseExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "InferenceModel-NativeClose") }
    private val appContext = appCtx.applicationContext

    private val _partialResults = MutableSharedFlow<PartialResult>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val partialResults: SharedFlow<PartialResult> = _partialResults.asSharedFlow()

    private val llm: LlmInference
    private val closed = AtomicBoolean(false)

    /**
     * RequestState enhancements:
     * - emittedSoFar: cumulative text actually emitted to UI for this request (not just last chunk).
     * - finalEmitted: set to true by the future listener BEFORE chunkAndEmitFinal runs, so callbacks
     *   that arrive after that will not re-emit.
     */
    private data class RequestState(
        val requestId: String,
        val session: LlmInferenceSession,
        val future: ListenableFuture<String>,
        val doneEmitted: AtomicBoolean = AtomicBoolean(false),
        val lastPartial: AtomicReference<String?> = AtomicReference(""),
        val lastEmitted: AtomicReference<String?> = AtomicReference(""),
        val emittedSoFar: AtomicReference<String> = AtomicReference(""), // cumulative emitted text
        val finalEmitted: AtomicBoolean = AtomicBoolean(false), // indicates future is producing final emission
        val closed: AtomicBoolean = AtomicBoolean(false)
    )

    private val requests = ConcurrentHashMap<String, RequestState>()

    init {
        Log.i(TAG, "init() thread=${Thread.currentThread().name}")
        val taskPath = ensureModelPresent(appContext, MODEL_ASSET_NAME)
        Log.i(TAG, "Model file available at: $taskPath")
        val options = LlmInference.LlmInferenceOptions.builder().setModelPath(taskPath).setMaxTokens(MAX_TOKENS).build()
        try {
            llm = LlmInference.createFromOptions(appContext, options)
            Log.i(TAG, "LlmInference created: ${llm.hashCode()}")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create LlmInference", e)
            throw e
        }
    }

    private fun newSession(topK: Int = 15, temperature: Float = 0.2f, randomSeed: Int? = null): LlmInferenceSession {
        Log.d(TAG, "newSession() topK=$topK temperature=$temperature randomSeed=$randomSeed")
        val builder = LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(topK).setTemperature(temperature)
        if (randomSeed != null) builder.setRandomSeed(randomSeed)
        val s = LlmInferenceSession.createFromOptions(llm, builder.build())
        Log.i(TAG, "New session created: ${s.hashCode()}")
        return s
    }

    fun startRequest(prompt: String, topK: Int = 15, temperature: Float = 0.2f, randomSeed: Int? = null): String {
        val requestId = UUID.randomUUID().toString()
        Log.i(TAG, "startRequest(requestId=$requestId) thread=${Thread.currentThread().name}")
        Log.d(TAG, "Prompt (truncated 512): ${prompt.take(512).replace("\n", "\\n")}")

        val localSession = try { newSession(topK, temperature, randomSeed) } catch (e: Throwable) {
            Log.e(TAG, "Failed to create session for request=$requestId", e); return requestId
        }

        try { localSession.addQueryChunk(prompt) } catch (e: Throwable) {
            Log.e(TAG, "addQueryChunk failed for request=$requestId session=${localSession.hashCode()}", e)
            try { localSession.close() } catch (_: Throwable) {}
            return requestId
        }

        val doneEmitted = AtomicBoolean(false)
        val lastPartial = AtomicReference<String?>("")
        val lastEmitted = AtomicReference<String?>("")
        val closedFlag = AtomicBoolean(false)

        try {
            val future = localSession.generateResponseAsync { partial: String, done: Boolean ->
                try {
                    // update lastPartial
                    lastPartial.set(partial)

                    // If the future has already started final emission, do NOT re-emit callback parts.
                    val state = requests[requestId] // may be null briefly; guard
                    if (state != null && state.finalEmitted.get()) {
                        Log.d(TAG, "callback: final emission in progress -> skipping callback emit for request=$requestId")
                    } else {
                        // emit and append to emittedSoFar
                        val emitted = _partialResults.tryEmit(PartialResult(requestId, partial, done))
                        if (emitted) {
                            lastEmitted.set(partial)
                            requests[requestId]?.emittedSoFar?.updateAndGet { old -> old + partial }
                        } else {
                            Log.w(TAG, "failed to emit partial for request=$requestId")
                        }
                    }

                    if (done) {
                        if (doneEmitted.compareAndSet(false, true)) {
                            Log.i(TAG, "done=true received request=$requestId -> scheduling close (callback)")
                            if (closedFlag.compareAndSet(false, true)) {
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
                            requests.remove(requestId)
                        } else {
                            Log.d(TAG, "duplicate done ignored for request=$requestId (callback)")
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Exception in callback for request=$requestId", e)
                }
            }

            // create RequestState and store (share the atomic refs)
            val state = RequestState(requestId, localSession, future, doneEmitted, lastPartial, lastEmitted, AtomicReference(""), AtomicBoolean(false), closedFlag)
            requests[requestId] = state
            Log.i(TAG, "Stored request state request=$requestId session=${localSession.hashCode()} future=${future.hashCode()} totalRequests=${requests.size}")

            // Future listener: fallback to final text if callback didn't produce done.
            future.addListener(Runnable {
                try {
                    Log.d(TAG, "Future listener invoked for request=$requestId (isDone=${future.isDone} isCancelled=${future.isCancelled})")

                    if (state.doneEmitted.compareAndSet(false, true)) {
                        // Obtain final text (prefer lastPartial if present)
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
                            // Mark that final emission is in progress BEFORE chunking/emitting so callbacks skip.
                            state.finalEmitted.set(true)
                            Log.d(TAG, "future listener finalText.len=${finalText.length} emittedSoFar.len=${state.emittedSoFar.get().length} request=$requestId (finalEmitted set = true)")

                            // Compute suffix that still needs to be emitted relative to emittedSoFar
                            val already = state.emittedSoFar.get()
                            val toEmit = when {
                                already.isEmpty() -> finalText
                                finalText == already -> { // already fully emitted
                                    ""
                                }
                                finalText.startsWith(already) -> finalText.removePrefix(already)
                                finalText.endsWith(already) -> finalText.removeSuffix(already) // rare
                                finalText.contains(already) -> {
                                    // find first index and emit remainder after that index+already.length
                                    val idx = finalText.indexOf(already)
                                    if (idx >= 0) finalText.substring(idx + already.length) else finalText
                                }
                                else -> finalText // no overlap detected
                            }

                            if (toEmit.isEmpty()) {
                                Log.i(TAG, "No missing suffix to emit (already == finalText), emitting done marker only for request=$requestId")
                                _partialResults.tryEmit(PartialResult(requestId, "", true))
                            } else {
                                Log.i(TAG, "Emitting missing suffix.len=${toEmit.length} for request=$requestId")
                                chunkAndEmitFinalWithState(requestId, finalText, toEmit, state, emitDelayMs = 20L)
                            }
                        }
                    } else {
                        Log.d(TAG, "done already emitted for request=$requestId before future listener")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Exception in future listener for request=$requestId", e)
                } finally {
                    if (state.closed.compareAndSet(false, true)) {
                        nativeCloseExecutor.submit {
                            try {
                                try { state.session.close() } catch (t: Throwable) { Log.w(TAG, "close in future listener", t) }
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
     * Emit the 'toEmit' suffix (which is guaranteed to be the missing suffix of the finalText
     * relative to emittedSoFar). Update emittedSoFar as chunks are emitted.
     */
    private fun chunkAndEmitFinalWithState(requestId: String, finalText: String, toEmit: String, state: RequestState, emitDelayMs: Long = 20L) {
        emitScope.launch {
            try {
                val sentenceRegex = Regex("(?<=[。．！？!?]|\\.|\\!|\\?)\\s*")
                val pieces = sentenceRegex.split(toEmit).map { it.trim() }.filter { it.isNotEmpty() }

                val chunks = if (pieces.isNotEmpty()) pieces else {
                    val maxChunkLen = 300
                    toEmit.chunked(maxChunkLen)
                }

                for ((i, chunk) in chunks.withIndex()) {
                    val isLast = (i == chunks.lastIndex)
                    try {
                        val emitted = _partialResults.tryEmit(PartialResult(requestId, chunk, isLast))
                        if (emitted) {
                            // update cumulative emitted text
                            state.emittedSoFar.updateAndGet { old -> old + chunk }
                            // also update lastEmitted (most recent chunk)
                            state.lastEmitted.set(chunk)
                        } else {
                            Log.w(TAG, "chunkAndEmitFinal failed to emit chunk for request=$requestId chunk.len=${chunk.length}")
                        }
                        Log.d(TAG, "chunkAndEmitFinal emitted request=$requestId chunk.len=${chunk.length} done=$isLast")
                    } catch (e: Throwable) {
                        Log.w(TAG, "chunkAndEmitFinal failed to emit chunk for request=$requestId", e)
                    }
                    if (emitDelayMs > 0 && !isLast) delay(emitDelayMs)
                }

                // Safety: if finalText not fully represented in emittedSoFar (unlikely), ensure it is updated
                if (!state.emittedSoFar.get().endsWith(finalText)) {
                    // Try to reconcile by setting emittedSoFar to finalText (best-effort)
                    state.emittedSoFar.set(finalText)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "chunkAndEmitFinal unexpected error for request=$requestId", e)
                try {
                    _partialResults.tryEmit(PartialResult(requestId, finalText, true))
                    state.emittedSoFar.set(finalText)
                    state.lastEmitted.set(finalText)
                } catch (_: Throwable) {}
            }
        }
    }

    fun cancelRequest(requestId: String) {
        val state = requests.remove(requestId)
        if (state == null) {
            Log.w(TAG, "cancelRequest: no such requestId=$requestId"); return
        }
        Log.i(TAG, "cancelRequest called for request=$requestId session=${state.session.hashCode()} future=${state.future.hashCode()}")

        try {
            try { state.session.cancelGenerateResponseAsync() } catch (e: Throwable) { Log.w(TAG, "cancelGenerateResponseAsync threw for request=$requestId", e) }
            try { state.future.cancel(true) } catch (e: Throwable) { Log.w(TAG, "future.cancel threw for request=$requestId", e) }
        } catch (e: Throwable) {
            Log.w(TAG, "Exception during cancel steps for request=$requestId", e)
        }

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

        _partialResults.tryEmit(PartialResult(requestId, "__CANCELLED__", true))
        Log.i(TAG, "cancelRequest completed request=$requestId")
    }

    fun cancelAll() {
        val keys = requests.keys().toList()
        for (k in keys) cancelRequest(k)
    }

    suspend fun awaitRequestCompletion(requestId: String) {
        val state = requests[requestId] ?: return
        val future = state.future
        if (future.isDone) return
        return suspendCancellableCoroutine { cont ->
            try {
                future.addListener(Runnable {
                    try {
                        if (!cont.isCompleted) cont.resume(Unit)
                    } catch (e: Throwable) {
                        if (!cont.isCompleted) cont.resumeWithException(e)
                    }
                }, MoreExecutors.directExecutor())
            } catch (e: Throwable) {
                if (!cont.isCompleted) cont.resumeWithException(e)
            }
            cont.invokeOnCancellation { future.cancel(true) }
        }
    }

    fun isRunning(requestId: String): Boolean = requests.containsKey(requestId)
    fun activeRequestIds(): List<String> = requests.keys().toList()

    fun close() {
        if (!closed.compareAndSet(false, true)) {
            Log.i(TAG, "close() called but already closed"); return
        }
        Log.i(TAG, "close() called")
        cancelAll()
        try { llm.close() } catch (e: Throwable) { Log.w(TAG, "llm.close threw", e) }
        try { nativeCloseExecutor.shutdownNow() } catch (e: Throwable) { Log.w(TAG, "nativeCloseExecutor.shutdownNow threw", e) }
        instance = null
        Log.i(TAG, "InferenceModel closed")
    }

    private fun ensureModelPresent(context: Context, assetName: String): String {
        val outFile = File(context.filesDir, assetName)
        try {
            if (!outFile.exists()) {
                Log.i(TAG, "Copying model from assets/models/$assetName to ${outFile.absolutePath}")
                context.assets.open("models/$assetName").use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
                Log.i(TAG, "Model copied to ${outFile.absolutePath}")
            } else {
                Log.i(TAG, "Model already present at ${outFile.absolutePath}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to ensure model present", e); throw e
        }
        return outFile.absolutePath
    }
}
