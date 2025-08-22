package slm_chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding // ✅ needed
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Simple, robust loading screen.
 *
 * - Minimal Compose API surface; compiles across versions.
 * - Warms model via InferenceModel.getInstance(context).
 * - Small animated Canvas logo + pulsing title + retry card.
 * - All comments in English.
 */
@Composable
fun LoadingRouteSimple(
    onModelLoaded: () -> Unit = {},
    onRetry: (() -> Unit)? = null
) {
    val appCtx = LocalContext.current.applicationContext
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF111213)) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle animated gradient background
            val transition = rememberInfiniteTransition()
            val t by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 7000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
            val bgBrush = Brush.linearGradient(
                colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B)),
                start = Offset(0f + t * 200f, 0f),
                end = Offset(400f - t * 200f, 800f),
                tileMode = TileMode.Clamp
            )
            Box(modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
            )

            // Center content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentHeight(align = Alignment.CenterVertically)
                    .wrapContentWidth(align = Alignment.CenterHorizontally),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AnimatedLogoSimple(logoSize = 72.dp)

                Spacer(modifier = Modifier.height(16.dp))

                PulsingTitle("Loading model…")

                Spacer(modifier = Modifier.height(18.dp))

                if (loading && errorMessage == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(36.dp)
                                .semantics { contentDescription = "Loading model progress" },
                            strokeWidth = 3.dp,
                            color = Color(0xFF9CA3FF)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Warming up engine",
                            color = Color(0xFFE6EEF8),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                errorMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(12.dp))
                    SimpleErrorCard(message = msg) {
                        errorMessage = null
                        loading = true
                        onRetry?.invoke()
                    }
                }
            }
        }
    }

    // Warm the model off the main thread
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                InferenceModel.getInstance(appCtx) // keep your existing initializer
                withContext(Dispatchers.Main) {
                    loading = false
                    onModelLoaded()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading = false
                    errorMessage = e.localizedMessage ?: "Unknown error while loading model"
                }
            }
        }
    }
}

/* ---------------------------
   Small, simple Canvas logo
   --------------------------- */
@Composable
private fun AnimatedLogoSimple(logoSize: Dp) {
    val anim = rememberInfiniteTransition()
    // Breathing scale factor for the center dot
    val breath by anim.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Canvas(modifier = Modifier.size(logoSize)) {
        // DrawScope.size is already in pixels
        val diameterPx = kotlin.math.min(size.width, size.height)
        val radius = diameterPx / 2f

        // Static outer ring with a subtle radial gradient
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFEEF2FF).copy(alpha = 0.14f), Color.Transparent),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        // Breathing center dot (scale by changing the drawn radius)
        val dotRadius = radius * 0.22f * breath
        drawCircle(
            color = Color.White,
            radius = dotRadius,
            center = center
        )
    }
}
/* ---------------------------
   Pulsing title (simple)
   --------------------------- */
@Composable
private fun PulsingTitle(text: String) {
    val anim = rememberInfiniteTransition()
    val alpha by anim.animateFloat(
        initialValue = 0.70f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    Text(
        text = text,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFFEFF6FF).copy(alpha = alpha)
    )
}

/* ---------------------------
   Error card
   --------------------------- */
@Composable
private fun SimpleErrorCard(message: String, onRetry: () -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Failed to load",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF6B6B)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRetry) {
                    Text(text = "Retry")
                }
            }
        }
    }
}
