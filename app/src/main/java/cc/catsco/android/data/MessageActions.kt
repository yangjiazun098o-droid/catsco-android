package cc.catsco.android.data

import cc.catsco.core.ChatMessage
import cc.catsco.core.ComposerDraft
import cc.catsco.core.MessageQuote
import org.json.JSONArray
import org.json.JSONObject

/** Resolve against the selected reply, so older replies never resend the latest task by mistake. */
fun regenerationDraft(messages: List<ChatMessage>, turn: MessageTurn, userId: String): ComposerDraft? {
    if (!turn.assistant || turn.streaming || turn.messageId <= 0) return null
    val replyIndex = messages.indexOfFirst { it.sequence == turn.messageId && it.senderId == turn.senderId }
    if (replyIndex < 0) return null
    val task = messages.take(replyIndex).lastOrNull {
        it.senderId == userId && (it.text.isNotBlank() || it.attachments.isNotEmpty())
    } ?: return null
    val quotedId = runCatching { JSONObject(task.raw).optLong("reply_to") }.getOrDefault(0)
    val quoted = messages.find { it.sequence == quotedId }
    val quote = if (quotedId > 0) MessageQuote(quotedId, quoted?.senderName.orEmpty(), quoted?.text.orEmpty()) else null
    return ComposerDraft(text = task.text, attachments = task.attachments, quote = quote)
}

internal fun messageSendBody(topic: String, draft: ComposerDraft, clientId: String): JSONObject {
    val body = JSONObject().put("topic_id", topic).put("type", "text")
        .put("content", draft.text).put("client_msg_id", clientId)
    draft.quote?.takeIf { it.messageId > 0 }?.let { body.put("reply_to", it.messageId) }
    if (draft.attachments.isNotEmpty()) {
        val blocks = JSONArray()
        if (draft.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", draft.text))
        draft.attachments.forEach { file ->
            blocks.put(JSONObject().put("type", if (file.mime.startsWith("image/")) "image" else "file").put("payload", JSONObject(file.raw)))
        }
        body.put("content_blocks", blocks)
        if (draft.text.isBlank()) body.put("content", draft.attachments.joinToString(", ") { it.name })
    }
    return body
}
