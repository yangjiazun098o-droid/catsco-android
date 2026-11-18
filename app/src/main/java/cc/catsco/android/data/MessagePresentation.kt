package cc.catsco.android.data

import cc.catsco.core.Attachment
import cc.catsco.core.ChatMessage
import cc.catsco.core.MessageQuote
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class MessagePlanStep(val text: String, val status: String)

data class MessageWorkStep(
    val id: String,
    val kind: String,
    val name: String,
    val input: String = "",
    val summary: String = "",
    val result: String? = null,
    val error: Boolean = false,
    val status: String = "",
    val children: List<MessageWorkStep> = emptyList(),
    val description: String = "",
    val failureReason: String = "",
)

data class MessageTurn(
    val key: String,
    val senderId: String,
    val senderName: String,
    val assistant: Boolean,
    val steps: List<MessageWorkStep>,
    val plan: List<MessagePlanStep>,
    val progress: List<String>,
    val text: List<String>,
    val files: List<Attachment>,
    val executionState: String,
    val streaming: Boolean,
    val messageId: Long = 0,
    val quote: MessageQuote? = null,
    val sourceMessageIds: Set<Long> = emptySet(),
) {
    val hasProcess get() = steps.isNotEmpty() || plan.isNotEmpty()
    val hasOutput get() = text.isNotEmpty() || files.isNotEmpty()
}

private val processTypes = setOf("thinking", "reasoning", "assistant_text", "tool_use", "tool_call", "tool_result", "runtime_plan", "process")
private val mediaTypes = setOf("image", "file", "attachment", "audio", "voice", "video")

internal fun decodedJson(value: Any?): Any? = if (value is String) {
    runCatching { JSONObject(value) }.getOrNull() ?: runCatching { JSONArray(value) }.getOrNull() ?: value
} else value

private fun jsonObject(value: Any?): JSONObject = decodedJson(value) as? JSONObject ?: JSONObject()

private fun displayValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    else -> value.toString()
}

private fun messageMetadata(message: ChatMessage) = jsonObject(jsonObject(message.raw).opt("metadata"))

private fun messageBlocks(message: ChatMessage): List<JSONObject> {
    val metadata = messageMetadata(message)
    val raw = jsonObject(message.raw)
    val content = jsonObject(raw.opt("content"))
    val stored = message.blocks.map(::jsonObject)
    val blocks = if (stored.isNotEmpty()) stored else listOf(when {
        message.kind == "runtime_plan" -> JSONObject().put("type", "runtime_plan")
            .put("payload", content.optJSONObject("payload") ?: raw.optJSONObject("payload") ?: content)
        content.first("type").isNotBlank() -> content
        message.kind in setOf("tool_use", "tool_call") -> JSONObject().put("type", "tool_use")
            .put("id", metadata.first("id", "tool_call_id", "tool_use_id"))
            .put("name", message.text).put("input", decodedJson(metadata.opt("input")))
        message.kind == "tool_result" -> JSONObject().put("type", "tool_result")
            .put("tool_use_id", metadata.first("tool_use_id", "tool_call_id", "id"))
            .put("content", message.text).put("is_error", metadata.optBoolean("is_error"))
        message.kind in setOf("thinking", "reasoning") -> JSONObject().put("type", "thinking").put("thinking", message.text)
        message.kind in mediaTypes -> JSONObject().put("type", message.kind).put("payload", content.optJSONObject("payload") ?: raw.optJSONObject("payload") ?: content)
        else -> JSONObject().put("type", if (message.kind in processTypes) "assistant_text" else "text").put("text", message.text)
    })
    return blocks.map { block ->
        val normalized = JSONObject(block.toString())
        if (normalized.isNull("metadata")) normalized.put("metadata", metadata)
        if (normalized.optString("type") == "tool_call") normalized.put("type", "tool_use")
        if (normalized.optString("type") == "text" && (normalized.optString("presentation_role") == "process" || metadata.optString("presentation_role") == "process" || normalized.optString("text").trimStart().startsWith("AI文本:"))) {
            normalized.put("type", "assistant_text").put("text", normalized.optString("text").trimStart().removePrefix("AI文本:").trimStart())
        }
        normalized
    }
}

