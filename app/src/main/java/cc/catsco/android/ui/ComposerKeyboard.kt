package cc.catsco.android.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberComposerImeInsets(): WindowInsets {
    val current = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    return remember(current, source, target) { ComposerImeInsets(current, source, target) }
}

internal class ComposerImeInsets(
    private val current: WindowInsets,
    private val source: WindowInsets,
    private val target: WindowInsets,
) : WindowInsets by current {
    override fun getBottom(density: Density): Int {
        // The IME still covers the shared area while changing between two visible heights.
        val covered = minOf(source.getBottom(density), target.getBottom(density))
        return maxOf(current.getBottom(density), covered)
    }
}
