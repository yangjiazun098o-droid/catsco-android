package cc.catsco.android

import cc.catsco.android.data.conversation
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

    @Test fun readsReplyForThatRunOnly() {
        val status = requireNotNull(conversationRunStatus(terminal))
        val notice = requireNotNull(runNotification(status, task, status.updatedAt - 3_000))
        val result = JSONObject("""{"messages":[
            {"seq_id":1,"from_uid":12,"created_at":"2026-11-20T03:00:00Z","type":"text","content":"上一个任务"},
            {"seq_id":2,"from_uid":20,"created_at":"2026-11-20T03:00:02Z","type":"text","content":"其他人"},
            {"seq_id":3,"from_uid":12,"created_at":"2026-11-20T03:00:02Z","type":"thinking","content":"过程"},
            {"seq_id":4,"from_uid":12,"created_at":"2026-11-20T03:00:03Z","type":"text","content":"正式回复"}
        ]}""")
        assertEquals("正式回复", replyForNotification(notice, result))
        assertNull(replyForNotification(notice.copy(startedAt = 0), result))
    }
}
