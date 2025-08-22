package slm_chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * GemmaUiState — StateFlow ベース、スレッド安全に更新できる実装
 * 内部ではプロンプト用のマークアップを保持（表示用の sanitize は Compose 側で行う）
 */

const val USER_PREFIX = "user"
const val MODEL_PREFIX = "model"

interface UiState {
    val messages: List<ChatMessage>
    val fullPrompt: String

    fun createLoadingMessage(): String
    fun appendMessage(id: String, text: String, done: Boolean = false)
    fun appendFirstMessage(id: String, text: String)
    fun addMessage(text: String, author: String): String
}

class GemmaUiState(
    initialMessages: List<ChatMessage> = emptyList()
) : UiState {
    private val START_TURN = "<start_of_turn>"
    private val END_TURN = "<end_of_turn>"

    private val _messagesFlow = MutableStateFlow(initialMessages.toList())
    val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    // UI 用の簡易 getter（Compose から直接 messagesFlow を使うのがベター）
    override val messages: List<ChatMessage>
        get() = _messagesFlow.value.map {
            it.copy(
                message = it.message.replace(START_TURN + it.author + "\n", "")
                    .replace(END_TURN, "")
            )
        }.reversed()

    override val fullPrompt: String
        get() = _messagesFlow.value.takeLast(4).joinToString("\n") { it.message }

    override fun createLoadingMessage(): String {
        val chatMessage = ChatMessage(author = MODEL_PREFIX, isLoading = true)
        _messagesFlow.update { old -> old + chatMessage }
        return chatMessage.id
    }

    override fun appendFirstMessage(id: String, text: String) {
        appendMessage(id, "$START_TURN$MODEL_PREFIX\n$text", done = false)
    }

    override fun appendMessage(id: String, text: String, done: Boolean) {
        _messagesFlow.update { old ->
            val idx = old.indexOfFirst { it.id == id }
            if (idx == -1) return@update old
            val target = old[idx]
            val newText = if (done) target.message + text + END_TURN else target.message + text
            val copy = old.toMutableList().apply { set(idx, target.copy(message = newText, isLoading = false)) }
            copy.toList()
        }
    }

    override fun addMessage(text: String, author: String): String {
        val chatMessage = ChatMessage(
            message = "$START_TURN$author\n$text$END_TURN",
            author = author
        )
        _messagesFlow.update { old -> old + chatMessage }
        return chatMessage.id
    }
}
