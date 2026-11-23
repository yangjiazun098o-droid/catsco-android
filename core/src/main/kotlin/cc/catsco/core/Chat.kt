package cc.catsco.core

private const val LATEST_HISTORY_LIMIT = 50
private const val HISTORY_BACKFILL_LIMIT = 200

data class Attachment(val name: String, val url: String, val mime: String, val raw: String)

data class ChatMessage(
    val sequence: Long,
    val senderId: String,
    val senderName: String,
    val text: String,
    val kind: String = "text",
    val streamId: String = "",
    val streamEvent: String = "",
    val clientId: String = "",
    val attachments: List<Attachment> = emptyList(),
    val raw: String = "",
    val streaming: Boolean = false,
    val turnId: String = "",
    val revision: Long = 0,
    val blocks: List<String> = emptyList(),
    val afterSequence: Long = 0,
) {
    val key: String get() = when { kind == "runtime_plan" -> "plan:$senderId"; streaming -> "stream:$streamId"; else -> "message:$sequence" }
}

/** Merges HTTP history and socket echoes without replaying a finished stream. */
class MessageTimeline {
    private val records = mutableMapOf<Long, ChatMessage>()
    private val streams = linkedMapOf<String, ChatMessage>()
    private val finalized = mutableSetOf<String>()
    private val plans = linkedMapOf<String, ChatMessage>()
    val messages: List<ChatMessage> get() = records.values.sortedBy { it.sequence } + streams.values + plans.values
    var historySyncedThrough: Long = 0
        private set

    fun markHistorySyncedThrough(sequence: Long) {
        historySyncedThrough = maxOf(historySyncedThrough, sequence)
    }

    fun receive(message: ChatMessage) {
        if (message.kind == "runtime_plan") {
            val anchor = message.afterSequence.takeIf { it > 0 } ?: message.sequence.takeIf { it > 0 } ?: (records.keys.maxOrNull() ?: 0)
            val anchored = message.copy(afterSequence = anchor)
            val prior = plans[message.senderId]
            if (prior != null && prior.afterSequence > anchored.afterSequence) return
            if (prior != null && prior.turnId == message.turnId && prior.revision > message.revision) return
            plans[message.senderId] = anchored
            return
        }
        val stream = message.streamId
        if (message.kind == "stream_cancel" || message.streamEvent == "cancel") {
            if (stream.isNotEmpty()) { streams.remove(stream); finalized.add(stream) }
            return
        }
        if (message.kind == "stream_delta" || message.streamEvent == "delta") {
            if (stream.isBlank() || stream in finalized) return
            val prior = streams[stream]
            streams[stream] = message.copy(
                text = prior?.text.orEmpty() + message.text,
                streaming = true,
            )
            return
        }
        if (message.sequence <= 0) return
        if (stream.isNotEmpty()) { finalized.add(stream); streams.remove(stream) }
        records[message.sequence] = message.copy(streaming = false)
    }

    fun merge(history: List<ChatMessage>) = history.sortedBy { it.sequence }.forEach(::receive)
}

data class MessageHistoryPage(val messages: List<ChatMessage>, val beforeId: Long, val hasMore: Boolean)

/** Reconnect the latest page to the last HTTP snapshot, even when live messages have arrived ahead of it. */
suspend fun loadLatestHistory(
    syncedThrough: Long,
    fetchPage: suspend (beforeId: Long, limit: Int) -> MessageHistoryPage,
): MessageHistoryPage {
    val latest = fetchPage(0, LATEST_HISTORY_LIMIT)
    if (syncedThrough <= 0 || !latest.hasMore || latest.beforeId <= syncedThrough) return latest

    val messages = latest.messages.toMutableList()
    var page = latest
    while (page.hasMore && page.beforeId > syncedThrough) {
        val older = fetchPage(page.beforeId, HISTORY_BACKFILL_LIMIT)
        check(!older.hasMore || older.beforeId in 1 until page.beforeId) { "消息同步暂未完成，请重试。" }
        messages.addAll(older.messages)
        page = older
    }
    return page.copy(messages = messages)
}

data class MessageQuote(val messageId: Long, val senderName: String, val text: String)

data class ComposerDraft(
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val clientId: String = "",
    val fingerprint: String = "",
    val pendingTaskAgent: String = "",
    val quote: MessageQuote? = null,
    val taskTitle: String = "",
    val taskCreationId: String = "",
    val followup: Boolean = false,
)
