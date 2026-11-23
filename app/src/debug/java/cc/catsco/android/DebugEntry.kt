package cc.catsco.android

import cc.catsco.core.*

// Debug source set only. This class's fixture implementation does not exist in Release.
object DebugEntry {
    const val available = true
    fun repository(): WorkspaceRepository = object : WorkspaceRepository {
        override suspend fun login(server: ServerAddress, account: String, password: String) = Session(server, "debug-fixture", "101", "本地预览")
        override suspend fun restore(session: Session) = session
        override suspend fun workspace(session: Session) = Workspace(
            employees = listOf(Employee("9", "小八", true), Employee("12", "研究助手")),
            conversations = listOf(
                Conversation("grp_17", "整理产品发布资料", "发布资料已经整理好了。", date = "2026-11-10T16:20:00Z", agentId = "9"),
                Conversation("grp_18", "检查测试结果", active = true, date = "2026-11-11T09:15:00Z", agentId = "12"),
                Conversation("p2p_9_101", "小八", date = "2026-11-08T13:40:00Z"),
                Conversation("grp_20", "产品讨论", date = "2026-11-06T10:00:00Z"),
            ),
        )
        override suspend fun resource(session: Session, path: String, query: Map<String, String>, method: String, body: String?): String =
            when (path) {
                "/api/agents/9/artifacts" -> """{"artifacts":[{"name":"发布清单.pdf"},{"name":"访谈提纲.docx"}]}"""
                "/api/agents/12/artifacts" -> """{"artifacts":[{"name":"测试结果.xlsx"}]}"""
                "/api/topics/grp_17/files" -> """{"files":[{"name":"发布清单.pdf"}]}"""
                "/api/artifacts/apps" -> when (query["agent"]) {
                    "9" -> """{"apps":[
                        {"id":"feedback-form","title":"反馈收集表","updated_at":"2026-11-20T04:00:00Z"},
                        {"id":"launch-board","title":"发布进度看板","source_topic_id":"grp_17","updated_at":"2026-11-19T09:00:00Z"}
                    ]}"""
                    "12" -> """{"apps":[{"id":"test-dashboard","title":"测试结果看板"}]}"""
                    else -> """{"apps":[]}"""
                }
                else -> super.resource(session, path, query, method, body)
            }
        override suspend fun renameConversation(session: Session, topicId: String, name: String) = Unit
    }
}
