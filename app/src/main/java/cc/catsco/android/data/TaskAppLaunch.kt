package cc.catsco.android.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class TaskAppLaunch(val reference: String, val topic: String, val appId: String, val title: String,
    val agent: Long, val path: String, val expires: Instant) {
    fun feedback(text: String, messageId: String): JSONObject {
        require(Instant.now().isBefore(expires)) { "此次打开已过期，请重新打开应用。" }
        val note = JSONObject().put("id", messageId).put("kind", "element").put("label", "应用首页")
            .put("body", text).put("target", JSONObject().put("selector", ":root"))
        val annotation = JSONObject().put("contract_version", "catsco.gateway-annotations.v1")
            .put("app_id", appId).put("agent_uid", agent).put("page", JSONObject().put("path", path))
            .put("annotations", JSONArray().put(note))
        return JSONObject().put("open_ref", reference).put("client_msg_id", messageId)
            .put("content", text).put("gateway_annotations", annotation)
    }

    companion object {
        fun from(result: JSONObject, url: String, topic: String, app: JSONObject): TaskAppLaunch {
            val binding = result.optJSONObject("open_binding") ?: error("应用未能关联当前任务，请重试。")
            val launch = url.toHttpUrlOrNull() ?: error("无效的应用地址。")
            val origin = binding.first("app_origin").toHttpUrlOrNull()
            val id = app.first("id")
            val ref = binding.first("open_ref")
            val agent = binding.optLong("agent_uid")
            val path = launch.queryParameter("next")
            val expires = runCatching { Instant.parse(binding.first("expires_at")) }.getOrNull()
            check(binding.first("contract_version") == "catsco.artifact-open-binding.v1" && binding.first("topic_id") == topic &&
                binding.first("app_id") == id && agent > 0 && ref.matches(Regex("aob_[A-Za-z0-9_-]+")) &&
                origin != null && launch.isHttps && origin.isHttps && launch.host == origin.host && launch.port == origin.port &&
                path == "/$id/" && expires != null && Instant.now().isBefore(expires)) { "应用返回的会话信息无效，请重新打开。" }
            return TaskAppLaunch(ref, topic, id, app.first("title", "name").ifBlank { id }, agent, path, expires)
        }
    }
}
