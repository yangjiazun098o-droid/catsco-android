package cc.catsco.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.view.View
import cc.catsco.core.Conversation

class TaskWidgetProvider : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snapshot = context.getSharedPreferences("task_widget", Context.MODE_PRIVATE)
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.task_widget)
            fun openTask(topic: String): PendingIntent {
                val intent = Intent(context, MainActivity::class.java).apply {
                    if (topic.isNotBlank()) {
                        action = Intent.ACTION_VIEW
                        data = Uri.Builder().scheme("catsco").authority("conversation").appendPath(topic).build()
                    }
                }
                return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            views.setOnClickPendingIntent(R.id.widget_root, openTask(""))
            val legacyFirstTitle = snapshot.getString("tasks", null)?.substringBefore("\n\n")
            val minHeight = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180)
            val rowCount = ((minHeight - 76) / 30).coerceIn(1, rowIds.size)
            rowIds.forEachIndexed { index, row ->
                val topic = snapshot.getString("topic_$index", if (index == 0) snapshot.getString("topic", "") else "").orEmpty()
                val title = snapshot.getString("title_$index", if (index == 0) legacyFirstTitle else null)
                views.setViewVisibility(row, if (index == 0 || (index < rowCount && topic.isNotBlank())) View.VISIBLE else View.GONE)
                views.setTextViewText(row, title ?: "打开 CatsCo 查看任务")
                views.setOnClickPendingIntent(row, openTask(topic))
            }
            val syncedAt = snapshot.getLong("synced_at", 0)
            views.setTextViewText(R.id.widget_sync, if (syncedAt > 0) "更新于 " + java.time.format.DateTimeFormatter
                .ofPattern("M月d日 HH:mm").withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(syncedAt)) else "打开 CatsCo 更新")
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        private val rowIds = intArrayOf(R.id.widget_tasks, R.id.widget_task_second, R.id.widget_task_third)
        fun update(context: Context, conversations: List<Conversation>, force: Boolean = false, syncedAt: Long = 0) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java))
            // Keep the snapshot current even if the launcher adds its first widget later.
            val recent = conversations.sortedByDescending { it.active }.take(3)
            val edit = context.getSharedPreferences("task_widget", Context.MODE_PRIVATE).edit()
                .remove("tasks").remove("topic").putLong("synced_at", syncedAt)
            rowIds.indices.forEach { index ->
                val task = recent.getOrNull(index)
                edit.putString("topic_$index", task?.id)
                edit.putString("title_$index", task?.let { (if (it.active) "进行中 · " else "") + it.name })
            }
            edit.apply()
            TaskWidgetProvider().onUpdate(context, manager, ids)
        }
        fun clear(context: Context) {
            context.getSharedPreferences("task_widget", Context.MODE_PRIVATE).edit().clear().apply()
            val manager = AppWidgetManager.getInstance(context)
            TaskWidgetProvider().onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java)))
        }
    }
}
