// ChatScreen.kt
package slm_chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun ChatRoute(
    chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.getFactory(LocalContext.current.applicationContext)
    )
) {
    val messages by chatViewModel.messagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val textInputEnabled by chatViewModel.isTextInputEnabled.collectAsStateWithLifecycle()
    ChatScreen(
        messages = messages,
        textInputEnabled = textInputEnabled,
        onSendMessage = { chatViewModel.sendMessage(it) }
    )
}

/**
 * ChatScreen:
 * - newest at bottom (reverseLayout = false)
 * - ensures bottom of the last bubble is visible when it grows by using a bottom anchor + bringIntoView
 * - suppresses auto bring while the user is actively scrolling
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    textInputEnabled: Boolean = true,
    onSendMessage: (String) -> Unit
) {
    var userMessage by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // single BringIntoViewRequester reused for the last-item bottom anchor
    val lastItemBringRequester = remember { BringIntoViewRequester() }

    // whether user is scrolling manually
    val isUserScrolling = listState.isScrollInProgress

    // When list size changes -> try to scroll to last index (layout it), then bring bottom anchor into view
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            // tiny delay to allow Compose to schedule layout
            delay(10)
            try {
                // animate/jump to last index to ensure last item is measured
                listState.animateScrollToItem(messages.lastIndex)
            } catch (_: Throwable) { /* ignore */ }

            // if user isn't interacting, request bringIntoView on the bottom anchor
            if (!isUserScrolling) {
                // small extra delay to let last item finish measuring
                delay(10)
                try {
                    lastItemBringRequester.bringIntoView()
                } catch (_: Throwable) { /* ignore */ }
            }
        }
    }

    // Also observe changes to the last message content: when it changes (grows), bring bottom into view.
    // This handles streaming partial updates (last message expands) after it's already laid out.
    LaunchedEffect(messages) {
        snapshotFlow { messages.lastOrNull()?.message }
            .collectLatest { lastMsg ->
                if (lastMsg == null) return@collectLatest
                if (messages.isEmpty()) return@collectLatest
                if (isUserScrolling) return@collectLatest
                // small delay to let layout stabilize, then bring bottom into view
                delay(12)
                try {
                    lastItemBringRequester.bringIntoView()
                } catch (_: Throwable) { /* ignore */ }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.Bottom
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            reverseLayout = false
        ) {
            itemsIndexed(messages) { index, chat ->
                val isLast = index == messages.lastIndex
                ChatItemWithBottomAnchor(
                    chatMessage = chat,
                    bringRequester = if (isLast) lastItemBringRequester else null
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF6A1B9A),
                    unfocusedTextColor = Color(0xFF6A1B9A),
                    focusedBorderColor = Color(0xFF6A1B9A),
                    unfocusedBorderColor = Color(0xFF6A1B9A)
                ),
                value = userMessage,
                onValueChange = { userMessage = it },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                label = { Text("Input") },
                modifier = Modifier.weight(0.85f),
                enabled = textInputEnabled,
            )

            IconButton(
                onClick = {
                    if (userMessage.isNotBlank()) {
                        onSendMessage(userMessage)
                        userMessage = ""
                        // small ensure bring after send (keyboard may shift layout)
                        coroutineScope.launch {
                            delay(60)
                            try { lastItemBringRequester.bringIntoView() } catch (_: Throwable) {}
                        }
                    }
                },
                modifier = Modifier
                    .weight(0.15f)
            ) {
                Icon(Icons.AutoMirrored.Default.Send, contentDescription = "Send", tint = Color(0xFF6A1B9A))
            }
        }
    }
}

/**
 * ChatItemWithBottomAnchor:
 * - renders the bubble (Card)
 * - if bringRequester != null, attach it to a tiny Spacer placed after the text (bottom anchor).
 *   bringIntoView() on that anchor brings the bottom of the bubble into view.
 */
@Composable
fun ChatItemWithBottomAnchor(
    chatMessage: ChatMessage,
    bringRequester: BringIntoViewRequester? = null
) {
    val backgroundColor = if (chatMessage.isFromUser) Color(0xFF6A1B9A) else Color(0xFF9575CD)
    val bubbleShape = if (chatMessage.isFromUser)
        RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
    else
        RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    val horizontalAlignment = if (chatMessage.isFromUser) Alignment.End else Alignment.Start

    fun sanitizeMessage(raw: String, author: String): String {
        var s = raw
        val startWithAuthor = "<start_of_turn>$author\n"
        if (s.startsWith(startWithAuthor)) s = s.removePrefix(startWithAuthor)
        s = s.replace("<start_of_turn>", "").replace("</start_of_turn>", "")
        s = s.replace("<end_of_turn>", "").replace("</end_of_turn>", "")
        return s.trim()
    }

    Column(
        horizontalAlignment = horizontalAlignment,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
    ) {
        // author label above bubble
        val authorLabel = if (chatMessage.isFromUser) "User" else "Model"
        Text(
            color = Color(0xFF212121),
            text = authorLabel,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            shape = bubbleShape,
            modifier = Modifier.widthIn(0.dp, 900.dp) // optional width cap; tune or remove
        ) {
            if (chatMessage.isLoading) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF6A1B9A))
                }
            } else {
                val authorKey = if (chatMessage.isFromUser) USER_PREFIX else MODEL_PREFIX
                val response = sanitizeMessage(chatMessage.message, authorKey)

                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = response)
                    // tiny bottom anchor to bring the bottom of the Card into view
                    if (bringRequester != null) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .bringIntoViewRequester(bringRequester)
                        )
                    }
                }
            }
        }
    }
}
