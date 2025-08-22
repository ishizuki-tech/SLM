package slm_chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SurveyValidator"

/**
 * Result returned to callers after parsing model output.
 */
data class ValidationResult(
    val requestId: String,
    val rawModelOutput: String,
    val relevance: Int?,
    val specificity: Int?,
    val actionability: Int?,
    val overall: Int?,
    val needsFollowup: Boolean?,
    val followupQuestion: String?
)

/**
 * SurveyValidator
 *
 * - Uses InferenceModel singleton to perform response validation.
 * - Provides both suspend (validateResponse) and async callback (validateResponseAsync) APIs.
 * - Manages an internal scope and supports best-effort cancellation and close().
 */
class SurveyValidator(private val context: Context) {

    private val model = InferenceModel.getInstance(context)

    // single scope for internal async work (can be cancelled via close())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // track requestId -> Job so async validators can be cancelled
    private val collectors = ConcurrentHashMap<String, Job>()

    /**
     * Build the prompt to send to the LLM.
     */
    private fun buildPrompt(validatePrompt: String, questionText: String, userAnswer: String): String {
        return """
        Input:
        Question: "$questionText"
        Answer: "$userAnswer"
        "$validatePrompt"
        """.trimIndent()
    }

    // --------------------------
    // Suspend API
    // --------------------------
    /**
     * Suspend API: start a request and collect partials until completion or timeout.
     *
     * Cancellation: cancel the coroutine that called this function (e.g. viewModelScope) to abort.
     */
    suspend fun validateResponse(
        validatePrompt: String,
        questionText: String,
        userAnswer: String,
        timeoutMs: Long = 15_000L,
        topK: Int = 15,
        temperature: Float = 0.2f,
        randomSeed: Int? = null
    ): ValidationResult {
        val prompt = buildPrompt(validatePrompt, questionText, userAnswer)
        val requestId = model.startRequest(prompt, topK = topK, temperature = temperature, randomSeed = randomSeed)
        Log.i(TAG, "validateResponse started request=$requestId")

        val sb = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    // explicit cancellation check; throws CancellationException if cancelled
                    ensureActive()

                    // suspend until next partial for this requestId
                    val pr = model.partialResults
                        .filter { it.requestId == requestId }
                        .first()

                    sb.append(pr.text)
                    if (pr.done) break
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "validateResponse cancelled by caller, request=$requestId")
            // best-effort cancel on model side
            try { model.cancelRequest(requestId) } catch (t: Throwable) { Log.w(TAG, "model.cancelRequest failed", t) }
            throw e
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "validateResponse timed out request=$requestId")
            try { model.cancelRequest(requestId) } catch (t: Throwable) { Log.w(TAG, "cancelRequest threw", t) }
        } catch (e: Throwable) {
            Log.w(TAG, "validateResponse unexpected error request=$requestId", e)
            try { model.cancelRequest(requestId) } catch (t: Throwable) { Log.w(TAG, "cancelRequest threw", t) }
        }

        val raw = sb.toString().trim()
        val parsed = parseModelOutput(raw)
        return ValidationResult(
            requestId = requestId,
            rawModelOutput = raw,
            relevance = parsed["relevance"] as? Int,
            specificity = parsed["specificity"] as? Int,
            actionability = parsed["actionability"] as? Int,
            overall = parsed["overall"] as? Int,
            needsFollowup = parsed["needs_followup"] as? Boolean,
            followupQuestion = parsed["followup"] as? String
        )
    }

    // --------------------------
    // Async / callback API
    // --------------------------
    /**
     * Async / callback API.
     * - Returns requestId immediately.
     * - When finished (or on cancel/timeout) the callback will be invoked once with a ValidationResult.
     */
    fun validateResponseAsync(
        validatePrompt: String,
        questionText: String,
        userAnswer: String,
        timeoutMs: Long = 15_000L,
        topK: Int = 40,
        temperature: Float = 0.6f,
        randomSeed: Int? = null,
        onComplete: (ValidationResult) -> Unit
    ): String {
        val requestId = model.startRequest(
            buildPrompt(validatePrompt, questionText, userAnswer),
            topK = topK,
            temperature = temperature,
            randomSeed = randomSeed
        )

        val job = scope.launch {
            val sb = StringBuilder()
            try {
                withTimeout(timeoutMs) {
                    while (true) {
                        ensureActive()
                        val pr = model.partialResults
                            .filter { it.requestId == requestId }
                            .first()
                        sb.append(pr.text)
                        if (pr.done) break
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "validateResponseAsync cancelled request=$requestId")
                try { model.cancelRequest(requestId) } catch (t: Throwable) { Log.w(TAG, "model.cancelRequest failed", t) }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "validateResponseAsync timed out request=$requestId")
                try { model.cancelRequest(requestId) } catch (t: Throwable) { Log.w(TAG, "cancelRequest threw", t) }
            } catch (e: Throwable) {
                Log.w(TAG, "validateResponseAsync error request=$requestId", e)
                try { model.cancelRequest(requestId) } catch (t: Throwable) { Log.w(TAG, "cancelRequest threw", t) }
            } finally {
                val raw = sb.toString().trim()
                val parsed = parseModelOutput(raw)
                val result = ValidationResult(
                    requestId = requestId,
                    rawModelOutput = raw,
                    relevance = parsed["relevance"] as? Int,
                    specificity = parsed["specificity"] as? Int,
                    actionability = parsed["actionability"] as? Int,
                    overall = parsed["overall"] as? Int,
                    needsFollowup = parsed["needs_followup"] as? Boolean,
                    followupQuestion = parsed["followup"] as? String
                )
                // invoke callback on Main to avoid UI threading issues
                try {
                    withContext(Dispatchers.Main) { onComplete(result) }
                } catch (e: Throwable) {
                    // fallback: call directly
                    try { onComplete(result) } catch (_: Throwable) {}
                }
                collectors.remove(requestId)
            }
        }

        collectors[requestId] = job
        return requestId
    }

    /**
     * Cancel an in-flight validation (best-effort).
     * Cancels the internal collector job (if any) and requests model-side cancellation.
     */
    fun cancelValidation(requestId: String) {
        Log.i(TAG, "cancelValidation request=$requestId")
        collectors.remove(requestId)?.cancel()
        try {
            model.cancelRequest(requestId)
        } catch (e: Throwable) {
            Log.w(TAG, "model.cancelRequest threw", e)
        }
    }

    /**
     * Close validator and cancel all in-flight jobs.
     * Call this when you no longer need the validator (e.g., on ViewModel.onCleared()).
     */
    fun close() {
        Log.i(TAG, "SurveyValidator.close() called — cancelling all jobs")
        collectors.values.forEach { try { it.cancel() } catch (_: Throwable) {} }
        collectors.clear()
        scope.cancel()
    }

    // --------------------------
    // Parsing helper
    // --------------------------
    /**
     * Try to parse raw output. First, if there is a JSON-looking substring, try to parse it.
     * Otherwise, attempt heuristic line-based parsing.
     */
    private fun parseModelOutput(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()

        // 1) Try to extract JSON candidate (handles cases where model prefixes text)
        extractJsonCandidate(raw)?.let { candidateJson ->
            try {
                val obj = JSONObject(candidateJson)
                return jsonObjectToMap(obj)
            } catch (e: Throwable) {
                Log.w(TAG, "Candidate JSON parse failed, will fallback to heuristic", e)
            }
        }

        // 2) Try full-string JSON parse (in case model returned pure JSON)
        try {
            val obj = JSONObject(raw)
            return jsonObjectToMap(obj)
        } catch (e: Throwable) {
            Log.w(TAG, "Full JSON parse failed, falling back to heuristic parse", e)
        }

        // 3) Heuristic parse
        val m = mutableMapOf<String, Any?>()
        val lines = raw.lines()
        val intRegex = Regex("""(relevance|specificity|actionability|overall)\s*[:=]\s*([0-2])""", RegexOption.IGNORE_CASE)
        val boolRegex = Regex("""needs_followup\s*[:=]\s*(true|false)""", RegexOption.IGNORE_CASE)
        val followupRegex = Regex("""followup\s*[:=]\s*(.*)""", RegexOption.IGNORE_CASE)

        for (ln in lines) {
            intRegex.find(ln)?.let {
                m[it.groupValues[1].lowercase(Locale.ROOT)] = it.groupValues[2].toInt()
            }
            boolRegex.find(ln)?.let {
                m["needs_followup"] = it.groupValues[1].lowercase(Locale.ROOT) == "true"
            }
            followupRegex.find(ln)?.let {
                m["followup"] = it.groupValues[1].trim().trim('"')
            }
        }

        if (m.isEmpty()) {
            m["followup"] = raw.take(1000)
        }
        return m
    }

    /**
     * Extract a JSON substring starting at the first '{' and matching braces.
     * Returns null if no balanced JSON object found.
     */
    private fun extractJsonCandidate(s: String): String? {
        val start = s.indexOf('{')
        if (start == -1) return null
        var depth = 0
        for (i in start until s.length) {
            val c = s[i]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return s.substring(start, i + 1)
            }
        }
        return null
    }

    /**
     * Convert JSONObject to a simple Map<String, Any?> with only the keys we care about.
     */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        if (obj.has("relevance")) m["relevance"] = obj.optInt("relevance", -1).takeIf { it >= 0 }
        if (obj.has("specificity")) m["specificity"] = obj.optInt("specificity", -1).takeIf { it >= 0 }
        if (obj.has("actionability")) m["actionability"] = obj.optInt("actionability", -1).takeIf { it >= 0 }
        if (obj.has("overall")) m["overall"] = obj.optInt("overall", -1).takeIf { it >= 0 }
        if (obj.has("needs_followup")) m["needs_followup"] = obj.optBoolean("needs_followup", false)
        if (obj.has("followup")) m["followup"] = obj.optString("followup").takeIf { it.isNotBlank() }
        return m
    }
}
