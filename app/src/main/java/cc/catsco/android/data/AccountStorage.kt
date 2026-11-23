package cc.catsco.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cc.catsco.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AccountStorage(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_workspace", Context.MODE_PRIVATE)
    private fun prefix(session: Session): String = MessageDigest.getInstance("SHA-256")
        .digest("${session.server.value}\n${session.userId}".toByteArray())
        .joinToString("") { "%02x".format(it) } + ":"

    private companion object {
        // The activity and scheduled checker share a single key even on first use.
        val key: SecretKey by lazy {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("catsco.workspace.v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("catsco.workspace.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }

    fun write(session: Session, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val bytes = cipher.doFinal(value.toByteArray())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        check(prefs.edit().putString(prefix(session) + name, encoded).commit()) { "无法保存本机草稿。" }
    }

    fun read(session: Session, name: String): String? {
        val raw = prefs.getString(prefix(session) + name, null) ?: return null
        return runCatching {
            val parts = raw.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)))
        }.getOrNull()
    }

    fun draft(session: Session, topic: String): ComposerDraft {
        val value = read(session, "draft:$topic")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ComposerDraft()
        val quote = value.optJSONObject("quote")?.takeIf { it.optLong("message_id") > 0 }?.let {
            MessageQuote(it.optLong("message_id"), it.optString("sender_name"), it.optString("text"))
        }
        return ComposerDraft(value.optString("text"), value.objects("files").map(::attachment), value.optString("client_id"), value.optString("fingerprint"), value.optString("pending_agent"), quote,
            taskTitle = value.optString("task_title"), taskCreationId = value.optString("task_creation_id"), followup = value.optBoolean("task_followup"))
    }

    fun saveDraft(session: Session, topic: String, draft: ComposerDraft) = write(session, "draft:$topic", JSONObject()
        .put("text", draft.text).put("files", JSONArray(draft.attachments.map { JSONObject(it.raw) }))
        .put("client_id", draft.clientId).put("fingerprint", draft.fingerprint).put("pending_agent", draft.pendingTaskAgent)
        .put("task_title", draft.taskTitle).put("task_creation_id", draft.taskCreationId).put("task_followup", draft.followup)
        .put("quote", draft.quote?.let { JSONObject().put("message_id", it.messageId).put("sender_name", it.senderName).put("text", it.text) }).toString())

    fun workspace(session: Session): Workspace = cachedWorkspace(session) ?: Workspace()

    fun lastTaskEmployeeId(session: Session): String = read(session, "new-task:last-employee").orEmpty()

    fun saveLastTaskEmployeeId(session: Session, id: String) = write(session, "new-task:last-employee", id)

    fun cachedWorkspace(session: Session): Workspace? {
        val value = read(session, "workspace")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return Workspace(
            value.objects("employees").map {
                Employee(it.optString("id"), it.optString("name"), false, it.optBoolean("owner"), it.optString("description"),
                    if (it.has("cloud") && !it.isNull("cloud")) it.optBoolean("cloud") else null,
                    it.optLong("createdAt"), it.optLong("addedAt"), it.optString("avatarUrl"), it.optString("username"))
            }.prioritizedForTasks(),
            value.objects("conversations").map { Conversation(it.optString("id"), it.optString("name"), it.optString("preview"), false, it.optString("date"), it.optString("agentId"), it.optBoolean("task"), it.optString("projectId"), notificationsMuted = it.optBoolean("notifications_muted")) },
        )
    }

    fun saveWorkspace(session: Session, workspace: Workspace) = write(session, "workspace", JSONObject()
        .put("employees", JSONArray(workspace.employees.map {
            JSONObject().put("id", it.id).put("name", it.name).put("owner", it.owner).put("description", it.description)
                .put("cloud", it.cloud ?: JSONObject.NULL).put("createdAt", it.createdAt).put("addedAt", it.addedAt)
                .put("avatarUrl", it.avatarUrl).put("username", it.username)
        }))
        .put("conversations", JSONArray(workspace.conversations.map {
            JSONObject().put("id", it.id).put("name", it.name).put("preview", it.preview).put("date", it.date)
                .put("agentId", it.agentId).put("task", it.task).put("projectId", it.projectId)
                .put("notifications_muted", it.notificationsMuted)
        })).toString())

    fun clear(session: Session) {
        val prefix = prefix(session)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        check(editor.commit()) { "无法清除本机资料。" }
    }

    internal fun clearOfflineData(session: Session, includeDrafts: Boolean) {
        val prefix = prefix(session)
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (!key.startsWith(prefix)) continue
            val name = key.removePrefix(prefix)
            if (name == "workspace" || name.startsWith("messages:") ||
                (includeDrafts && (name.startsWith("draft:") || name.startsWith("module-draft:")))) {
                editor.remove(key)
            }
        }
        check(editor.commit()) { "无法清除本机缓存。" }
    }

    internal fun removeHistory(session: Session, topic: String) {
        check(prefs.edit().remove(prefix(session) + "messages:$topic").commit()) { "无法清除本机会话缓存。" }
    }
}

