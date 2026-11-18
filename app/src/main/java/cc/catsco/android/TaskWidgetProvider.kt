package cc.catsco.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import cc.catsco.core.Conversation

class TaskWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snapshot = context.getSharedPreferences("task_widget", Context.MODE_PRIVATE)
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.task_widget)
            views.setTextViewText(R.id.widget_tasks, snapshot.getString("tasks", null) ?: "打开 CatsCo 同步任务")
            val syncedAt = snapshot.getLong("synced_at", 0)
            views.setTextViewText(R.id.widget_sync, if (syncedAt > 0) "更新于 " + java.time.format.DateTimeFormatter
                .ofPattern("M月d日 HH:mm").withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(syncedAt)) else "打开 CatsCo 更新")
            val topic = snapshot.getString("topic", "").orEmpty()
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("catsco://conversation/$topic")
            }
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun update(context: Context, conversations: List<Conversation>, force: Boolean = false, syncedAt: Long = 0) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java))
            if (ids.isEmpty() && !force) return
            val recent = conversations.sortedByDescending { it.active }.take(3)
            context.getSharedPreferences("task_widget", Context.MODE_PRIVATE).edit()
                .putString("tasks", recent.joinToString("\n\n") { (if (it.active) "进行中 · " else "") + it.name }.ifBlank { "还没有任务，打开 CatsCo 新建" })
                .putString("topic", recent.firstOrNull()?.id.orEmpty()).putLong("synced_at", syncedAt).apply()
            TaskWidgetProvider().onUpdate(context, manager, ids)
        }
        fun clear(context: Context) {
            context.getSharedPreferences("task_widget", Context.MODE_PRIVATE).edit().clear().apply()
            val manager = AppWidgetManager.getInstance(context)
            TaskWidgetProvider().onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java)))
        }
    }
}
