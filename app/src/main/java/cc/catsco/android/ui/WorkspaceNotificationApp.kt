package cc.catsco.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.catsco.android.WorkspaceState
import cc.catsco.android.WorkspaceViewModel
import kotlinx.coroutines.delay

private const val bannerTimeoutMillis = 3_000L
private const val headerBadgeMillis = 60_000L

@Composable
internal fun WorkspaceNotificationApp(
    state: WorkspaceState,
    model: WorkspaceViewModel,
    notices: List<WorkspaceNotification>,
    latestKey: String?,
    onReadAll: () -> Unit,
    onOpen: (WorkspaceNotification) -> Unit,
    resolveReply: suspend (WorkspaceNotification) -> String? = { null },
    quotaNotice: WorkspaceNotification? = null,
) {
    var banner by remember { mutableStateOf<WorkspaceNotification?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<String?>(null) }
    var showBadge by remember { mutableStateOf(false) }
    var showCenter by remember { mutableStateOf(false) }

    fun dismissBanner() {
        banner = null
        expanded = false
        reply = null
    }

    fun openNotice(notice: WorkspaceNotification) {
        dismissBanner()
        showCenter = false
        onOpen(notice)
    }

    LaunchedEffect(latestKey) {
        val latest = notices.firstOrNull { it.key == latestKey } ?: return@LaunchedEffect
        if (!showCenter && !expanded) banner = latest
        showBadge = true
        delay(headerBadgeMillis)
        showBadge = false
    }
    LaunchedEffect(banner?.key, expanded) {
        val showing = banner ?: return@LaunchedEffect
        if (expanded) {
            reply = resolveReply(showing)
        } else {
            delay(bannerTimeoutMillis)
            if (!expanded && banner?.key == showing.key) dismissBanner()
        }
    }

    Box(Modifier.fillMaxSize()) {
        CatsCoApp(
            state, model,
            notificationUnreadCount = notices.count { it.unread },
            showNotificationBadge = showBadge,
            onNotifications = { dismissBanner(); showCenter = true },
            notificationBanner = {
                WorkspaceNotificationBanner(
                    banner, expanded, reply,
                    onExpand = { if (banner?.key == it.key) expanded = true },
                    onOpen = ::openNotice,
                    onDismiss = ::dismissBanner,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp)
                        .padding(top = CatsCoMobileSpec.headerHeight + 4.dp),
                )
            },
            composerNotice = {
                AnimatedVisibility(
                    visible = quotaNotice != null && banner?.kind != WorkspaceNotificationKind.Quota,
                    enter = expandVertically(tween(170, easing = LinearOutSlowInEasing), expandFrom = Alignment.Bottom) +
                        fadeIn(tween(170, easing = LinearOutSlowInEasing)),
                ) {
                    Box(Modifier.padding(bottom = 5.dp)) {
                        WorkspaceQuotaNotice { quotaNotice?.let(::openNotice) }
                    }
                }
            },
        )
        if (showCenter) WorkspaceNotificationSheet(notices, onDismiss = { showCenter = false }, onReadAll, ::openNotice)
    }
}
