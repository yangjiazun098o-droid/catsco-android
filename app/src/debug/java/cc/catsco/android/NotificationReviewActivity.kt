package cc.catsco.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.WorkspaceNotification
import cc.catsco.android.ui.WorkspaceNotificationApp
import cc.catsco.android.ui.WorkspaceNotificationKind
import cc.catsco.core.ChatMessage
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.File

private const val reviewAction = "cc.catsco.android.debug.PREVIEW_NOTIFICATION"
private const val completedTask = "grp_17"
private const val failedTask = "grp_18"
private const val completedReply = "**发布资料已整理完成。**\n\n- 发布清单\n- 对外公告文案\n- 常见问题与回复口径"
private const val failedReply = "**测试运行超时。**\n\n已保留目前的测试记录。恢复连接后，可以在原任务中继续检查。"

/** Native, debug-only preview with its own local session and preferences. */
class NotificationReviewActivity : ComponentActivity() {
    private val events = Channel<WorkspaceNotificationKind>(Channel.UNLIMITED)
    private val sampleApplication by lazy { NotificationReviewApplication(applicationContext) }
    private val model by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(sampleApplication))[WorkspaceViewModel::class.java]
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val kind = when (intent.getStringExtra("event")) {
                "completed" -> WorkspaceNotificationKind.Completed
                "failed" -> WorkspaceNotificationKind.Failed
                "quota" -> WorkspaceNotificationKind.Quota
                else -> return
            }
            events.trySend(kind)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!model.state.value.preview) {
            model.foreground(false)
            model.enterPreview()
        }
        val isolatedContext = object : ContextWrapper(this) {
            override fun getApplicationContext(): Context = sampleApplication
            override fun getSharedPreferences(name: String, mode: Int) = sampleApplication.getSharedPreferences(name, mode)
        }
        setContent {
            CompositionLocalProvider(LocalContext provides isolatedContext) {
                CatsCoTheme(isSystemInDarkTheme()) { NotificationReview(model, events) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(reviewAction), ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onDestroy() {
        events.close()
        super.onDestroy()
    }
}

private class NotificationReviewApplication(context: Context) : Application() {
    init { attachBaseContext(context) }

    override fun getSharedPreferences(name: String, mode: Int) =
        super.getSharedPreferences("notification_review_$name", mode)

    override fun getNoBackupFilesDir(): File =
        File(super.getNoBackupFilesDir(), "notification-review").apply { mkdirs() }
}

@Composable
private fun NotificationReview(model: WorkspaceViewModel, events: Channel<WorkspaceNotificationKind>) {
    val state by model.state.collectAsState()
    var notices by remember {
        mutableStateOf(listOf(sampleNotice(WorkspaceNotificationKind.Completed, "昨天").copy(unread = false)))
    }
    var lastArrival by remember { mutableIntStateOf(0) }
    LaunchedEffect(events) {
        for (kind in events) {
            lastArrival++
            notices = listOf(sampleNotice(kind, "刚刚").copy(key = "sample:$lastArrival")) + notices
        }
    }

    val pageMessages = remember(state.page) { sampleMessages(state.page) }
    val creditPage = remember {
        ResourceState(
            loaded = true,
            value = JSONObject("""{"summary":{"plan_name":"共享模型服务","credit_balance":"0"},"model_usage":{"summary":{"remaining_percent":0,"quota_configured":true,"status":"over_limit","provider":"shared"}}}"""),
        )
    }
    val display = state.copy(
        messages = pageMessages,
        resource = if (state.page == "commercial") creditPage else state.resource,
        openUrl = null,
        workspace = state.workspace.copy(conversations = state.workspace.conversations.map {
            if (it.id == failedTask && notices.any { notice -> notice.topic == failedTask }) it.copy(active = false) else it
        }),
    )
    WorkspaceNotificationApp(
        display, model, notices, lastArrival.takeIf { it > 0 }?.let { "sample:$it" },
        onReadAll = { notices = notices.map { it.copy(unread = false) } },
        onOpen = { selected ->
            notices = notices.map { if (it.key == selected.key) it.copy(unread = false) else it }
            if (selected.kind == WorkspaceNotificationKind.Quota) model.navigate("commercial")
            else model.openConversation(selected.topic, selected.title)
        },
        quotaNotice = notices.firstOrNull { it.kind == WorkspaceNotificationKind.Quota },
    )
}

private fun sampleNotice(kind: WorkspaceNotificationKind, time: String) = when (kind) {
    WorkspaceNotificationKind.Completed -> WorkspaceNotification(
        completedTask, kind, "整理产品发布资料", "已整理发布清单和对外文案。", time, reply = completedReply, key = "sample:history",
    )
    WorkspaceNotificationKind.Failed -> WorkspaceNotification(
        failedTask, kind, "检查测试结果", "测试运行超时，已保留现有记录。", time, reply = failedReply, key = "sample:failed",
    )
    WorkspaceNotificationKind.Quota -> WorkspaceNotification(
        "quota", kind, "当前模型额度已用完", "查看剩余额度和套餐权益。", time,
        reply = "当前模型的可用额度已用完。可以到「套餐与权益」查看额度和可用服务。", key = "sample:quota",
    )
}

private fun sampleMessages(page: String): List<ChatMessage> {
    val (request, response) = when (page) {
        "chat/$completedTask" -> "帮我整理这次产品发布要准备的材料。" to completedReply
        "chat/$failedTask" -> "检查测试结果，把失败项整理出来。" to failedReply
        else -> return emptyList()
    }
    return listOf(
        ChatMessage(1, "101", "本地预览", request),
        ChatMessage(2, "9", "小八", response),
    )
}
