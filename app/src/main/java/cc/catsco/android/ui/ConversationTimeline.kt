package cc.catsco.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import cc.catsco.android.data.MessageTurn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    loadingOlder: Boolean = false,
    olderLoadFailed: Boolean = false,
    localSendRevision: Long = 0,
    onMessagesBehindHeader: ((Boolean) -> Unit)? = null,
    content: @Composable (MessageTurn) -> Unit,
) {
    key(topic) {
        val list = rememberLazyListState()
        val topGap = 10.dp
        val topGapPx = with(LocalDensity.current) { topGap.roundToPx() }
        val usesOverlayHeader = topContentPadding > 0.dp
        val reportHeaderOverlap by rememberUpdatedState(onMessagesBehindHeader)
        if (onMessagesBehindHeader != null) {
            LaunchedEffect(list, usesOverlayHeader, topGapPx) {
                snapshotFlow {
                    usesOverlayHeader && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > topGapPx)
                }.collectLatest { reportHeaderOverlap?.invoke(it) }
            }
        }
        var following by rememberSaveable { mutableStateOf(true) }
        var userScrolling by remember { mutableStateOf(false) }
        var previousLocalSend by rememberSaveable { mutableLongStateOf(localSendRevision) }
        val atLatest by remember { derivedStateOf { !list.canScrollForward } }
        val nearOldest by remember {
            derivedStateOf { list.layoutInfo.visibleItemsInfo.isNotEmpty() && list.firstVisibleItemIndex <= 1 }
        }
        val canLoadOlder by rememberUpdatedState(hasMore && !loading && !olderLoadFailed &&
            targetMessageId == 0L && turns.isNotEmpty())
        val loadOlder by rememberUpdatedState(onLoadOlder)
        var locatedTarget by rememberSaveable { mutableLongStateOf(0) }
        var priorTarget by rememberSaveable { mutableLongStateOf(0) }
        var highlightedKey by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        var returningToLatest by remember { mutableStateOf(false) }
        val highlightColor = MaterialTheme.colorScheme.primary

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
                list.scrollToItem(index)
                locatedTarget = targetMessageId
                highlightedKey = turns[index].key
            }
        }
        LaunchedEffect(highlightedKey) {
            if (highlightedKey != null) { kotlinx.coroutines.delay(1800); highlightedKey = null }
        }

        LaunchedEffect(localSendRevision) {
            if (targetMessageId == 0L && localSendRevision > previousLocalSend) following = true
            previousLocalSend = localSendRevision
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
            snapshotFlow { canLoadOlder && nearOldest && (!following || (!list.canScrollForward && !list.canScrollBackward)) }
                .collect { shouldLoad -> if (shouldLoad) loadOlder() }
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topContentPadding + topGap, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(turns, key = { it.key }) { turn ->
                    Box(Modifier.fillMaxWidth().then(if (highlightedKey == turn.key)
                        Modifier.background(highlightColor.copy(alpha = .06f), RoundedCornerShape(15.dp)) else Modifier)) {
                        content(turn)
                    }
                }
                if (turns.isEmpty()) item(key = "empty") {
                    Text(if (loading) "正在读取消息…" else emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
            }
            if (nearOldest && (loadingOlder || olderLoadFailed)) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = topContentPadding + 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    if (loadingOlder) Text("正在加载更早的消息…", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium)
                    else TextButton(onClick = onLoadOlder, enabled = !loading) { Text("加载失败，点此重试") }
                }
            }
            AnimatedVisibility(
                visible = targetMessageId == 0L && !atLatest && !following && turns.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(100)),
            ) {
                FilledTonalButton(
                    onClick = {
                        if (!returningToLatest) {
                            returningToLatest = true
                            scope.launch {
                                val layout = list.layoutInfo
                                val destination = layout.totalItemsCount - 1
                                val lastVisible = layout.visibleItemsInfo.lastOrNull()
                                val viewport = layout.viewportEndOffset - layout.viewportStartOffset
                                val nearby = lastVisible != null && destination - lastVisible.index <= 3 &&
                                    lastVisible.offset + lastVisible.size - layout.viewportEndOffset <= viewport
                                try {
                                    if (destination >= 0) {
                                        if (nearby) list.animateScrollToItem(destination) else list.scrollToItem(destination)
                                        following = true
                                    }
                                } finally {
                                    returningToLatest = false
                                }
                            }
                        }
                    },
                    enabled = !returningToLatest && !atLatest && !following,
                    modifier = Modifier.testTag("conversation_latest"),
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
