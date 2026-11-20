package cc.catsco.android.data

import cc.catsco.android.ui.WorkspaceNotification
import cc.catsco.android.ui.WorkspaceNotificationKind
import cc.catsco.core.Conversation
import cc.catsco.core.ConversationRunStatus
import cc.catsco.core.Session
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class WorkspaceNotificationInbox(
    val items: List<WorkspaceNotification> = emptyList(),
    val latestKey: String? = null,
    val quotaPage: String? = null,
)

internal fun conversationRunStatus(value: JSONObject, fallbackTopic: String = ""): ConversationRunStatus? {
    val topic = value.first("topic_id").ifBlank { fallbackTopic }
    if (!topic.matches(Regex("(?:grp_[0-9]+|p2p_[0-9]+_[0-9]+)"))) return null
    val runId = value.first("run_id")
    val time = notificationTimestamp(value.optString("updated_at"))
    if (runId.isBlank() || time == 0L) return null
    return ConversationRunStatus(topic, runId, value.first("state"), value.first("summary"), value.first("error"),
        value.first("source_uid"), time)
}

private fun notificationTimestamp(value: String): Long =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

internal fun notificationTime(value: Long): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

private val quotaErrorPattern = Regex(
    """\b(quota_exhausted|insufficient_quota|insufficient[-_ ]credit|insufficient[-_ ]quota|quota[-_ ]exceeded)\b|额度不足|额度已用完|额度已耗尽""",
    RegexOption.IGNORE_CASE,
)

internal fun runNotification(status: ConversationRunStatus, conversation: Conversation, startedAt: Long): WorkspaceNotification? {
    val kind = when (status.state) {
        "completed" -> WorkspaceNotificationKind.Completed
        "failed" -> if (quotaErrorPattern.containsMatchIn(status.error)) WorkspaceNotificationKind.Quota else WorkspaceNotificationKind.Failed
        else -> return null
    }
    val detail = if (kind == WorkspaceNotificationKind.Completed) status.summary else status.error.ifBlank { status.summary }
    val fallback = when (kind) {
        WorkspaceNotificationKind.Completed -> "可打开任务查看完整回复。"
        WorkspaceNotificationKind.Failed -> "可打开任务查看当前记录。"
        WorkspaceNotificationKind.Quota -> "请查看模型额度和套餐权益。"
    }
    return WorkspaceNotification(
        topic = status.topic, kind = kind,
        title = if (kind == WorkspaceNotificationKind.Quota) "模型额度不足" else conversation.name,
        detail = detail.ifBlank { fallback }.take(540), time = notificationTime(status.updatedAt), reply = "",
        key = "run:${status.topic}:${status.sourceId}:${status.runId}", sourceId = status.sourceId.ifBlank { conversation.agentId },
        startedAt = startedAt, finishedAt = status.updatedAt, runId = status.runId,
    )
}

internal fun replyForNotification(notice: WorkspaceNotification, result: JSONObject): String? {
    if (notice.sourceId.isBlank()) return null
    return result.objects("messages").asSequence()
        .filter { item ->
            val receivedAt = notificationTimestamp(item.optString("created_at"))
            val message = chatMessage(item)
            message.senderId == notice.sourceId && message.kind == "text" && message.sequence > 0 &&
                message.streamEvent !in setOf("delta", "cancel") &&
                (message.turnId == notice.runId ||
                    notice.startedAt > 0 && receivedAt in notice.startedAt..notice.finishedAt)
        }
        .map(::chatMessage)
        .sortedBy { it.sequence }
        .map { message ->
            if (message.blocks.isEmpty()) message.text else message.blocks
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .filter { it.optString("type") == "text" }
                .joinToString("\n\n") { it.first("text", "content") }
        }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .take(8_000).ifBlank { null }
}

internal fun AccountStorage.loadNotifications(session: Session): List<WorkspaceNotification> {
    val raw = read(session, "notifications") ?: return emptyList()
    return runCatching { JSONArray(raw).objects().mapNotNull { record ->
        val kind = WorkspaceNotificationKind.entries.find { it.name == record.optString("kind") } ?: return@mapNotNull null
        val time = record.optLong("finishedAt")
        WorkspaceNotification(
            record.optString("topic"), kind, record.optString("title"), record.optString("detail"),
            notificationTime(time), "", record.optBoolean("unread"), record.optString("key"),
            record.optString("sourceId"), time, record.optLong("startedAt"), record.optString("runId"),
        )
    } }.getOrDefault(emptyList())
}

internal fun AccountStorage.saveNotifications(session: Session, items: List<WorkspaceNotification>) =
    write(session, "notifications", JSONArray(items.map {
        JSONObject().put("topic", it.topic).put("kind", it.kind.name).put("title", it.title).put("detail", it.detail)
            .put("unread", it.unread).put("key", it.key).put("sourceId", it.sourceId).put("finishedAt", it.finishedAt)
            .put("startedAt", it.startedAt).put("runId", it.runId)
    }).toString())