/** Display projection of the desktop message grouping. Raw history stays intact. */
fun presentMessages(messages: List<ChatMessage>, currentUser: String, agentIds: Set<String>, senderNames: Map<String, String> = emptyMap()): List<MessageTurn> {
    val blocks = messages.associate { it.key to messageBlocks(it) }
    fun assistant(message: ChatMessage): Boolean = message.senderId != currentUser && (
        message.senderId in agentIds || messageMetadata(message).first("role", "sender_type") in setOf("assistant", "agent") ||
            jsonObject(message.raw).optString("role") == "assistant" || blocks[message.key].orEmpty().any { it.optString("type") in processTypes }
        )
    val groups = mutableListOf<MutableList<ChatMessage>>()
    messages.filter { it.kind != "runtime_plan" }.forEach { message ->
        val prior = groups.lastOrNull()
        val previous = prior?.lastOrNull()
        val priorTurn = prior?.asReversed()?.firstOrNull { it.turnId.isNotBlank() }?.turnId.orEmpty()
        val sameTurn = priorTurn.isNotBlank() && priorTurn == message.turnId
        val turnConflict = priorTurn.isNotBlank() && message.turnId.isNotBlank() && priorTurn != message.turnId
        val priorTopic = previous?.let { jsonObject(it.raw).first("topic_id", "topic") }.orEmpty()
        val nextTopic = jsonObject(message.raw).first("topic_id", "topic")
        val topicConflict = priorTopic.isNotBlank() && nextTopic.isNotBlank() && priorTopic != nextTopic
        val hasProcess = prior.orEmpty().any { entry -> blocks[entry.key].orEmpty().any { it.optString("type") in processTypes } } || blocks[message.key].orEmpty().any { it.optString("type") in processTypes }
        val gap = runCatching {
            Instant.parse(jsonObject(message.raw).optString("created_at")).epochSecond - Instant.parse(jsonObject(previous?.raw).optString("created_at")).epochSecond
        }.getOrNull()
        val replyConflict = !hasProcess && (jsonObject(message.raw).optLong("reply_to") > 0 || jsonObject(previous?.raw).optLong("reply_to") > 0)
        if (previous != null && assistant(message) && assistant(previous) && previous.senderId == message.senderId && !turnConflict && !topicConflict && !replyConflict && (sameTurn || hasProcess || gap in 0L..90L)) {
            prior.add(message)
        } else groups.add(mutableListOf(message))
    }
    val humanBoundary = groups.indexOfLast { it.last().senderId == currentUser }
    val humanSequence = groups.getOrNull(humanBoundary)?.last()?.sequence ?: 0
    messages.filter { it.kind == "runtime_plan" }.forEach { plan ->
        if (maxOf(plan.afterSequence, plan.sequence) < humanSequence) return@forEach
        val index = groups.indexOfLast { group ->
            val turn = group.asReversed().firstOrNull { it.turnId.isNotBlank() }?.turnId.orEmpty()
            group.last().senderId == plan.senderId && assistant(group.last()) && (turn.isBlank() || plan.turnId.isBlank() || turn == plan.turnId)
        }
        if (index > humanBoundary) groups[index].add(plan)
        else if (index < 0 && (groups.isEmpty() || groups.last().last().senderId == currentUser)) groups.add(mutableListOf(plan))
    }
    return groups.map { group ->
        val isAssistant = assistant(group.first())
        val working = mutableListOf<JSONObject>()
        val output = mutableListOf<JSONObject>()
        val firstTool = group.indexOfFirst { entry -> blocks[entry.key].orEmpty().any { it.optString("type") == "tool_use" && it.optString("name") != "update_plan" } }
        group.forEachIndexed { index, message ->
            val source = blocks[message.key].orEmpty()
            val hasMedia = source.any { it.optString("type") in mediaTypes }
            source.forEach { original ->
                val block = JSONObject(original.toString())
                if (isAssistant && firstTool >= 0 && index < firstTool && !hasMedia && block.optString("type") == "text") block.put("type", "assistant_text")
                if (block.optString("type") in processTypes) working.add(block) else output.add(block)
            }
            if (!hasMedia) {
                (message.attachments + messageMetadata(message).objects("attachments").map(::attachment)).forEach { file ->
                    output.add(JSONObject().put("type", "file").put("payload", jsonObject(file.raw)))
                }
            }
        }
        val trace = workingTrace(working)
        val last = group.last()
        val finalMessage = group.lastOrNull { entry ->
            entry.sequence > 0 && blocks[entry.key].orEmpty().any { it.optString("type") == "text" }
        } ?: last
        val replyId = jsonObject(finalMessage.raw).optLong("reply_to")
        val quotedMessage = messages.find { it.sequence == replyId }
        val quote = if (replyId > 0) MessageQuote(
            replyId,
            quotedMessage?.senderName.orEmpty().ifBlank { senderNames[quotedMessage?.senderId].orEmpty() },
            quotedMessage?.text ?: "较早的消息",
        ) else null
        val state = group.asReversed().firstNotNullOfOrNull { message ->
            val raw = jsonObject(message.raw)
            messageMetadata(message).first("execution_state", "executionState").ifBlank { raw.first("_execution_state", "execution_state") }.takeIf(String::isNotBlank)
        }.orEmpty()
        MessageTurn(
            group.first().key, last.senderId, group.lastOrNull { it.senderName.isNotBlank() }?.senderName.orEmpty(), isAssistant,
            trace.first, trace.second,
            working.filter { it.optString("type") == "assistant_text" }.map { it.first("text", "content") }.filter(String::isNotBlank),
            output.filter { it.optString("type") == "text" }.map { it.first("text", "content") }.filter(String::isNotBlank),
            output.filter { it.optString("type") in mediaTypes }.map { attachment(it.optJSONObject("payload") ?: it) }.filter { it.url.isNotBlank() }.distinctBy { it.url },
            state, group.any { it.streaming }, finalMessage.sequence, quote,
            sourceMessageIds = group.map { it.sequence }.filter { it > 0 }.toSet(),
        )
    }
}

