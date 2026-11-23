package cc.catsco.android.data

import cc.catsco.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val status: Int, val detail: String = "") : Exception(when (status) {
    401 -> "登录已过期，请重新登录。"
    403 -> "当前账号没有权限。"
    404 -> "此功能在当前服务器上暂不可用。"
    409 -> "资料已发生变化，请刷新后再操作。"
    in 500..599 -> "服务暂时不可用，请稍后重试。"
    else -> detail.ifBlank { "请求失败（$status），请稍后重试。" }
})

class HttpWorkspaceRepository : WorkspaceRepository {
    val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun api(
        server: ServerAddress,
        path: String,
        token: String = "",
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
    ): JSONObject {
        require(path.startsWith("/api/") && !path.contains('?') && path.split('/').none { it == ".." }) { "无效的接口地址。" }
        val url = server.value.toHttpUrl().newBuilder().encodedPath(path)
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("Accept", "application/json")
        if (token.isNotBlank()) request.header("Authorization", "Bearer $token")
        val payload = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        request.method(method, if (method in setOf("POST", "PUT", "PATCH")) payload ?: "{}".toRequestBody("application/json".toMediaType()) else payload)
        return execute(request.build())
    }

    private suspend fun execute(request: Request, limit: Int = 8 * 1024 * 1024): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val output = java.io.ByteArrayOutputStream()
                        it.body?.byteStream()?.use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                check(output.size() + count <= limit) { "服务器响应过大。" }
                                output.write(buffer, 0, count)
                            }
                        }
                        val text = output.toString(Charsets.UTF_8.name())
                        val json = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrNull()
                        if (!it.isSuccessful) throw ApiFailure(it.code, json?.optJSONObject("error")?.optString("message") ?: json?.optString("error").orEmpty())
                        json ?: error("服务器返回了无法识别的数据。")
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    override suspend fun resource(session: Session, path: String, query: Map<String, String>, method: String, body: String?): String =
        api(session.server, path, session.token, method, query, body?.let(::JSONObject)).toString()

    override suspend fun upload(session: Session, file: java.io.File, name: String, mime: String, type: String): String {
        val size = file.length()
        require(size in 1..100L * 1024 * 1024) { "请选择有内容且不超过 100 MB 的文件。" }
        val url = session.server.value.toHttpUrl().newBuilder().encodedPath("/api/upload")
            .addQueryParameter("raw", "1").addQueryParameter("type", type).build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${session.token}")
            .header("X-CatsCo-File-Name", encodeSegment(name))
            .header("X-CatsCo-File-Size", size.toString())
            .post(file.asRequestBody(mime.toMediaType())).build()
        val response = execute(request)
        val uploaded = response.optJSONObject("file") ?: response
        check(uploaded.optString("url").isNotBlank()) { "上传响应缺少文件地址。" }
        return uploaded.put("name", name).put("mime_type", mime).toString()
    }

    override suspend fun login(server: ServerAddress, account: String, password: String): Session {
        val result = try {
            api(server, "/api/auth/login", method = "POST", body = JSONObject().put("account", account.trim()).put("password", password).put("persistent", true))
        } catch (error: ApiFailure) {
            if (error.status == 401) throw IllegalArgumentException("账号或密码不正确，请检查后重试。")
            throw error
        }
        return session(server, result, account)
    }

    fun session(server: ServerAddress, result: JSONObject, fallback: String = ""): Session {
        val uid = result.first("uid", "id")
        val token = result.optString("token")
        check(token.isNotBlank() && uid.toLongOrNull()?.let { it > 0 } == true) { "登录信息不完整，请重新登录。" }
        return Session(server, token, uid, result.first("display_name", "name", "username").ifBlank { fallback }, result.first("avatar_url"))
    }

    override suspend fun restore(session: Session): Session {
        val profile = api(session.server, "/api/me", session.token)
        if (profile.first("uid", "id") != session.userId) throw ApiFailure(401)
        return session.withProfile(profile)
    }

    override suspend fun workspace(session: Session): Workspace = coroutineScope {
        val roster = async { api(session.server, "/api/agents", session.token).objects("agents") }
        val details = async {
            try {
                api(session.server, "/api/bots", session.token).objects("bots")
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiFailure) {
                if (error.status == 401) throw error
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
        val conversationList = async { conversations(session) }
        val metadata = details.await().associateBy { it.first("id", "uid", "user_id") }
        val agents = roster.await().map { employee(it, metadata[it.first("id", "uid", "user_id")]) }
        Workspace(agents.prioritizedForTasks(), conversationList.await())
    }

    override suspend fun conversations(session: Session): List<Conversation> {
        val response = api(session.server, "/api/conversations", session.token)
        return withContext(Dispatchers.Default) { response.objects("conversations").map(::conversation) }
    }

    override suspend fun renameConversation(session: Session, topicId: String, name: String) {
        require(name.isNotBlank()) { "名称不能为空。" }
        if (topicId.startsWith("grp_")) {
            val group = topicId.removePrefix("grp_").toLongOrNull() ?: error("无效的会话。")
            val info = api(session.server, "/api/groups/info", session.token, query = mapOf("id" to group.toString()))
                .optJSONObject("group") ?: error("无法读取群组资料。")
            api(session.server, "/api/groups/update", session.token, "POST", body = JSONObject().put("group_id", group).put("name", name).put("avatar_url", info.optString("avatar_url")))
        } else api(session.server, "/api/conversations", session.token, "PATCH", body = JSONObject().put("topic_id", topicId).put("name", name))
    }
}

fun JSONObject.first(vararg keys: String): String = keys.asSequence().map { if (isNull(it)) "" else optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()

internal fun employee(roster: JSONObject, metadata: JSONObject? = null): Employee {
    val owner = roster.optBoolean("is_owner") || roster.optString("relation") == "owner"
    val cloud = when {
        roster.has("is_cloud") && !roster.isNull("is_cloud") -> roster.optBoolean("is_cloud")
        metadata?.has("is_cloud") == true && !metadata.isNull("is_cloud") -> metadata.optBoolean("is_cloud")
        roster.first("tenant_name").isNotBlank() || metadata?.first("tenant_name").orEmpty().isNotBlank() -> true
        owner && metadata != null -> false
        else -> null
    }
    return Employee(
        id = roster.first("id", "uid", "user_id"),
        name = roster.first("display_name", "displayName", "name", "username"),
        online = roster.optBoolean("is_online") || roster.optBoolean("routable"),
        owner = owner,
        description = roster.first("description", "role").ifBlank { metadata?.first("description", "role").orEmpty() },
        cloud = cloud,
        createdAt = employeeTimestamp(roster.first("created_at").ifBlank { metadata?.first("created_at").orEmpty() }),
        addedAt = employeeTimestamp(roster.first("added_at").ifBlank { metadata?.first("added_at").orEmpty() }),
        avatarUrl = roster.first("avatar_url").ifBlank { metadata?.first("avatar_url").orEmpty() },
        username = roster.first("username").ifBlank { metadata?.first("username").orEmpty() },
    )
}

private fun employeeTimestamp(value: String): Long {
    val numeric = value.toLongOrNull()
    if (numeric != null) return if (numeric in 1..9_999_999_999L) numeric * 1000 else numeric.coerceAtLeast(0)
    return runCatching { java.time.Instant.parse(value).toEpochMilli().coerceAtLeast(0) }.getOrDefault(0)
}

fun Session.withProfile(profile: JSONObject): Session = copy(
    name = profile.first("display_name", "name", "username").ifBlank { name },
    avatarUrl = if (profile.has("avatar_url")) profile.first("avatar_url") else avatarUrl,
)
fun JSONObject.objects(vararg keys: String): List<JSONObject> {
    val array = keys.firstNotNullOfOrNull { optJSONArray(it) } ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
fun encodeSegment(text: String): String = java.net.URLEncoder.encode(text, Charsets.UTF_8.name()).replace("+", "%20")
fun conversation(json: JSONObject): Conversation {
    val id = json.first("id", "topic_id", "topic")
    val bot = json.optBoolean("is_bot")
    val taskStatus = json.optJSONObject("task_status")
    return Conversation(
        id, json.first("name").ifBlank { "新对话" }, json.optString("preview"),
        taskStatus?.optString("state") in setOf("running", "waiting", "queued", "working", "pending"), json.optString("last_time"),
        json.optJSONArray("agent_ids")?.optString(0).orEmpty().ifBlank { if (bot) json.first("friend_id") else "" },
        json.optBoolean("is_agent_task") || bot || json.optBoolean("is_group") || id.startsWith("grp_"),
        json.optLong("project_id").takeIf { it > 0 }?.toString().orEmpty(),
        taskStatus?.let { conversationRunStatus(it, id) },
        json.optBoolean("notifications_muted"),
    )
}
