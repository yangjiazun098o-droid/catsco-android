package cc.catsco.core

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

data class MessageQuote(val messageId: Long, val senderName: String, val text: String)

data class ComposerDraft(
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val clientId: String = "",
    val fingerprint: String = "",
    val pendingTaskAgent: String = "",
    val quote: MessageQuote? = null,
    val taskTitle: String = "",
)
