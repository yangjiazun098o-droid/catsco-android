package cc.catsco.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.data.MessageTurn
import cc.catsco.android.data.presentMessages
import cc.catsco.android.ui.*
import cc.catsco.core.ChatMessage
import cc.catsco.core.Conversation
import cc.catsco.core.Workspace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream
import kotlin.math.abs

class AgentActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: MessageReviewActivity
    private val turn = MessageTurn("reply", "9", "Agent", true, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", false)

    private fun show(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.setContent(content = content)
        }
        compose.waitForIdle()
    }

    @Test fun dotsExchangePlacesWithoutOverlappingAndReturnToTheirOriginalSlots() {
        assertEquals(exchangeDotPositions(0f), exchangeDotPositions(1f))
        for (frame in 0..640) {
            val points = exchangeDotPositions(frame / 640f)
            assertTrue(points.count { abs(it.y) > .001f } <= 1)
            for (left in 0..4) for (right in left + 1..4) {
                assertTrue((points[left] - points[right]).getDistance() > 4f)
            }
        }
        val midpoint = exchangeDotPositions(.32f / 3.2f)
        assertEquals(0f, midpoint[0].y, .001f)
        assertEquals(-6f, midpoint[1].y, .001f)
        val firstSwap = exchangeDotPositions(.48f / 3.2f)
        assertEquals(6f, firstSwap[0].x, .001f)
        assertEquals(0f, firstSwap[1].x, .001f)
    }

    @Test fun onlyCurrentRunningTasksGetActivityFeedback() {
        assertNull(agentActivityLabel(turn, false, false))
        assertEquals("正在思考", agentActivityLabel(turn, true, false))
        assertEquals("正在生成", agentActivityLabel(turn.copy(streaming = true), false, false))
        assertEquals("正在停止", agentActivityLabel(turn, true, true))
        listOf("completed", "failed", "stopped", "paused", "waiting_for_input").forEach { status ->
            assertNull(agentActivityLabel(turn.copy(executionState = status), true, false))
        }
        val workspace = Workspace(conversations = listOf(Conversation("grp_1", "Task", active = true)))
        assertFalse(WorkspaceState(workspace = workspace).conversationRunning)
        assertTrue(WorkspaceState(workspace = workspace, page = "chat/grp_1").conversationRunning)
        assertFalse(WorkspaceState(workspace = workspace, page = "chat/grp_2").conversationRunning)
        assertTrue(WorkspaceState(page = "chat/grp_2", stoppingTopic = "grp_2").conversationRunning)
        assertTrue(WorkspaceState(page = "chat/grp_2", messages = listOf(ChatMessage(0, "9", "Agent", "", streaming = true))).conversationRunning)
    }

    @Test fun waitingAndPersistedReplyShareOnePresentationSlot() {
        val user = turn.copy(key = "question", senderId = "1", assistant = false, text = listOf("问题"))
        val pending = conversationDisplayTurns(listOf(user), true, "9", "Agent")
        val stream = conversationDisplayTurns(listOf(user, turn.copy(key = "stream", streaming = true, text = listOf("回答"))), true, "9", "Agent")
        val persisted = conversationDisplayTurns(listOf(user, turn.copy(key = "saved", messageId = 42, text = listOf("回答"))), false, "9", "Agent")
        assertEquals(pending.last().key, stream.last().key)
        assertEquals(stream.last().key, persisted.last().key)
        assertEquals(0L, pending.last().messageId)
        val nextHuman = user.copy(key = "second-question", senderId = "2")
        val next = conversationDisplayTurns(listOf(user, turn, nextHuman), true, "9", "Agent")
        assertEquals(next.size, next.map { it.key }.distinct().size)
        assertNotEquals(pending.last().key, next.last().key)
    }

    @Test fun aFollowupKeepsTheExistingLoadingSlotUntilItsReplyArrives() {
        val user = turn.copy(key = "question", senderId = "1", assistant = false, messageId = 1, text = listOf("问题"))
        val followup = user.copy(key = "followup", messageId = 2, text = listOf("补充"))
        val pending = conversationDisplayTurns(listOf(user), true, "9", "Agent").last()
        val afterSend = conversationDisplayTurns(listOf(user, followup), true, "9", "Agent", followupMessageIds = setOf(2))
        assertEquals(listOf(user.key, pending.key, followup.key), afterSend.map { it.key })

        val reply = turn.copy(key = "first-stream", streaming = true, text = listOf("当前回复"))
        val afterReply = conversationDisplayTurns(listOf(user, followup, reply), true, "9", "Agent", followupMessageIds = setOf(2))
        assertEquals(listOf(user.key, followup.key, pending.key), afterReply.map { it.key })
        val afterAnotherFollowup = conversationDisplayTurns(listOf(user, reply, followup), true, "9", "Agent", followupMessageIds = setOf(2))
        assertEquals(listOf(user.key, pending.key, followup.key), afterAnotherFollowup.map { it.key })
        assertEquals(reply.text, afterAnotherFollowup.single { it.assistant }.text)
    }

    @Test fun explicitRunIdentityKeepsExecutionTogetherAcrossFollowups() {
        val first = ChatMessage(1, "1", "User", "问题")
        val progress = ChatMessage(2, "9", "Agent", "正在核对", kind = "assistant_text", turnId = "run-a")
        val followup = ChatMessage(3, "1", "User", "补充")
        val result = ChatMessage(4, "9", "Agent", "结果", turnId = "run-a")
        val next = ChatMessage(5, "9", "Agent", "处理补充内容", turnId = "run-b")
        val turns = presentMessages(listOf(first, progress, followup, result, next), "1", setOf("9"), followupMessageIds = setOf(3))

        assertEquals(listOf(1L, 4L, 3L, 5L), turns.map { it.messageId })
        assertEquals(listOf("正在核对"), turns[1].progress)
        assertEquals(listOf("结果"), turns[1].text)
        assertEquals(listOf("处理补充内容"), turns[3].text)
    }

    @Test fun bubbleOpensOnceFromItsTopLeftAndStreamingThenGrowsNaturally() {
        var loading by mutableStateOf(true)
        var bodyHeight by mutableStateOf(100)
        show {
            CatsCoTheme {
                Column(Modifier.statusBarsPadding().padding(16.dp).width(340.dp)) {
                    AgentMessageHeader("自迭代", "正在生成")
                    AgentReplyBubble(loading) {
                        if (!loading) Text("第一段正文", Modifier.fillMaxWidth().height(bodyHeight.dp))
                    }
                }
            }
        }
        val initial = compose.onNodeWithTag("agent-loading-bubble").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { loading = false }
        compose.mainClock.advanceTimeBy(80)
        val middle = compose.onNodeWithTag("agent-response-bubble").fetchSemanticsNode().boundsInRoot
        assertEquals(initial.left, middle.left, 1f)
        assertEquals(initial.top, middle.top, 1f)
        assertTrue(middle.width > initial.width)
        assertTrue(middle.width < with(compose.density) { 340.dp.toPx() })
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithTag("agent-response-bubble").assertWidthIsEqualTo(340.dp).assertHeightIsEqualTo(100.dp)
        compose.runOnIdle { bodyHeight = 180 }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("agent-response-bubble").assertHeightIsEqualTo(180.dp)
        compose.mainClock.autoAdvance = true
    }

    @Test fun timelineRetainsOpeningAnimationWhenTheFirstReplyArrives() {
        val user = turn.copy(key = "question", senderId = "1", assistant = false, text = listOf("问题"))
        var raw by mutableStateOf(listOf(user))
        show {
            CatsCoTheme {
                ConversationTimeline("morph", conversationDisplayTurns(raw, true, "9", "Agent"), "1", false, false, {},
                    Modifier.statusBarsPadding().width(340.dp).height(480.dp)) { entry ->
                    MessageTurnContent(entry, !entry.assistant, "Agent", entry.assistant, false, {}, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("agent-loading-bubble").assertWidthIsEqualTo(68.dp)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { raw = raw + turn.copy(key = "first-stream", streaming = true, text = listOf("第一段真实回复")) }
        compose.mainClock.advanceTimeBy(80)
        val width = compose.onNodeWithTag("agent-response-bubble").fetchSemanticsNode().boundsInRoot.width
        assertTrue(width > with(compose.density) { 68.dp.toPx() })
        assertTrue(width < with(compose.density) { (340 - 32).dp.toPx() })
        compose.mainClock.autoAdvance = true
    }

    @Test fun loadingBubbleSitsBelowNameAndStopKeepsTheDraft() {
        var running by mutableStateOf(true)
        var stopping by mutableStateOf(false)
        var stopped = 0
        var sent = 0
        show {
            CatsCoTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding().padding(16.dp)) {
                        if (running) AgentPendingReply("自迭代", stopping)
                        Spacer(Modifier.weight(1f))
                        Text("草稿保留")
                        ComposerSendButton(running, stopping, true, { sent++ }, { stopped++; stopping = true }, hasDraft = true)
                    }
                }
            }
        }
        val name = compose.onNodeWithText("自迭代").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("正在思考").assertDoesNotExist()
        compose.onNodeWithText("自迭代").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "正在思考")
        )
        val bubble = compose.onNodeWithTag("agent-loading-bubble")
        bubble.assertWidthIsEqualTo(68.dp).assertHeightIsEqualTo(42.dp)
        val bounds = bubble.fetchSemanticsNode().boundsInRoot
        assertEquals(name.left, bounds.left, 1f)
        assertTrue(bounds.top > name.bottom)
        compose.onNodeWithContentDescription("停止生成").performClick()
        compose.onNodeWithContentDescription("正在停止").assertIsNotEnabled()
        compose.onNodeWithTag("agent-loading-bubble").assertDoesNotExist()
        compose.onNodeWithText("草稿保留").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, stopped); assertEquals(0, sent); running = false; stopping = false }
        compose.onNodeWithContentDescription("发送任务").performClick()
        compose.runOnIdle { assertEquals(1, sent); assertEquals(1, stopped) }
    }

    @Test fun aCompletedResponseHasNoLoadingBubbleEvenBeforeRosterRefresh() {
        var completed by mutableStateOf(false)
        show {
            CatsCoTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding().padding(16.dp)) {
                        MessageTurnContent(if (completed) turn.copy(executionState = "completed", text = listOf("最终结果")) else turn,
                            false, "Agent", true, false, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("agent-loading-bubble").assertIsDisplayed()
        compose.runOnIdle { completed = true }
        compose.onNodeWithTag("agent-loading-bubble").assertDoesNotExist()
        // Markdown is a native TextView hosted by AndroidView, outside Compose's text bounds.
        fun containsVisibleResult(view: android.view.View): Boolean {
            if (view is android.widget.TextView && view.text.toString() == "最终结果") {
                return view.isShown && view.width > 0 && view.height > 0
            }
            return view is android.view.ViewGroup && (0 until view.childCount).any { containsVisibleResult(view.getChildAt(it)) }
        }
        compose.waitUntil(5_000) {
            var visible = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { visible = containsVisibleResult(host.window.decorView) }
            visible
        }
    }
}
