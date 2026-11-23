package cc.catsco.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.catsco.android.ui.CatsCoApp
import cc.catsco.android.ui.CatsCoSessionSplash
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.WorkspaceNotificationApp
import cc.catsco.android.ui.WorkspaceNotificationKind

class MainActivity : ComponentActivity() {
    private var inboundIntent by mutableStateOf<Intent?>(null)
    private var inboundHandled = false
    private var notificationCenterRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        inboundHandled = savedInstanceState?.getBoolean("inboundHandled") ?: false
        if (!inboundHandled) inboundIntent = intent?.takeIf {
            it.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, openTaskNotices)
        }
        setContent {
            val model: WorkspaceViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val inbox by model.notifications.collectAsStateWithLifecycle()
            val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(owner, model) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_START -> model.foreground(true)
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> model.foreground(false)
                        else -> Unit
                    }
                }
                owner.lifecycle.addObserver(observer)
                onDispose { owner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(state.session?.userId, state.busy, inboundIntent) {
                val incoming = inboundIntent
                if (state.session != null && !state.busy && incoming != null) {
                    if (incoming.action == openTaskNotices) {
                        if (incoming.getStringExtra("account") == state.session?.userId &&
                            incoming.getStringExtra("server") == state.session?.server?.value) notificationCenterRequest++
                        inboundIntent = null
                        inboundHandled = true
                    } else if (incoming.action == Intent.ACTION_SEND) {
                        model.navigate("home")
                        incoming.getStringExtra(Intent.EXTRA_TEXT)?.let { text -> model.draft(listOf(model.state.value.draft, text).filter(String::isNotBlank).joinToString("\n\n")) }
                        androidx.core.content.IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, android.net.Uri::class.java)?.let(model::attach)
                        inboundIntent = null
                        inboundHandled = true
                    } else {
                        conversationTopic(incoming.data)?.let { model.openConversation(it, "会话") }
                        inboundIntent = null
                        inboundHandled = true
                    }
                }
            }
            val dark = when (state.appearance) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                val bars = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            CatsCoTheme(dark = dark) {
                if (state.restoringSession) {
                    CatsCoSessionSplash()
                } else if (state.session == null || state.preview) {
                    CatsCoApp(state, model)
                } else key(state.session?.server, state.session?.userId, state.localDataEpoch) {
                    val quotaNotice = if (state.page == inbox.quotaPage) inbox.items.firstOrNull {
                        it.kind == WorkspaceNotificationKind.Quota &&
                            (state.page == "home" || state.page == "chat/${it.topic}")
                    } else null
                    WorkspaceNotificationApp(state, model, inbox.items, inbox.latestKey,
                        model::readAllNotifications, model::openNotification, model::resolveNotificationReply, quotaNotice,
                        onRead = model::readNotification, arrivalElapsed = inbox.arrivalElapsed,
                        openCenterRequest = notificationCenterRequest, onCenterOpened = { notificationCenterRequest = 0 })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inboundHandled = false
        inboundIntent = intent
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("inboundHandled", inboundHandled)
        super.onSaveInstanceState(outState)
    }
}

internal fun conversationTopic(uri: android.net.Uri?): String? {
    if (uri?.scheme != "catsco" || uri.host != "conversation" || uri.pathSegments.size != 1) return null
    return uri.lastPathSegment?.takeIf { it.matches(Regex("(?:grp_[0-9]+|p2p_[0-9]+_[0-9]+)")) }
}