fun attachment(json: JSONObject): Attachment = Attachment(
    json.first("name", "title", "filename").ifBlank { "附件" }, json.first("url", "download_url", "file_url"),
    json.first("mime_type", "mime").ifBlank { "application/octet-stream" }, json.toString(),
)

fun chatMessage(raw: JSONObject): ChatMessage {
    val metadata = raw.optJSONObject("metadata") ?: runCatching { JSONObject(raw.optString("metadata")) }.getOrDefault(JSONObject())
    val content = raw.opt("content")
    val decoded = when (content) {
        is JSONObject -> content
        is String -> runCatching { JSONObject(content) }.getOrNull()
        else -> null
    }
    fun decodedBlocks(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> value.objects()
        is String -> runCatching { JSONArray(value).objects() }.getOrDefault(emptyList())
        else -> emptyList()
    }
    val blocks = decodedBlocks(raw.opt("content_blocks")).ifEmpty {
        decodedBlocks(decoded?.opt("content_blocks")).ifEmpty {
            decodedBlocks(decoded?.opt("contentBlocks")).ifEmpty { decoded?.objects("blocks").orEmpty() }
        }
    }
    val files = blocks.filter { it.optString("type") in setOf("image", "file", "audio", "video") }
        .map { attachment(it.optJSONObject("payload") ?: it) }.filter { it.url.isNotBlank() }.toMutableList()
    val workingTypes = setOf("thinking", "tool_use", "tool_call", "tool_result", "assistant_text", "reasoning", "runtime_plan")
    val explicitKind = raw.first("type", "msg_type").ifBlank { decoded?.optString("type").orEmpty().ifBlank { "text" } }
    val planPayload = decoded?.optJSONObject("payload") ?: raw.optJSONObject("payload")
    val hasPlan = listOf(decoded?.opt("steps"), decoded?.opt("plan"), planPayload?.opt("steps"), planPayload?.opt("plan"), decoded?.optJSONObject("plan")?.opt("steps"))
        .any { (decodedJson(it) as? JSONArray)?.length()?.let { count -> count > 0 } == true }
    val kind = if (hasPlan) "runtime_plan" else if (blocks.isNotEmpty() && blocks.none { it.optString("type") == "text" }) {
        blocks.firstOrNull { it.optString("type") in workingTypes }?.optString("type") ?: explicitKind
    } else explicitKind
    if (kind in setOf("image", "file", "audio", "video")) {
        val file = attachment(decoded?.optJSONObject("payload") ?: raw.optJSONObject("payload") ?: decoded ?: raw)
        if (file.url.isNotBlank()) files.add(file)
    }
    val text = when {
        blocks.isNotEmpty() -> blocks.filter { it.optString("type") == "text" || it.optString("type") in workingTypes }
            .joinToString("\n\n") { block ->
                when (block.optString("type")) {
                    "thinking", "reasoning" -> block.first("thinking", "text", "content")
                    "tool_use", "tool_call" -> block.first("name", "text", "content")
                    else -> block.first("text", "content", "summary")
                }
            }
        decoded != null -> decoded.first("text", "content", "summary", "title").ifBlank { decoded.optJSONObject("payload")?.first("text", "summary").orEmpty() }
        content is String -> content
        else -> ""
    }
    return ChatMessage(
        listOf(raw.optLong("seq_id"), raw.optLong("id"), raw.optLong("seq")).firstOrNull { it > 0 } ?: 0,
        raw.first("from_uid", "from").removePrefix("usr"), raw.first("from_name", "sender_name"), text,
        kind, metadata.first("stream_id").ifBlank { raw.optString("_stream_id") }, metadata.optString("stream_event"),
        raw.optString("client_msg_id"), files, raw.toString(),
        turnId = metadata.first("turn_id", "turnId", "response_id", "responseId", "run_id", "runId", "stream_id"),
        revision = maxOf(decoded?.optLong("revision") ?: 0, planPayload?.optLong("revision") ?: 0),
        blocks = blocks.map(JSONObject::toString),
        afterSequence = raw.optLong("_after_sequence"),
    )
}
