package cc.catsco.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import dev.chrisbanes.haze.HazeState

internal const val disclosureDuration = 180

@Composable
internal fun CatsCoSectionReveal(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier.semantics { if (!expanded) hideFromAccessibility() },
        enter = expandVertically(tween(disclosureDuration, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(150, easing = LinearOutSlowInEasing)),
        exit = shrinkVertically(tween(disclosureDuration, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(120)),
    ) { content() }
}

@Composable
internal fun CatsCoConversationHeaderGlass(backdrop: HazeState, visible: Boolean, modifier: Modifier, overWallpaper: Boolean = false) {
    val glass = updateTransition(visible, label = "conversationHeaderGlass")
    val opacity = glass.animateFloat(
        transitionSpec = { if (targetState) tween(160, easing = LinearOutSlowInEasing) else tween(140) },
        label = "conversationHeaderGlassOpacity",
    ) { if (it) 1f else 0f }
    if (glass.currentState || glass.targetState) {
        CatsCoHeaderGlass(backdrop, modifier.testTag("conversation-header-glass"), overWallpaper, opacity = { opacity.value })
    }
}
