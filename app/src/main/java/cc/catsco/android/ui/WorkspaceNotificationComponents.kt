package cc.catsco.android.ui

import cc.catsco.android.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

internal enum class WorkspaceNotificationKind(val label: String) {
    Completed("任务已完成"),
    Failed("任务失败"),
    Quota("额度不足"),
}

internal data class WorkspaceNotification(
    val topic: String,
    val kind: WorkspaceNotificationKind,
    val title: String,
    val detail: String,
    val time: String,
    val reply: String,
    val unread: Boolean = true,
    val key: String,
    val sourceId: String = "",
    val finishedAt: Long = 0,
    val startedAt: Long = 0,
    val runId: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceNotificationSheet(
    notices: List<WorkspaceNotification>,
    onDismiss: () -> Unit,
    onReadAll: () -> Unit,
    onSelect: (WorkspaceNotification) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = LocalConfiguration.current.screenHeightDp < 500)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun closeWith(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                sheet.hide()
                if (!sheet.isVisible) action()
            } finally { closing = false }
        }
    }
    val colors = MaterialTheme.colorScheme
    val unreadCount = notices.count { it.unread }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = CatsCoMobileSpec.sheetShape,
        containerColor = colors.surface,
        scrimColor = Color.Black.copy(alpha = .16f),
    ) {
        Column(Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = 16.dp).testTag("review-notification-sheet")) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = 4.dp, top = 4.dp, bottom = 8.dp)) {
                    Text("通知", Modifier.semantics { heading() }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (unreadCount > 0) {
                        Text("$unreadCount 条未读", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                }
                TextButton(
                    onClick = onReadAll,
                    enabled = unreadCount > 0,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("全部已读", fontSize = 13.sp, maxLines = 1)
                }
                CatsCoHeaderAction(R.drawable.e_close, "关闭通知", decorated = false) { closeWith(onDismiss) }
            }
            if (notices.isEmpty()) {
                Text("暂无通知", Modifier.padding(horizontal = 4.dp, vertical = 20.dp), color = colors.onSurfaceVariant, fontSize = 14.sp)
            } else {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    itemsIndexed(notices, key = { _, notice -> notice.key }) { index, notice ->
                        WorkspaceNotificationRow(notice) { closeWith { onSelect(notice) } }
                        if (index < notices.lastIndex) {
                            HorizontalDivider(Modifier.padding(start = 4.dp, end = 8.dp), color = colors.outlineVariant.copy(alpha = .45f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceNotificationRow(notice: WorkspaceNotification, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .catsCoActionClickable(shape = CatsCoMobileSpec.radiusLarge, onClick = onClick)
            .semantics { stateDescription = if (notice.unread) "未读" else "已读" }
            .padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(
                notice.title, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp,
                fontWeight = if (notice.unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(5.dp)) {
                    if (notice.unread) WorkspaceNoticeUnreadDot()
                }
                Text(notice.time, fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(
            if (notice.kind == WorkspaceNotificationKind.Quota) notice.detail else "${notice.kind.label} · ${notice.detail}",
            fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceNotificationBanner(
    notice: WorkspaceNotification?,
    expanded: Boolean,
    reply: String?,
    onExpand: (WorkspaceNotification) -> Unit,
    onOpen: (WorkspaceNotification) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastContent by remember { mutableStateOf<Triple<WorkspaceNotification, Boolean, String?>?>(null) }
    val content = if (notice != null) Triple(notice, expanded, reply) else lastContent
    SideEffect { if (notice != null) lastContent = content }
    val enterOffset = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier,
        enter = slideInVertically(tween(170, easing = LinearOutSlowInEasing)) { -enterOffset } +
            fadeIn(tween(170, easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(180)),
    ) {
        if (content != null) {
            val (item, showReply, visibleReply) = content
            val colors = MaterialTheme.colorScheme
            val shape = CatsCoMobileSpec.radiusLarge
            val quotaNotice = item.kind == WorkspaceNotificationKind.Quota
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("review-notification-banner").semantics {
                    liveRegion = LiveRegionMode.Polite
                },
                shape = shape,
                color = colors.surface,
                border = BorderStroke(.5.dp, colors.outlineVariant.copy(alpha = .7f)),
                shadowElevation = 1.dp,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val expandTarget = if (showReply) Modifier else Modifier
                            .catsCoActionClickable(enabled = notice != null, shape = shape) { onExpand(item) }
                            .semantics { stateDescription = "可展开查看回复" }
                        Column(
                            Modifier.weight(1f).then(expandTarget).heightIn(min = 56.dp)
                                .padding(start = 16.dp, top = 7.dp, bottom = 7.dp, end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Text(if (quotaNotice) item.title else item.kind.label,
                                fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(if (quotaNotice) item.detail else item.title,
                                fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (showReply) Spacer(Modifier.width(CatsCoMobileSpec.minimumTouchTarget))
                        else CatsCoBoundedIconButton(
                            onClick = onDismiss,
                            enabled = notice != null,
                            modifier = Modifier.size(CatsCoMobileSpec.minimumTouchTarget),
                            visualSize = 32.dp,
                        ) {
                            Icon(painterResource(R.drawable.e_close), "关闭提示", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                    AnimatedVisibility(
                        visible = showReply,
                        enter = expandVertically(tween(180, easing = LinearOutSlowInEasing), expandFrom = Alignment.Top) +
                            fadeIn(tween(150)),
                    ) {
                        val replyMaxHeight = LocalConfiguration.current.screenHeightDp.dp * .32f
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .55f))
                            Column(Modifier.fillMaxWidth().heightIn(max = replyMaxHeight)
                                .verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 6.dp)) {
                                MarkdownContent(visibleReply ?: item.reply.ifBlank { item.detail })
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = onDismiss, enabled = notice != null,
                                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                                    Text("关闭")
                                }
                                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                                    TextButton(
                                        onClick = { onOpen(item) },
                                        enabled = notice != null,
                                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                                    ) {
                                        Text(if (quotaNotice) "查看额度" else "任务详情")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceQuotaNotice(onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("review-quota-notice"),
        shape = shape,
        color = colors.surface,
        border = BorderStroke(.5.dp, colors.outlineVariant.copy(alpha = .7f)),
    ) {
        Row(
            Modifier.fillMaxWidth().catsCoActionClickable(shape = shape, onClick = onOpen)
                .heightIn(min = CatsCoMobileSpec.minimumTouchTarget).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("模型额度已用完", Modifier.weight(1f), fontSize = 13.sp, color = colors.onSurfaceVariant)
            Text("查看额度", fontSize = 13.sp, color = colors.onSurfaceVariant)
            Icon(painterResource(R.drawable.e_next), null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun WorkspaceNoticeUnreadDot() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
}
