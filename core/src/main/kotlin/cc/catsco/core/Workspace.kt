package cc.catsco.core

import java.net.URI

@JvmInline
value class ServerAddress private constructor(val value: String) {
    companion object {
        fun parse(input: String): ServerAddress {
            val uri = URI(input.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/")) { "请输入 HTTPS 服务器地址，不包含路径、账号或参数。" }
            return ServerAddress(URI("https", null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString())
        }
    }
}
data class Session(val server: ServerAddress, val token: String, val userId: String, val name: String, val avatarUrl: String = "")
data class Employee(
    val id: String,
    val name: String,
    val online: Boolean = false,
    val owner: Boolean = false,
    val description: String = "",
    val cloud: Boolean? = null,
    val createdAt: Long = 0,
    val addedAt: Long = 0,
    val avatarUrl: String = "",
    val username: String = "",
)

fun List<Employee>.prioritizedForTasks(): List<Employee> = sortedWith(
    compareByDescending<Employee> { it.cloud == true }
        .thenByDescending { it.owner }
        .thenByDescending { if (it.owner) it.createdAt else it.addedAt },
)

/** Reuse the last successful choice if still available, then use the priority-ordered roster. */
fun List<Employee>.defaultTaskEmployeeId(lastTaskEmployeeId: String): String =
    (firstOrNull { it.id == lastTaskEmployeeId } ?: firstOrNull())?.id.orEmpty()

fun List<Employee>.withObservedAdditions(previous: List<Employee>?, now: Long): List<Employee> {
    val priorById = previous.orEmpty().associateBy { it.id }
    return map { employee ->
        val prior = priorById[employee.id]
        employee.copy(
            cloud = employee.cloud ?: prior?.cloud,
            createdAt = employee.createdAt.takeIf { it > 0 } ?: prior?.createdAt ?: 0,
            // The first sync is a baseline, not evidence of historical addition order.
            addedAt = employee.addedAt.takeIf { it > 0 } ?: prior?.addedAt
                ?: if (previous != null) now else 0,
        )
    }.prioritizedForTasks()
}
data class Conversation(
    val id: String,
    val name: String,
    val preview: String = "",
    val active: Boolean = false,
    val date: String = "",
    val agentId: String = "",
    val task: Boolean = false,
    val projectId: String = "",
    val runStatus: ConversationRunStatus? = null,
    val notificationsMuted: Boolean = false,
)
data class ConversationRunStatus(
    val topic: String,
    val runId: String,
    val state: String,
    val summary: String,
    val error: String,
    val sourceId: String,
    val updatedAt: Long,
)
data class Project(val id: String, val name: String)
data class Workspace(val employees: List<Employee> = emptyList(), val conversations: List<Conversation> = emptyList(), val projects: List<Project> = emptyList())
interface WorkspaceRepository {
    suspend fun login(server: ServerAddress, account: String, password: String): Session
    suspend fun restore(session: Session): Session
    suspend fun workspace(session: Session): Workspace
    suspend fun conversations(session: Session): List<Conversation> = workspace(session).conversations
    suspend fun renameConversation(session: Session, topicId: String, name: String)
    suspend fun resource(session: Session, path: String, query: Map<String, String> = emptyMap(), method: String = "GET", body: String? = null): String {
        error("调试预览不连接业务接口，请使用真实账号登录。")
    }
    suspend fun upload(session: Session, file: java.io.File, name: String, mime: String, type: String = if (mime.startsWith("image/")) "image" else "file"): String {
        error("调试预览不上传文件，请使用真实账号登录。")
    }
}
interface SessionStore {
    fun read(): Session?
    fun write(session: Session)
    fun clear()
}
