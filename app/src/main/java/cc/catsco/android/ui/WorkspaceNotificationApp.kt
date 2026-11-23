package cc.catsco.android.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
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
    onRead: (WorkspaceNotification) -> Unit = {},
    arrivalElapsed: Long? = null,
    openCenterRequest: Int = 0,
    onCenterOpened: () -> Unit = {},
) {
    var bannerKey by rememberSaveable { mutableStateOf<String?>(null) }
    val banner = notices.firstOrNull { it.key == bannerKey }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var reply by remember { mutableStateOf<String?>(null) }
    var showBadge by remember { mutableStateOf(false) }
    var showCenter by rememberSaveable { mutableStateOf(false) }
    var lastHandled by rememberSaveable { mutableStateOf<String?>(null) }
    val arrival = remember(latestKey) { arrivalElapsed ?: SystemClock.elapsedRealtime() }
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(
        bannerTimeoutMillis, containsText = true, containsControls = true) ?: bannerTimeoutMillis

    fun dismissBanner() {
        bannerKey = null
        expanded = false
        reply = null
    }

    fun openNotice(notice: WorkspaceNotification) {
        dismissBanner()
        showCenter = false
        onOpen(notice)
    }

    LaunchedEffect(openCenterRequest) {
        if (openCenterRequest > 0) {
            dismissBanner()
            showCenter = true
            onCenterOpened()
        }
    }

    LaunchedEffect(latestKey) {
        val latest = notices.firstOrNull { it.key == latestKey } ?: return@LaunchedEffect
        val age = (SystemClock.elapsedRealtime() - arrival).coerceAtLeast(0)
        if (latest.key != lastHandled) {
            if (!showCenter && !expanded && latest.unread && age < timeout) bannerKey = latest.key
            lastHandled = latest.key
        }
        showBadge = age < headerBadgeMillis
        delay((headerBadgeMillis - age).coerceAtLeast(0))
        showBadge = false
    }
    LaunchedEffect(banner?.key, expanded, timeout) {
        val showing = banner ?: return@LaunchedEffect
        if (expanded) {
            reply = resolveReply(showing)
        } else {
            val age = (SystemClock.elapsedRealtime() - arrival).coerceAtLeast(0)
            delay((timeout - age).coerceAtLeast(0))
            if (!expanded && bannerKey == showing.key) dismissBanner()
        }
    }

    Box(Modifier.fillMaxSize()) {
        CatsCoApp(
            state, model,
            notificationUnreadCount = notices.count { it.unread },
            showNotificationBadge = showBadge && notices.any { it.unread },
            onNotifications = { dismissBanner(); showCenter = true },
            notificationBanner = {
                WorkspaceNotificationBanner(
                    banner, expanded, reply,
                    onExpand = { if (bannerKey == it.key) { expanded = true; onRead(it) } },
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
        BackHandler(enabled = expanded && banner != null && !showCenter, onBack = ::dismissBanner)
    }
}
