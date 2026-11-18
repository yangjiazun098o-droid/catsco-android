package cc.catsco.android.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import cc.catsco.android.data.MessageTurn
import kotlinx.coroutines.flow.collectLatest

/** Following is user intent, retained across incoming messages and streaming remeasurement. */
@Composable
internal fun ConversationTimeline(
    topic: String,
    turns: List<MessageTurn>,
    userId: String,
    loading: Boolean,
    hasMore: Boolean,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "还没有消息，输入内容开始任务。",
    targetMessageId: Long = 0,
    topContentPadding: Dp = 0.dp,
    content: @Composable (MessageTurn) -> Unit,
) {
    key(topic) {
        val list = rememberLazyListState()
        var following by rememberSaveable { mutableStateOf(true) }
        var userScrolling by remember { mutableStateOf(false) }
        var previousUserKey by rememberSaveable { mutableStateOf<String?>(null) }
        val newestUserKey = turns.lastOrNull { it.senderId == userId }?.key
        val atLatest by remember { derivedStateOf { !list.canScrollForward } }
        var locatedTarget by rememberSaveable { mutableLongStateOf(0) }
        var priorTarget by rememberSaveable { mutableLongStateOf(0) }
        var highlightedKey by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(targetMessageId) {
            if (targetMessageId == 0L && priorTarget > 0) {
                following = true
                locatedTarget = 0
                highlightedKey = null
            }
            priorTarget = targetMessageId
        }

        LaunchedEffect(targetMessageId, turns) {
            if (targetMessageId <= 0 || locatedTarget == targetMessageId) return@LaunchedEffect
            following = false
            val index = turns.indexOfFirst { it.messageId == targetMessageId || targetMessageId in it.sourceMessageIds }
            if (index >= 0) {
                list.scrollToItem(index + 1)
                locatedTarget = targetMessageId
                highlightedKey = turns[index].key
            }
        }
        LaunchedEffect(highlightedKey) {
            if (highlightedKey != null) { kotlinx.coroutines.delay(1800); highlightedKey = null }
        }

        LaunchedEffect(newestUserKey) {
            val priorIndex = turns.indexOfFirst { it.key == previousUserKey }
            val newIndex = turns.indexOfFirst { it.key == newestUserKey }
            if (targetMessageId == 0L && priorIndex >= 0 && newIndex > priorIndex) following = true
            previousUserKey = newestUserKey
        }
        LaunchedEffect(list) {
            list.interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Start) {
                    following = false
                    userScrolling = true
                }
            }
        }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
                if (!scrolling && userScrolling) {
                    following = !list.canScrollForward
                    userScrolling = false
                }
            }
        }
        LaunchedEffect(list) {
            snapshotFlow {
                val layout = list.layoutInfo
                // The final item's size catches growth inside one long assistant turn.
                listOf(following, layout.totalItemsCount, layout.viewportEndOffset,
                    layout.visibleItemsInfo.lastOrNull()?.size, list.canScrollForward)
            }.collectLatest {
                if (following && list.canScrollForward && list.layoutInfo.totalItemsCount > 0) {
                    // Schedule the position for the next measure, including AndroidView resizing.
                    list.requestScrollToItem(list.layoutInfo.totalItemsCount - 1)
                }
            }
        }
        Box(modifier) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("conversation_timeline"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topContentPadding + 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                item(key = "older") {
                    if (hasMore) TextButton(onClick = onLoadOlder, enabled = !loading) { Text(if (loading) "正在读取…" else "加载更早的消息") }
                }
                items(turns, key = { it.key }) { turn ->
                    Box(Modifier.fillMaxWidth().then(if (highlightedKey == turn.key)
                        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .06f), RoundedCornerShape(15.dp)) else Modifier)) {
                        content(turn)
                    }
                }
                if (turns.isEmpty()) item(key = "empty") {
                    Text(if (loading) "正在读取消息…" else emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
            }
            if (targetMessageId == 0L && !atLatest && !following && turns.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { following = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).testTag("conversation_latest"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 3.dp),
                ) { Text("↓ 回到最新") }
            }
        }
    }
}
