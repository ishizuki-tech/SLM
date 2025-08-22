package slm_chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * SurveyValidator
 *
 * - Uses InferenceModel singleton to perform response validation.
 * - Sends a structured prompt asking the LLM to return a strict JSON object.
 * - Provides suspend and callback APIs, supports timeout and cancellation.
 */

private const val TAG = "SurveyValidator"

data class ValidationResult(
    val requestId: String,
    val rawModelOutput: String,
    val relevance: Int?,        // 0..2 (null if not parsed)
    val specificity: Int?,      // 0..2
    val actionability: Int?,    // 0..2
    val overall: Int?,          // optional aggregate score 0..2
    val needsFollowup: Boolean?,
    val followupQuestion: String? // if needsFollowup == true
)

class SurveyValidator(private val context: Context) {

    private val model = InferenceModel.getInstance(context)

    // track request -> Job so we can cancel local collector if needed
    private val collectors = ConcurrentHashMap<String, Job>()

    /**
     * Build the prompt to send to the LLM.
     * We instruct the model to output a strict JSON (single line) with specific fields.
     */
    private fun buildPrompt(questionText: String, userAnswer: String): String {
        return """
            あなたはアンケートの回答を評価するアシスタントです。
            次の入力を読み、厳格なJSONを **一行** で返してください（余計な説明は絶対に書かないでください）。

            入力:
            質問文: "$questionText"
            回答: "$userAnswer"

            出力 JSON の形式（必ずこのキーを含めること）:
            {
              "relevance": <0|1|2>,        // 0=無関係, 1=部分的, 2=完全に関連
              "specificity": <0|1|2>,      // 0=抽象的/曖昧,1=部分的,2=具体的
              "actionability": <0|1|2>,    // 0=使えない,1=改善余地あり,2=そのまま使える
              "overall": <0|1|2>,          // 総合評価（任意だが推奨）
              "needs_followup": <true|false>,
              "followup": "<追質問（needs_followupがtrueの時に短く提示）>"
            }

            重要:
            - JSON 以外のテキストや説明は出力しないでください（もし出力する場合はパースできないので、呼び出し側はフォールバックします）。
            - followup は簡潔（1文）にしてください。
        """.trimIndent()
    }

    /**
     * Suspend API: validate a single response and return ValidationResult.
     * - timeoutMs: maximum wait (default 15s)
     */
    suspend fun validateResponse(
        questionText: String,
        userAnswer: String,
        timeoutMs: Long = 15_000L,
        topK: Int = 40,
        temperature: Float = 0.6f,
        randomSeed: Int? = null
    ): ValidationResult {
        val prompt = buildPrompt(questionText, userAnswer)
        val requestId = model.startRequest(prompt, topK = topK, temperature = temperature, randomSeed = randomSeed)
        Log.i(TAG, "validateResponse started request=$requestId")

        // collect partials for this requestId until done=true or timeout
        val sb = StringBuilder()
        val job = Job()
        collectors[requestId] = job

        try {
            withTimeout(timeoutMs) {
                // collect the first emission for this request, then keep reading until done
                while (isActive) {
                    val pr = model.partialResults
                        .filter { it.requestId == requestId }
                        .first() // suspend until next partial for this requestId

                    sb.append(pr.text)
                    if (pr.done) {
                        break
                    }
                    // small loop back to wait next partial
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "validateResponse timed out request=$requestId")
            // Try to cancel model-side generation best-effort
            try { model.cancelRequest(requestId) } catch (t: Throwable) { Log.w(TAG, "cancelRequest threw", t) }
        } finally {
            collectors.remove(requestId)?.cancel()
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

    /**
     * Async / callback API.
     * - Returns requestId immediately.
     * - When finished (or on cancel/timeout) the callback will be invoked once with a ValidationResult.
     */
    fun validateResponseAsync(
        questionText: String,
        userAnswer: String,
        timeoutMs: Long = 15_000L,
        topK: Int = 40,
        temperature: Float = 0.6f,
        randomSeed: Int? = null,
        onComplete: (ValidationResult) -> Unit
    ): String {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val requestId = model.startRequest(
            buildPrompt(questionText, userAnswer),
            topK = topK,
            temperature = temperature,
            randomSeed = randomSeed
        )

        val job = scope.launch {
            val sb = StringBuilder()
            try {
                withTimeout(timeoutMs) {
                    while (isActive) {
                        val pr = model.partialResults
                            .filter { it.requestId == requestId }
                            .first()
                        sb.append(pr.text)
                        if (pr.done) break
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "validateResponseAsync timed out request=$requestId")
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
                onComplete(result)
            }
        }

        collectors[requestId] = job
        return requestId
    }

    /**
     * Cancel an in-flight validation (best-effort).
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
     * Try to parse model output as JSON first. If parsing fails, attempt a lightweight key:value parse.
     * Returns a map with keys: relevance, specificity, actionability, overall, needs_followup, followup
     */
    private fun parseModelOutput(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()

        // 1) Strict JSON parse
        try {
            val obj = JSONObject(raw)
            val m = mutableMapOf<String, Any?>()
            if (obj.has("relevance")) m["relevance"] = obj.optInt("relevance", -1).takeIf { it >= 0 }
            if (obj.has("specificity")) m["specificity"] = obj.optInt("specificity", -1).takeIf { it >= 0 }
            if (obj.has("actionability")) m["actionability"] = obj.optInt("actionability", -1).takeIf { it >= 0 }
            if (obj.has("overall")) m["overall"] = obj.optInt("overall", -1).takeIf { it >= 0 }
            if (obj.has("needs_followup")) m["needs_followup"] = obj.optBoolean("needs_followup", false)
            if (obj.has("followup")) m["followup"] = obj.optString("followup").takeIf { it.isNotBlank() }
            return m
        } catch (e: Throwable) {
            Log.w(TAG, "JSON parse failed, falling back to heuristic parse", e)
        }

        // 2) Heuristic parse (lines like "relevance: 2" or "followup: ...")
        val m = mutableMapOf<String, Any?>()
        val lines = raw.lines()
        val intRegex = Regex("""(relevance|specificity|actionability|overall)\s*[:=]\s*([0-2])""", RegexOption.IGNORE_CASE)
        val boolRegex = Regex("""needs_followup\s*[:=]\s*(true|false)""", RegexOption.IGNORE_CASE)
        val followupRegex = Regex("""followup\s*[:=]\s*(.*)""", RegexOption.IGNORE_CASE)

        for (ln in lines) {
            intRegex.find(ln)?.let { m[it.groupValues[1].lowercase()] = it.groupValues[2].toInt() }
            boolRegex.find(ln)?.let { m["needs_followup"] = it.groupValues[1].lowercase() == "true" }
            followupRegex.find(ln)?.let { m["followup"] = it.groupValues[1].trim().trim('"') }
        }

        // If nothing parsed, put raw model output into followup (so caller can inspect)
        if (m.isEmpty()) {
            m["followup"] = raw.take(1000) // truncated raw
        }

        return m
    }
}
