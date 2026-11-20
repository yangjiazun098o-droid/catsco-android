package cc.catsco.android.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun Modifier.catsCoActionClickable(
    enabled: Boolean = true,
    shape: Shape? = null,
    verticalInset: Dp = 0.dp,
    onClick: () -> Unit,
): Modifier =
    clickable(
        interactionSource = null,
        indication = CatsCoPressFeedback(MaterialTheme.colorScheme.onSurface, shape, verticalInset),
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
    )

@Composable
internal fun Modifier.catsCoDisclosureClickable(
    expanded: Boolean,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = null,
    indication = CatsCoPressFeedback(MaterialTheme.colorScheme.onSurface, pressOpacity = 0f),
    enabled = enabled,
    role = Role.Button,
    onClickLabel = onClickLabel,
    onClick = onClick,
).semantics { if (enabled) stateDescription = if (expanded) "已展开" else "已收起" }

internal data class CatsCoPressFeedback(
    val color: Color,
    val shape: Shape? = null,
    val verticalInset: Dp = 0.dp,
    val pressOpacity: Float = .055f,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        StateLayer(interactionSource, color, shape, verticalInset, pressOpacity)
}

private class StateLayer(
    private val interactionSource: InteractionSource,
    private val color: Color,
    private val shape: Shape?,
    private val verticalInset: Dp,
    private val pressOpacity: Float,
) : Modifier.Node(), DrawModifierNode {
    private var opacity by mutableFloatStateOf(0f)

    override fun onAttach() {
        var pressed = false
        var focused = false
        var hovered = false
        var fade: Job? = null
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    is HoverInteraction.Enter -> hovered = true
                    is HoverInteraction.Exit -> hovered = false
                    else -> return@collect
                }
                val target = when {
                    focused -> .11f
                    pressed && pressOpacity > 0f -> pressOpacity
                    hovered -> .04f
                    else -> 0f
                }
                fade?.cancel()
                if (target >= opacity) opacity = target
                else fade = launch {
                    animate(opacity, target, animationSpec = tween(80, easing = LinearOutSlowInEasing)) { value, _ ->
                        opacity = value
                    }
                }
            }
        }
    }

    override fun onDetach() {
        opacity = 0f
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = opacity
        if (alpha <= 0f) return
        val inset = verticalInset.toPx()
        val visualSize = Size(size.width, (size.height - 2 * inset).coerceAtLeast(0f))
        translate(top = inset) {
            if (shape == null) drawRect(color, size = visualSize, alpha = alpha)
            else drawOutline(shape.createOutline(visualSize, layoutDirection, this), color, alpha = alpha)
        }
    }
}
