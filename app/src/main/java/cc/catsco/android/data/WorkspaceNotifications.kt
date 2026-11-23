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
    val since: Long = 0,
    val cursor: OutcomeCursor = OutcomeCursor(0, 0),
    val arrivalElapsed: Long = 0,
)

internal data class OutcomeCursor(val micros: Long, val id: Long) : Comparable<OutcomeCursor> {
    override fun compareTo(other: OutcomeCursor) = compareValuesBy(this, other, OutcomeCursor::micros, OutcomeCursor::id)
}

internal data class OutcomePage(val statuses: List<ConversationRunStatus>, val next: OutcomeCursor, val hasMore: Boolean)

internal suspend fun HttpWorkspaceRepository.taskOutcomes(session: Session, after: OutcomeCursor): OutcomePage {
    val value = api(session.server, "/api/conversations/task-outcomes", session.token,
        query = mapOf("after_us" to after.micros.toString(), "after_id" to after.id.toString()))
    if (!value.has("outcomes")) throw ApiFailure(501)
    val statuses = value.getJSONArray("outcomes").objects().mapNotNull { conversationRunStatus(it) }
    val next = OutcomeCursor(value.getLong("next_us"), value.getLong("next_id"))
    val more = value.getBoolean("has_more")
    if (more && next <= after) throw org.json.JSONException("Invalid outcome cursor")
    return OutcomePage(statuses, next, more)
}

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
    if (conversation.notificationsMuted) return null
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
    val confirmed = mutableListOf<cc.catsco.core.ChatMessage>()
    val untagged = mutableListOf<cc.catsco.core.ChatMessage>()
    for (raw in result.objects("messages")) {
        val message = chatMessage(raw)
        if (message.senderId != notice.sourceId || message.kind != "text" || message.sequence <= 0 ||
            message.streamEvent in setOf("delta", "cancel")) continue
        val metadata = raw.optJSONObject("metadata") ?: runCatching { JSONObject(raw.optString("metadata")) }.getOrNull()
        val run = raw.first("run_id", "runId").ifBlank { metadata?.first("run_id", "runId").orEmpty() }
            .ifBlank { raw.first("turn_id", "turnId", "response_id", "responseId") }.ifBlank { message.turnId }
        if (run.isNotBlank()) {
            if (run == notice.runId) confirmed += message
            continue
        }
        val receivedAt = notificationTimestamp(raw.optString("created_at"))
        if (notice.startedAt > 0 && receivedAt in notice.startedAt..notice.finishedAt) untagged += message
    }
    return confirmed.ifEmpty { untagged }.asSequence()
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

internal fun AccountStorage.loadNotificationInbox(session: Session, now: Long, includeBackground: Boolean = true): WorkspaceNotificationInbox {
    val raw = read(session, "notifications")
    val saved = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
    val records = saved?.optJSONArray("items") ?: raw?.let { runCatching { JSONArray(it) }.getOrNull() }
    val items = decodeNotices(records)
    val since = saved?.optLong("since")?.takeIf { it > 0 } ?: items.maxOfOrNull { it.finishedAt } ?: now
    val cursor = OutcomeCursor(saved?.optLong("cursor_us")?.takeIf { it > 0 } ?: since * 1000, saved?.optLong("cursor_id") ?: 0)
    val visible = if (includeBackground) recentNotices(items + backgroundNotices(session)) else items
    return WorkspaceNotificationInbox(visible, since = since, cursor = cursor).also {
        if (saved == null) saveNotificationInbox(session, it)
    }
}

internal fun recentNotices(items: List<WorkspaceNotification>) = items.distinctBy { it.key }
    .sortedWith(compareByDescending<WorkspaceNotification> { it.finishedAt }.thenBy { it.key }).take(100)

internal fun AccountStorage.backgroundNotices(session: Session): List<WorkspaceNotification> =
    decodeNotices(read(session, "background-task-notices")?.let { runCatching { JSONArray(it) }.getOrNull() })

internal fun AccountStorage.saveBackgroundNotices(session: Session, items: List<WorkspaceNotification>) =
    write(session, "background-task-notices", encodeNotices(recentNotices(items + backgroundNotices(session))).toString())

private fun decodeNotices(records: JSONArray?) = records?.objects().orEmpty().mapNotNull { record ->
    val kind = WorkspaceNotificationKind.entries.find { it.name == record.optString("kind") } ?: return@mapNotNull null
    val time = record.optLong("finishedAt")
    WorkspaceNotification(
        record.optString("topic"), kind, record.optString("title"), record.optString("detail"),
        notificationTime(time), "", record.optBoolean("unread"), record.optString("key"),
        record.optString("sourceId"), time, record.optLong("startedAt"), record.optString("runId"),
    )
}

internal fun AccountStorage.saveNotificationInbox(session: Session, inbox: WorkspaceNotificationInbox) =
    write(session, "notifications", JSONObject().put("since", inbox.since)
        .put("cursor_us", inbox.cursor.micros).put("cursor_id", inbox.cursor.id).put("items", encodeNotices(inbox.items)).toString())

private fun encodeNotices(items: List<WorkspaceNotification>) = JSONArray(items.map {
    JSONObject().put("topic", it.topic).put("kind", it.kind.name).put("title", it.title).put("detail", it.detail)
        .put("unread", it.unread).put("key", it.key).put("sourceId", it.sourceId).put("finishedAt", it.finishedAt)
        .put("startedAt", it.startedAt).put("runId", it.runId)
})
