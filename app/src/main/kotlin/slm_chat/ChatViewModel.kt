package slm_chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ChatViewModel — GemmaUiState を内包し messagesFlow を公開
 * InferenceModel は startRequest / partialResults / cancelRequest を想定
 */

class ChatViewModel(
    private val inferenceModel: InferenceModel
) : ViewModel() {

    private val gemmaState = GemmaUiState()
    val messagesFlow: StateFlow<List<ChatMessage>> = gemmaState.messagesFlow

    private val _textInputEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true)
    val isTextInputEnabled: StateFlow<Boolean> = _textInputEnabled.asStateFlow()

    @Volatile
    private var currentRequestId: String? = null

    fun sendMessage(userMessage: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1) Add user message on Main
            withContext(Dispatchers.Main) {
                gemmaState.addMessage(userMessage, USER_PREFIX)
            }

            // 2) Create loading message (Main)
            val currentMessageId = withContext(Dispatchers.Main) {
                gemmaState.createLoadingMessage()
            }

            // disable input (Main)
            withContext(Dispatchers.Main) { setInputEnabled(false) }

            // optionally cancel previously tracked request
            currentRequestId?.let { prevId ->
                try { inferenceModel.cancelRequest(prevId) } catch (_: Throwable) {}
                currentRequestId = null
            }

            try {
                val fullPrompt = gemmaState.fullPrompt
                val requestId = inferenceModel.startRequest(fullPrompt)
                currentRequestId = requestId

                inferenceModel.partialResults
                    .filter { it.requestId == requestId }
                    .collectIndexed { index, pr ->
                        withContext(Dispatchers.Main) {
                            if (index == 0) {
                                gemmaState.appendFirstMessage(currentMessageId, pr.text)
                            } else {
                                gemmaState.appendMessage(currentMessageId, pr.text, pr.done)
                            }
                            if (pr.done) {
                                currentRequestId = null
                                setInputEnabled(true)
                            }
                        }
                    }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    gemmaState.addMessage(e.localizedMessage ?: "Unknown Error", MODEL_PREFIX)
                    setInputEnabled(true)
                }
            } finally {
                currentRequestId = null
            }
        }
    }

    fun cancelCurrentRequest() {
        val id = currentRequestId ?: return
        try {
            inferenceModel.cancelRequest(id)
        } finally {
            currentRequestId = null
            viewModelScope.launch(Dispatchers.Main) { setInputEnabled(true) }
        }
    }

    private fun setInputEnabled(isEnabled: Boolean) {
        _textInputEnabled.value = isEnabled
    }

    override fun onCleared() {
        super.onCleared()
        try { inferenceModel.close() } catch (_: Throwable) {}
    }

    companion object {
        fun getFactory(context: Context) = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val inferenceModel = InferenceModel.getInstance(context)
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(inferenceModel) as T
            }
        }
    }
}
