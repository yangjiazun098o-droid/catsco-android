package cc.catsco.android

import cc.catsco.android.data.*
import cc.catsco.core.MessageTimeline
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessagePresentationTest {
    private fun message(raw: String) = chatMessage(JSONObject(raw))
    private fun project(vararg raw: String) = presentMessages(raw.map(::message), "1", setOf("9", "10"))

    @Test fun shellEnvelopeFailureOverridesFalseTransportFlag() {
        val turn = project("""{"id":1,"from_uid":9,"content_blocks":[
            {"type":"tool_use","id":"a","name":"execute_shell","input":{"command":"ls /missing","description":"查找本会话文件"}},
            {"type":"tool_result","tool_use_id":"a","is_error":false,"content":"Command completed\nstatus: failed\ncommand: ls /missing\nexit_code: 1\nstdout:\nstderr:\nNo such file"},
            {"type":"text","text":"已说明文件不存在的原因"}
        ],"metadata":{"execution_state":"completed"}}""").single()
        assertTrue(turn.steps.single().error)
        assertEquals("failed", turn.steps.single().status)
        assertEquals("No such file", turn.steps.single().failureReason)
        assertEquals("查找本会话文件", turn.steps.single().description)
        assertEquals("completed", turn.executionState)
        assertTrue(turn.hasOutput)
    }

    @Test fun emptyStderrUsesActualExitCode() {
        val turn = project("""{"id":1,"from_uid":9,"content_blocks":[
            {"type":"tool_result","content":"Command completed\nstatus: failed\nexit_code: 1\nstdout:\n(empty)\nstderr:\n(empty)"}
        ]}""").single()
        assertEquals("退出码 1", turn.steps.single().failureReason)
    }

    @Test fun errorsInOutputAreNotExecutionMetadata() {
        listOf(
            "stdout:\nstatus: failed\nexit_code: 1",
            "A report mentions exit_code: 1",
            """{"status":"failed","exit_code":1}""",
            "Command completed\nstatus: completed\nexit_code: 0\nstdout:\nstatus: failed\nexit_code: 2",
        ).forEach { output ->
            assertFalse(toolResultFailed(JSONObject().put("content", output)))
        }
    }

    @Test fun structuredFailuresAndLegacyResultFlagsAreRecognized() {
        listOf("""{"exit_code":2}""", """{"timed_out":true}""", """{"status":"failed"}""").forEach {
            assertTrue(toolResultFailed(JSONObject().put("content", JSONObject(it))))
        }
        assertFalse(toolResultFailed(JSONObject().put("content", JSONObject("""{"exit_code":0,"stdout":"failed"}"""))))
        assertTrue(toolResultFailed(JSONObject().put("metadata", JSONObject().put("is_error", true))))
    }

    @Test fun toolResultsMatchTheirCallIdsAcrossMessages() {
        val turn = project(
            """{"id":1,"from_uid":9,"content_blocks":[{"type":"tool_use","id":"a","name":"read_file","input":{"file_path":"a.txt"}}]}""",
            """{"id":2,"from_uid":9,"content_blocks":[{"type":"tool_use","id":"b","name":"grep","input":{"pattern":"match"}}]}""",
            """{"id":3,"from_uid":9,"content_blocks":[{"type":"tool_result","tool_use_id":"b","content":"result B"}]}""",
            """{"id":4,"from_uid":9,"content_blocks":[{"type":"tool_result","tool_use_id":"a","content":"result A"}]}""",
        ).single()
        assertEquals(2, turn.steps.size)
        assertEquals("result A", turn.steps[0].result)
        assertEquals("result B", turn.steps[1].result)
        assertEquals("a.txt", turn.steps[0].summary)
    }

    @Test fun humanAndTurnBoundariesKeepIndependentReplies() {
        val turns = project(
            """{"id":1,"from_uid":9,"metadata":{"turn_id":"one"},"content":"first"}""",
            """{"id":2,"from_uid":9,"metadata":{"turn_id":"two"},"content":"second"}""",
            """{"id":3,"from_uid":1,"content":"new question"}""",
            """{"id":4,"from_uid":9,"content":"new answer"}""",
        )
        assertEquals(4, turns.size)
        assertFalse(turns[2].assistant)
    }

    @Test fun unknownResultDoesNotOverwriteAnotherTool() {
        val turn = project("""{"id":1,"from_uid":9,"content_blocks":[
            {"type":"tool_use","id":"a","name":"read_file"},
            {"type":"tool_result","tool_use_id":"unknown","is_error":true,"content":"denied"}
        ]}""").single()
        assertNull(turn.steps[0].result)
        assertEquals("denied", turn.steps[1].result)
        assertTrue(turn.steps[1].error)
    }

    @Test fun latestPlanIsPersistentAndDeliveryPlumbingIsHidden() {
        val turn = project("""{"id":1,"from_uid":9,"content_blocks":[
            {"type":"tool_use","id":"p1","name":"update_plan","input":{"steps":[{"text":"old","status":"pending"}]}},
            {"type":"tool_result","tool_use_id":"p1","content":"ok"},
            {"type":"tool_use","id":"p2","name":"update_plan","input":{"steps":[{"text":"latest","status":"completed"},{"text":"next","status":"in_progress"}]}},
            {"type":"tool_result","tool_use_id":"p2","content":"ok"},
            {"type":"tool_use","id":"send","name":"send_file","input":{"name":"report.html"}},
            {"type":"tool_result","tool_use_id":"send","content":"delivered"}
        ]}""").single()
        assertTrue(turn.steps.isEmpty())
        assertEquals(listOf("latest", "next"), turn.plan.map { it.text })
        assertEquals(listOf("completed", "in_progress"), turn.plan.map { it.status })
    }

    @Test fun runtimePlanWinsOverEarlierToolSnapshot() {
        val history = listOf(
            message("""{"id":1,"from_uid":9,"content_blocks":[{"type":"tool_use","name":"update_plan","input":{"steps":[{"text":"earlier"}]}}]}"""),
            message("""{"id":2,"from_uid":9,"type":"runtime_plan","content":{"payload":{"steps":[{"text":"current","status":"running"}]}}}"""),
        )
        assertEquals("current", presentMessages(history, "1", setOf("9")).single().plan.single().text)
    }

    @Test fun oldRuntimePlanDoesNotLeakIntoNextHumanTask() {
        val timeline = MessageTimeline()
        timeline.receive(message("""{"id":10,"from_uid":1,"content":"old question"}"""))
        timeline.receive(message("""{"from_uid":9,"type":"runtime_plan","content":{"steps":[{"text":"old task"}]}}"""))
        timeline.receive(message("""{"id":20,"from_uid":1,"content":"new question"}"""))
        assertTrue(presentMessages(timeline.messages, "1", setOf("9")).all { it.plan.isEmpty() })
    }

    @Test fun mixedContentPreservesFilesAndFinalText() {
        val turn = project("""{"id":1,"from_uid":9,"content_blocks":[
            {"type":"tool_use","id":"a","name":"read_file","input":{"file_path":"report.md"}},
            {"type":"tool_result","tool_use_id":"a","content":"read"},
            {"type":"file","payload":{"name":"report.html","url":"/file","size":48640,"mime_type":"text/html"}},
            {"type":"text","text":"Final answer"}
        ]}""").single()
        assertEquals(listOf("Final answer"), turn.text)
        assertEquals(1, turn.files.size)
        assertEquals("HTML · 47.5 KB", attachmentDescription(turn.files.single()))
        assertEquals(1, turn.steps.size)
    }

    @Test fun failedPlanUpdateStaysVisibleAndDoesNotChangePlan() {
        val turn = project("""{"id":1,"from_uid":9,"content_blocks":[
            {"type":"tool_use","id":"p1","name":"update_plan","input":{"steps":[{"text":"confirmed"}]}},
            {"type":"tool_result","tool_use_id":"p1","content":"ok"},
            {"type":"tool_use","id":"p2","name":"update_plan","input":{"steps":[{"text":"rejected"}]}},
            {"type":"tool_result","tool_use_id":"p2","is_error":true,"content":"conflict"}
        ]}""").single()
        assertEquals("confirmed", turn.plan.single().text)
        assertEquals(1, turn.steps.size)
        assertTrue(turn.steps.single().error)
    }
}
