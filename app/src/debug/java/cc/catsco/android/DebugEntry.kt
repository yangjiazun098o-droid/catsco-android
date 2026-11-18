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
                Conversation("grp_17", "整理产品发布资料", "发布资料已经整理好了。", date = "2026-11-10T16:20:00Z"),
                Conversation("grp_18", "检查测试结果", active = true, date = "2026-11-11T09:15:00Z"),
                Conversation("p2p_9_101", "小八", date = "2026-11-08T13:40:00Z"),
                Conversation("grp_20", "产品讨论", date = "2026-11-06T10:00:00Z"),
            ),
        )
        override suspend fun renameConversation(session: Session, topicId: String, name: String) = Unit
    }
}