private fun planSteps(value: Any?): List<MessagePlanStep> {
    val objectValue = jsonObject(value)
    val candidates = listOf(objectValue.opt("steps"), objectValue.opt("plan"), objectValue.optJSONObject("payload")?.opt("steps"), objectValue.optJSONObject("payload")?.opt("plan"), objectValue.optJSONObject("plan")?.opt("steps"))
    val array = candidates.mapNotNull { decodedJson(it) as? JSONArray }.firstOrNull { it.length() > 0 } ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.opt(index)
        val text = if (item is String) item else jsonObject(item).first("text", "step", "title", "name", "label", "description")
        if (text.isBlank()) null else MessagePlanStep(text, when (jsonObject(item).first("status", "state").lowercase()) {
            "completed", "complete", "done", "完成", "已完成" -> "completed"
            "in_progress", "in-progress", "running", "进行中" -> "in_progress"
            else -> "pending"
        })
    }
}

private fun resultPlan(value: Any?): List<MessagePlanStep> = displayValue(value).lineSequence().mapNotNull { line ->
    val match = Regex("^\\s*\\d+[.)、]?\\s*(已完成|完成|进行中|待处理|pending|in_progress|completed|done)\\s*[-:：]\\s*(.+)$", RegexOption.IGNORE_CASE).find(line) ?: return@mapNotNull null
    val item = JSONObject().put("steps", JSONArray().put(JSONObject().put("text", match.groupValues[2]).put("status", match.groupValues[1])))
    planSteps(item).singleOrNull()
}.toList()

/** Only interpret execution metadata, never status-like text inside command output. */
internal fun toolResultFailed(block: JSONObject): Boolean {
    if (block.optBoolean("is_error") || jsonObject(block.opt("metadata")).optBoolean("is_error")) return true
    val value = block.opt("content") ?: block.opt("text")
    if (value is JSONObject) {
        return value.optBoolean("is_error") || value.optBoolean("timed_out") ||
            value.first("status").lowercase() in setOf("failed", "error", "timed_out", "timeout") ||
            value.opt("exit_code")?.toString()?.toIntOrNull()?.let { it != 0 } == true
    }
    val text = (value as? String)?.trimStart() ?: return false
    if (!text.startsWith("Command completed\n") && !text.startsWith("Command completed\r\n")) return false
    val header = text.lineSequence().drop(1).takeWhile {
        !it.trim().matches(Regex("(?:stdout|stderr|output):.*", RegexOption.IGNORE_CASE)) && it.isNotBlank()
    }.toList()
    return header.any { line ->
        val key = line.substringBefore(':').trim()
        val field = line.substringAfter(':', "").trim()
        key == "status" && field in setOf("failed", "error", "timed_out", "timeout") ||
            key == "exit_code" && field.toIntOrNull()?.let { it != 0 } == true
    }
}

private fun toolFailureReason(value: Any?): String {
    val decoded = decodedJson(value)
    if (decoded is JSONObject) {
        return decoded.first("message", "stderr", "error").ifBlank {
            decoded.opt("exit_code")?.toString()?.toIntOrNull()?.let { "退出码 $it" }.orEmpty()
        }.take(240)
    }
    val text = displayValue(value).trim()
    val stderr = Regex("(?m)^stderr:\\s*\\n?([\\s\\S]*)").find(text)?.groupValues?.get(1)?.trim()
    if (!stderr.isNullOrBlank() && stderr !in setOf("(empty)", "（空）")) return stderr.lineSequence().first().take(240)
    val exit = Regex("(?m)^exit_code:\\s*(-?\\d+)\\s*$").find(text)?.groupValues?.get(1)
    return if (exit != null) "退出码 $exit" else text.lineSequence().firstOrNull().orEmpty().take(240)
}

