package slm_chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun SurveyRoute(
    surveyViewModel: SurveyViewModel = viewModel(
        factory = SurveyViewModel.getFactory(LocalContext.current.applicationContext)
    )
) {
    SurveyScreen(surveyViewModel)
}

/**
 * Survey screen without streaming pane.
 *
 * - Edit question
 * - Enter free-text answer
 * - Start validation / Cancel (uses suspend-style ViewModel helpers)
 * - Show/edit prompt with tap-to-toggle label
 * - Show parsed final result and errors
 * - Entire screen is vertically scrollable
 */
@Composable
fun SurveyScreen(viewModel: SurveyViewModel) {
    // Read state directly from ViewModel (backed by mutableStateOf)
    val prompt by remember { derivedStateOf { viewModel.validatePrompt } }
    val question by remember { derivedStateOf { viewModel.questionText } }
    val answer by remember { derivedStateOf { viewModel.userAnswer } }
    val loading by remember { derivedStateOf { viewModel.isLoading } }
    val result by remember { derivedStateOf { viewModel.lastValidationResult } }
    val error by remember { derivedStateOf { viewModel.lastError } }

    val scrollState = rememberScrollState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)        // enable vertical scrolling for whole screen
                .padding(padding)
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "Survey Validator",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            // Question
            Text("Question", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = question,
                onValueChange = { viewModel.onQuestionChange(it) },
                label = { Text("Question") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            // Answer
            Text("Answer (free text)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = { viewModel.onAnswerChange(it) },
                label = { Text("Answer (free text)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )

            Spacer(Modifier.height(12.dp))

            // Actions
            Row {
                Button(
                    onClick = { viewModel.startValidationUsingSuspend() },
                    enabled = !loading
                ) {
                    Text(if (loading) "Validating..." else "Start Validation")
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = { viewModel.cancelValidationUsingSuspend() },
                    enabled = loading
                ) {
                    Text("Cancel")
                }
            }

            Spacer(Modifier.height(20.dp))

            // Prompt (collapsible)
            SurveyScreen_PromptSection(
                viewModel = viewModel,
                prompt = prompt,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))

            // Parsed result
            Text("Final result (parsed)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Surface(
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(12.dp)) {
                    if (result == null) {
                        Text("No result yet.")
                    } else {
                        val r = result!!
                        Column {
                            Text("rawModelOutput:", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(r.rawModelOutput, modifier = Modifier.padding(bottom = 6.dp))
                            Spacer(Modifier.height(3.dp))

                            Text("relevance: ${r.relevance}")
                            Text("specificity: ${r.specificity}")
                            Text("actionability: ${r.actionability}")
                            Spacer(Modifier.height(8.dp))
                            Text("overall: ${r.overall}")
                            Text("needsFollowup: ${r.needsFollowup}")
                            Text("followupQuestion: ${r.followupQuestion ?: "-"}")
                        }
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text("Error: $error", color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(48.dp)) // bottom padding
        }
    }
}


// --- Prompt section (collapsible) ---
@Composable
fun SurveyScreen_PromptSection(
    viewModel: SurveyViewModel,
    prompt: String,
    modifier: Modifier = Modifier
) {
    // local UI state for expanded/collapsed. Move to ViewModel if you want persistence across rotation.
    var promptExpanded by remember { mutableStateOf(true) }

    Column(modifier = modifier.animateContentSize()) {
        // Clickable label row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { promptExpanded = !promptExpanded }
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = "Prompt",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (promptExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (promptExpanded) "Collapse" else "Expand"
            )
        }

        Spacer(Modifier.height(6.dp))

        if (promptExpanded) {
            // Expanded: editable OutlinedTextField
            OutlinedTextField(
                value = prompt,
                onValueChange = { viewModel.onPromptChange(it) },
                label = { Text("Prompt") },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // Collapsed: compact preview row (tap expands)
            Surface(
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { promptExpanded = true }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (prompt.isBlank()) "Tap to edit prompt" else prompt,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(imageVector = Icons.Default.ExpandMore, contentDescription = "Expand")
                }
            }
        }
    }
}
