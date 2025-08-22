package slm_chat

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SurveyViewModel"

/**
 * Minimal ViewModel that uses ONLY the suspend SurveyValidator.validateResponse API.
 *
 * - No streaming support (because validateResponse returns only after completion).
 * - Cancellation cancels the coroutine waiting for validateResponse (best-effort).
 */
class SurveyViewModel(private val context: Context) : ViewModel() {

    private val validator = SurveyValidator(context)

    // UI-observed state (Compose-friendly)
    var questionText by mutableStateOf("What is your name?")
        private set

    var userAnswer by mutableStateOf("")
        private set

    var validatePrompt by mutableStateOf("""
        You are an assistant that evaluates survey responses.
        Read the input below and return a strict JSON object on a single line only (do not output any extra explanation).

        The output JSON must include the following keys:
        {
          "relevance": <0|1|2>,        // 0=irrelevant, 1=partially relevant, 2=fully relevant
          "specificity": <0|1|2>,      // 0=vague, 1=somewhat specific, 2=very specific
          "actionability": <0|1|2>,    // 0=not actionable, 1=needs refinement, 2=actionable as-is
          "overall": <0|1|2>,          // optional aggregate score (recommended)
          "needs_followup": <true|false>,
          "followup": "<follow-up question (short, include only if needs_followup is true)>"
        }

        Important:
        - Output ONLY the JSON object and nothing else. If any extra text is output, parsing may fail and the caller will fall back.
        - Make the followup concise (one short sentence).
        
        
    """.trimIndent()
    )

    // we don't get partial streaming for this pattern; keep for showing raw output after finish
    var rawOutput by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(false)
        private set

    var lastValidationResult by mutableStateOf<ValidationResult?>(null)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    // Job representing the running validateResponse coroutine (so UI can cancel it)
    private var validationJob: Job? = null

    fun onPromptChange(p: String) { validatePrompt = p }
    fun onQuestionChange(q: String) { questionText = q }
    fun onAnswerChange(a: String) { userAnswer = a }

    /**
     * Start validation using the suspend API (validateResponse).
     * This is the simple A pattern you requested.
     */
    fun startValidationUsingSuspend(timeoutMs: Long = 15_000L) {
        if (isLoading) return

        lastError = null
        lastValidationResult = null
        rawOutput = ""
        isLoading = true

        // cancel previous if any
        validationJob?.cancel()

        validationJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    validator.validateResponse(
                        validatePrompt = validatePrompt,
                        questionText = questionText,
                        userAnswer = userAnswer,
                        timeoutMs = timeoutMs,
                        topK = 40,
                        temperature = 0.2f,
                        randomSeed = null
                    )
                }
                // update UI on Main
                lastValidationResult = result
                rawOutput = result.rawModelOutput
            } catch (e: Throwable) {
                Log.w(TAG, "validateResponse (suspend) failed or cancelled", e)
                lastError = e.message ?: "Validation error"
            } finally {
                isLoading = false
                validationJob = null
            }
        }
    }

    /**
     * Cancel the suspend-style validation job (best-effort).
     *
     * Note: this cancels the coroutine waiting on validateResponse. Because the validator
     * internally calls model.startRequest and we don't have the requestId until the
     * suspend function returns, this does not guarantee immediate model-side stop.
     * For guaranteed immediate model-side cancellation use the async/requestId pattern.
     */
    fun cancelValidationUsingSuspend() {
        validationJob?.cancel()
        validationJob = null

        isLoading = false
        rawOutput = rawOutput + "\n\n-- cancelled --"
    }

    override fun onCleared() {
        super.onCleared()
        validationJob?.cancel()
        // optional: close validator resources
        try { validator.close() } catch (_: Throwable) {}
    }

    // Factory for Compose viewModel(...) usage
    companion object {
        fun getFactory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SurveyViewModel::class.java)) {
                        return SurveyViewModel(context) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
