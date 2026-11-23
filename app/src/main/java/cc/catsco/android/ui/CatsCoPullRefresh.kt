package cc.catsco.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val CatsCoRefreshHeight = 32.dp

@Stable
@OptIn(ExperimentalMaterial3Api::class)
internal class CatsCoPullRefreshState(
    internal val pull: PullToRefreshState,
    private val active: () -> Boolean,
    private val loading: () -> Boolean,
    internal val refresh: () -> Unit,
) {
    val refreshing get() = active() && loading()
    val progress get() = pull.distanceFraction.coerceIn(0f, 1f)
    val indicatorVisible by derivedStateOf { active() && (refreshing || progress > .01f) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberCatsCoPullRefresh(active: Boolean, loading: Boolean, onRefresh: () -> Unit): CatsCoPullRefreshState {
    val pull = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val surfaceActive by rememberUpdatedState(active)
    val requestRunning by rememberUpdatedState(loading)
    val request by rememberUpdatedState(onRefresh)
    var showFeedback by remember { mutableStateOf(false) }
    return remember(pull, scope) {
        CatsCoPullRefreshState(pull, { surfaceActive }, { requestRunning || showFeedback }) {
            showFeedback = true
            request()
            scope.launch {
                delay(160)
                showFeedback = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.catsCoPullRefresh(state: CatsCoPullRefreshState, enabled: Boolean): Modifier =
    pullToRefresh(isRefreshing = state.refreshing, state = state.pull, enabled = enabled,
        threshold = 64.dp, onRefresh = state.refresh)

internal fun Modifier.catsCoRefreshTranslation(state: CatsCoPullRefreshState): Modifier =
    graphicsLayer { translationY = CatsCoRefreshHeight.toPx() * state.progress }

@Composable
internal fun CatsCoRefreshIndicator(state: CatsCoPullRefreshState, tag: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(CatsCoRefreshHeight), contentAlignment = Alignment.Center) {
        val ring = Modifier.size(18.dp).testTag(tag).graphicsLayer {
            alpha = (state.progress * 2f).coerceIn(0f, 1f)
        }
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        if (state.refreshing) {
            CircularProgressIndicator(modifier = ring, color = color, strokeWidth = 2.dp)
        } else {
            CircularProgressIndicator(progress = { state.progress }, modifier = ring, color = color, strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun CatsCoRefreshSlot(state: CatsCoPullRefreshState, tag: String, top: Dp = 0.dp) {
    if (state.indicatorVisible) {
        CatsCoRefreshIndicator(state, tag, Modifier.offset {
            val y = top.toPx() + CatsCoRefreshHeight.toPx() * (state.progress - 1f) / 2f
            IntOffset(0, y.roundToInt())
        })
    }
}
