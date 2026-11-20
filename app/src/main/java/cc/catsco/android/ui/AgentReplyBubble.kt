package cc.catsco.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

private val bubbleOpeningEasing = CubicBezierEasing(.2f, .8f, .2f, 1f)
private val replyBubbleShape = RoundedCornerShape(15.dp)

/** Measure the reply at its reading width while its single surface opens from the loader. */
@Composable
internal fun AgentReplyBubble(loading: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val phase = remember { Animatable(if (loading) 0f else 1f) }
    LaunchedEffect(loading) {
        if (loading) phase.snapTo(0f)
        else phase.animateTo(1f, tween(180, easing = LinearEasing))
    }
    val showDots by remember(phase) { derivedStateOf { phase.value < .5f } }
    Surface(
        modifier.testTag(if (loading) "agent-loading-bubble" else "agent-response-bubble"),
        shape = replyBubbleShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Layout(content = {
            Box(Modifier.fillMaxWidth().graphicsLayer {
                alpha = ((phase.value - .12f) / .7f).coerceIn(0f, 1f)
            }) { content() }
            if (showDots) {
                Box(Modifier.size(68.dp, 42.dp).graphicsLayer {
                    alpha = (1f - phase.value * 2f).coerceIn(0f, 1f)
                }, contentAlignment = Alignment.Center) { AgentExchangeDots() }
            }
        }) { measurables, constraints ->
            val body = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val dots = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val smallWidth = 68.dp.roundToPx().coerceAtMost(constraints.maxWidth)
            val smallHeight = 42.dp.roundToPx()
            val opening = bubbleOpeningEasing.transform(phase.value)
            val width = constraints.constrainWidth((smallWidth + (body.width - smallWidth) * opening).roundToInt())
            val height = constraints.constrainHeight((smallHeight + (body.height - smallHeight) * opening).roundToInt())
            layout(width, height) {
                body.placeRelative(0, 0)
                dots?.placeRelative(0, 0)
            }
        }
    }
}
