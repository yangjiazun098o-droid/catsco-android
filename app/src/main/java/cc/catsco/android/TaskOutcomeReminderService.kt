package cc.catsco.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import cc.catsco.android.data.*
import cc.catsco.android.ui.WorkspaceNotification
import cc.catsco.core.Conversation
import cc.catsco.core.Session
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal const val openTaskNotices = "cc.catsco.android.OPEN_TASK_NOTICES"
private const val jobId = 23011
private const val channelId = "task-outcomes"

internal object TaskOutcomeReminders {
    fun schedule(context: Context, session: Session, after: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(channelId, "任务更新", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val extras = PersistableBundle().apply {
            putString("server", session.server.value)
            putString("account", session.userId)
            putLong("after", after)
        }
        val job = JobInfo.Builder(jobId, ComponentName(context, TaskOutcomeReminderService::class.java))
            .setPeriodic(15 * 60_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras).build()
        context.getSystemService(JobScheduler::class.java)?.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(jobId)
        context.getSystemService(NotificationManager::class.java)?.cancel(channelId, jobId)
    }
}

class TaskOutcomeReminderService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Job? = null
    private val repository by lazy { HttpWorkspaceRepository() }

    override fun onStartJob(params: JobParameters): Boolean {
        current = scope.launch {
            val retry = try {
                withContext(Dispatchers.IO) { checkOutcomes(params.extras) }
                false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ApiFailure) { error.status >= 500 }
            catch (_: Exception) { true }
            jobFinished(params, retry)
        }
        return true
    }

    private suspend fun checkOutcomes(extras: PersistableBundle) {
        val vault = KeystoreSessionStore(this)
        val session = vault.read() ?: return
        if (session.userId != extras.getString("account") || session.server.value != extras.getString("server")) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val storage = AccountStorage(this)
        // The scheduled worker has a separate delivery receipt. The foreground
        // inbox keeps its own read flags and resumes from its own checkpoint.
        val inbox = storage.loadNotificationInbox(session, System.currentTimeMillis(), includeBackground = false)
        val receipt = storage.read(session, "system-task-notices")?.let(::JSONObject) ?: JSONObject()
        val after = maxOf(extras.getLong("after"), inbox.since * 1000)
        var cursor = maxOf(OutcomeCursor(after, 0), OutcomeCursor(receipt.optLong("micros"), receipt.optLong("id")))
        val previous = receipt.optJSONArray("keys")
        val delivered = (0 until (previous?.length() ?: 0)).map { previous!!.getString(it) }.toMutableSet()
        val inApp = inbox.items.mapTo(mutableSetOf()) { it.key }
        val notices = linkedMapOf<String, WorkspaceNotification>()
        try {
            val conversations = storage.workspace(session).conversations.associateBy { it.id }
            do {
                val page = repository.taskOutcomes(session, cursor)
                for (status in page.statuses) {
                    // The authenticated ledger has already applied the current mute preference.
                    val task = Conversation(status.topic, conversations[status.topic]?.name ?: "任务")
                    val item = runNotification(status, task, 0) ?: continue
                    if (item.key !in delivered && item.key !in inApp) notices[item.key] = item
                }
                cursor = page.next
            } while (page.hasMore && notices.size < 100)
        } catch (error: ApiFailure) {
            if (error.status !in setOf(404, 405, 501)) throw error
            for (conversation in repository.conversations(session)) {
                val status = conversation.runStatus ?: continue
                if (status.updatedAt * 1000 < cursor.micros) continue
                val item = runNotification(status, conversation, 0) ?: continue
                if (item.key !in delivered && item.key !in inApp) notices[item.key] = item
            }
        }
        currentCoroutineContext().ensureActive()
        if (notices.isEmpty() || vault.read()?.let { it.userId == session.userId && it.server == session.server && it.token == session.token } != true) return
        storage.saveBackgroundNotices(session, notices.values.toList())
        val intent = Intent(this, MainActivity::class.java).setAction(openTaskNotices)
            .putExtra("server", session.server.value).putExtra("account", session.userId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, jobId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channelId).setSmallIcon(R.drawable.e_notifications)
            .setContentTitle("任务有新动态").setContentText("打开 CatsCo 查看任务通知")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
        currentCoroutineContext().ensureActive()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.notify(channelId, jobId, notification)
        delivered.addAll(notices.keys)
        storage.write(session, "system-task-notices", JSONObject().put("micros", cursor.micros).put("id", cursor.id)
            .put("keys", JSONArray(delivered.toList().takeLast(200))).toString())
    }

    override fun onStopJob(params: JobParameters): Boolean {
        current?.cancel()
        return true
    }
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
