package slm_chat

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

private const val TAG = "SurveyViewModel"

/**
 * ViewModel:
 * - SurveyValidator を使って検査を走らせる
 * - InferenceModel.partialResults を購読してストリーミング表示を更新する
 */
class SurveyViewModel(application: Application) : AndroidViewModel(application) {

    private val validator = SurveyValidator(application.applicationContext)
    private val model = InferenceModel.getInstance(application.applicationContext)

    // Compose の state を ViewModel 内で使うためのプロパティ（delegate を使う場合は getValue/setValue を import）
    var questionText by mutableStateOf("この冬、最も困った農作業の問題は何ですか？")
        private set

    var userAnswer by mutableStateOf("")
        private set

    var streamingText by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(false)
        private set

    var lastValidationResult by mutableStateOf<ValidationResult?>(null)
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    private var currentRequestId: String? = null
    private var streamingCollectorJob: Job? = null

    fun onQuestionChange(q: String) { questionText = q }
    fun onAnswerChange(a: String) { userAnswer = a }

    /**
     * 検証開始（非同期）。validateResponseAsync のコールバックで結果を受け取る。
     */
    fun startValidation(timeoutMs: Long = 15_000L) {
        if (isLoading) return
        lastError = null
        lastValidationResult = null
        streamingText = ""
        isLoading = true

        val reqId = validator.validateResponseAsync(
            questionText = questionText,
            userAnswer = userAnswer,
            timeoutMs = timeoutMs,
            topK = 40,
            temperature = 0.2f,
            randomSeed = null
        ) { result ->
            // コールバックはバックグラウンドスレッドから呼ばれる可能性があるため main に戻す
            viewModelScope.launch(Dispatchers.Main) {
                lastValidationResult = result
                isLoading = false
                streamingCollectorJob?.cancel()
                streamingCollectorJob = null
                currentRequestId = null
            }
        }

        currentRequestId = reqId

        // partialResults を購読して streamingText を更新 (viewModelScope は Main dispatcher)
        streamingCollectorJob?.cancel()
        streamingCollectorJob = viewModelScope.launch {
            try {
                model.partialResults
                    .filter { it.requestId == reqId }
                    .collect { pr ->
                        // UI スレッドで文字列を連結
                        streamingText = streamingText + pr.text
                        if (pr.done) {
                            // done が来たら購読は自然終了する（collect 後の処理があればここで）
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "streaming collector stopped", e)
            }
        }
    }

    /**
     * 現在の検証をキャンセル（UI から呼ぶ）
     */
    fun cancelValidation() {
        val req = currentRequestId ?: return
        try {
            validator.cancelValidation(req)
        } catch (e: Throwable) {
            Log.w(TAG, "validator.cancelValidation threw", e)
        }
        try {
            model.cancelRequest(req)
        } catch (e: Throwable) {
            Log.w(TAG, "model.cancelRequest threw", e)
        }
        streamingCollectorJob?.cancel()
        streamingCollectorJob = null
        currentRequestId = null
        isLoading = false
        streamingText = streamingText + "\n\n-- cancelled --"
    }

    override fun onCleared() {
        super.onCleared()
        streamingCollectorJob?.cancel()
        // 保険で全キャンセル（空文字は無視される実装なら問題ない）
        try { currentRequestId?.let { validator.cancelValidation(it) } } catch (_: Throwable) {}
    }
}