private fun workingTrace(blocks: List<JSONObject>): Pair<List<MessageWorkStep>, List<MessagePlanStep>> {
    val steps = mutableListOf<MessageWorkStep>()
    val pending = mutableMapOf<String, Int>()
    val hidden = mutableSetOf<String>()
    var hiddenWithoutId = false
    var plan = emptyList<MessagePlanStep>()
    blocks.forEachIndexed { offset, block ->
        val type = block.optString("type")
        val id = if (type == "tool_result") block.first("tool_use_id", "tool_call_id", "id") else block.first("id", "tool_use_id", "tool_call_id")
        val metadata = jsonObject(block.opt("metadata"))
        val input = decodedJson(block.opt("input"))
        val subagent = metadata.first("subagent_id").ifBlank { jsonObject(input).first("subagent_id") }.ifBlank { id.takeIf { it.startsWith("subagent:") }.orEmpty() }
        if (subagent.isNotBlank()) {
            val priorIndex = steps.indexOfFirst { it.kind == "subagent" && it.id == subagent }
            val prior = steps.getOrNull(priorIndex) ?: MessageWorkStep(subagent, "subagent", metadata.first("subagent_name", "display_name").ifBlank { jsonObject(input).first("subagent_name", "display_name").ifBlank { "子 Agent" } })
            val error = type == "tool_result" && toolResultFailed(block)
            val result = block.opt("content") ?: block.opt("text")
            val child = MessageWorkStep(
                id = "sub-$offset",
                kind = if (type == "tool_result") "result" else "thinking",
                name = if (type == "tool_result") "执行结果" else "任务",
                input = displayValue(input),
                result = displayValue(result).takeIf(String::isNotBlank),
                summary = block.first("thinking", "text", "content"),
                error = error,
                failureReason = if (error) toolFailureReason(result) else "",
            )
            val updated = prior.copy(children = prior.children + child, error = prior.error || error,
                status = metadata.first("subagent_status", "status").ifBlank { if (type == "tool_result") if (error) "failed" else "completed" else prior.status })
            if (priorIndex >= 0) steps[priorIndex] = updated else steps.add(updated)
            return@forEachIndexed
        }
        when (type) {
            "runtime_plan" -> planSteps(block.opt("payload")).takeIf { it.isNotEmpty() }?.let { plan = it }
            "thinking", "reasoning", "process" -> steps.add(MessageWorkStep("step-$offset", "thinking", "思考", summary = block.first("thinking", "text", "content")))
            "tool_use" -> {
                val name = block.optString("name").ifBlank { "工具调用" }
                if (name in setOf("send_text", "send_file")) {
                    if (id.isNotBlank()) hidden.add(id) else hiddenWithoutId = true
                    return@forEachIndexed
                }
                if (id.isNotBlank()) pending[id] = steps.size
                steps.add(MessageWorkStep(
                    id = "step-$offset", kind = "tool", name = name,
                    input = displayValue(input),
                    summary = if (input is String) input else jsonObject(input).first("command", "file_path", "pattern", "query", "path", "url", "content"),
                    description = jsonObject(input).first("description").ifBlank { metadata.first("description") },
                ))
            }
            "tool_result" -> {
                if (id in hidden || (id.isBlank() && hiddenWithoutId)) {
                    if (id.isBlank()) hiddenWithoutId = false
                    return@forEachIndexed
                }
                val index = pending.remove(id) ?: if (id.isBlank()) steps.indexOfFirst { it.kind == "tool" && it.result == null }.takeIf { it >= 0 } else null
                val value = block.opt("content") ?: block.opt("text")
                val error = toolResultFailed(block)
                val reason = if (error) toolFailureReason(value) else ""
                if (index != null) {
                    steps[index] = steps[index].copy(
                        result = displayValue(value), error = error,
                        status = if (error) "failed" else "completed", failureReason = reason,
                    )
                } else {
                    steps.add(MessageWorkStep(
                        id = "step-$offset", kind = "result", name = if (error) "工具错误" else "工具结果",
                        result = displayValue(value), error = error, failureReason = reason,
                    ))
                }
            }
        }
    }
    var toolPlan = emptyList<MessagePlanStep>()
    steps.filter { it.name == "update_plan" }.forEach { step ->
        val updated = planSteps(step.input).ifEmpty { resultPlan(step.result) }
        if (updated.isNotEmpty() && !step.error) toolPlan = updated
    }
    return steps.filterNot { it.name == "update_plan" && !it.error && (planSteps(it.input).isNotEmpty() || resultPlan(it.result).isNotEmpty()) } to plan.ifEmpty { toolPlan }
}

fun attachmentDescription(file: Attachment): String {
    val raw = jsonObject(file.raw)
    val extension = file.name.substringAfterLast('.', "").uppercase().take(12)
    val type = extension.ifBlank { file.mime.substringAfter('/').substringBefore(';').uppercase() }
    val bytes = listOf("size", "file_size", "size_bytes", "content_length").firstNotNullOfOrNull { key -> raw.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0 } }
    val size = when {
        bytes == null -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    }
    return listOf(type, size).filter(String::isNotBlank).joinToString(" · ")
}
