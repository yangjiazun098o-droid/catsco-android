package cc.catsco.android

import cc.catsco.android.data.conversation
import cc.catsco.android.data.AccountStorage
import cc.catsco.android.data.OutcomeCursor
import cc.catsco.android.data.loadNotificationInbox
import cc.catsco.android.data.saveNotificationInbox
import cc.catsco.android.data.saveBackgroundNotices
import cc.catsco.core.Session
import cc.catsco.core.ServerAddress
import androidx.test.platform.app.InstrumentationRegistry
import cc.catsco.android.data.conversationRunStatus
import cc.catsco.android.data.replyForNotification
import cc.catsco.android.data.runNotification
import cc.catsco.android.ui.WorkspaceNotificationKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceNotificationTest {
    private val terminal = JSONObject("""{
        "topic_id":"grp_31", "run_id":"test-run", "state":"completed",
        "summary":"已生成报告", "source_uid":12, "updated_at":"2026-11-20T03:00:04Z"
    }""")
    private val task = conversation(JSONObject("""{"id":"grp_31","name":"整理报告","agent_ids":[12]}"""))

    @Test fun receivesOnlyConfirmedOutcomes() {
        val status = requireNotNull(conversationRunStatus(terminal))
        val first = requireNotNull(runNotification(status, task, 0))
        assertEquals("整理报告", first.title)
        assertEquals(WorkspaceNotificationKind.Completed, first.kind)
        val muted = conversation(JSONObject("""{"id":"grp_31","notifications_muted":true}"""))
        assertNull(runNotification(status, muted, 0))
        assertEquals(first.key, runNotification(status.copy(summary = "更新摘要"), task, 0)?.key)
        assertNull(runNotification(status.copy(state = "stale"), task, 0))
        assertNull(runNotification(status.copy(state = "cancelled"), task, 0))
        assertEquals(WorkspaceNotificationKind.Quota, runNotification(status.copy(
            state = "failed", error = "quota_exhausted",
        ), task, 0)?.kind)
        assertEquals(WorkspaceNotificationKind.Failed, runNotification(status.copy(
            state = "failed", error = "超时",
        ), task, 0)?.kind)
    }

    @Test fun notificationCheckpointSurvivesProcessRecreationAndIsScopedToAccount() {
        val storage = AccountStorage(InstrumentationRegistry.getInstrumentation().targetContext)
        val server = ServerAddress.parse("https://notice-test.invalid")
        val first = Session(server, "test", "1", "")
        val second = first.copy(userId = "2")
        storage.clear(first)
        storage.clear(second)
        try {
            val inbox = storage.loadNotificationInbox(first, 1000)
            assertEquals(1000L, storage.loadNotificationInbox(first, 2000).since)
            val item = requireNotNull(runNotification(requireNotNull(conversationRunStatus(terminal)), task, 0))
            val cursor = OutcomeCursor(1_200_043, 78)
            storage.saveNotificationInbox(first, inbox.copy(items = listOf(item.copy(unread = false)), cursor = cursor))
            storage.saveBackgroundNotices(first, listOf(item, item.copy(key = "background", title = "后台完成")))
            val reopened = storage.loadNotificationInbox(first, 3000)
            assertEquals(cursor, reopened.cursor)
            assertEquals(2, reopened.items.size)
            assertFalse(reopened.items.single { it.key == item.key }.unread)
            assertEquals("后台完成", reopened.items.single { it.key == "background" }.title)
            assertNull(reopened.latestKey)
            val other = storage.loadNotificationInbox(second, 3000)
            assertTrue(other.items.isEmpty())
            assertEquals(3000L, other.since)
        } finally {
            storage.clear(first)
            storage.clear(second)
        }
    }

    @Test fun readsReplyForThatRunOnly() {
        val status = requireNotNull(conversationRunStatus(terminal))
        val notice = requireNotNull(runNotification(status, task, status.updatedAt - 3_000))
        val result = JSONObject("""{"messages":[
            {"seq_id":1,"from_uid":12,"created_at":"2026-11-20T03:00:00Z","type":"text","content":"上一个任务"},
            {"seq_id":2,"from_uid":20,"created_at":"2026-11-20T03:00:02Z","type":"text","content":"其他人"},
            {"seq_id":3,"from_uid":12,"created_at":"2026-11-20T03:00:02Z","type":"thinking","content":"过程"},
            {"seq_id":5,"from_uid":12,"created_at":"2026-11-20T03:00:03Z","metadata":{"turn_id":"different-run"},"type":"text","content":"并发任务"},
            {"seq_id":4,"from_uid":12,"created_at":"2026-11-20T03:00:03Z","type":"text","content":"正式回复"}
        ]}""")
        assertEquals("正式回复", replyForNotification(notice, result))
        assertNull(replyForNotification(notice.copy(startedAt = 0), result))
        result.getJSONArray("messages").put(JSONObject("""{"seq_id":6,"from_uid":12,"created_at":"2026-11-20T03:00:03Z","run_id":"test-run","metadata":{"turn_id":"separate-stream"},"type":"text","content":"明确关联"}"""))
        assertEquals("明确关联", replyForNotification(notice, result))
    }
}
