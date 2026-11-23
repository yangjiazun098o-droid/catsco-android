package cc.catsco.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.data.MessageTurn
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.ConversationTimeline
import cc.catsco.android.ui.MessageTurnContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class ConversationScrollTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var turns by mutableStateOf((1..24).map { turn(it, if (it % 2 == 0) "9" else "1") })
    private var viewport by mutableStateOf(480)
    private var topic by mutableStateOf("one")
    private var loading by mutableStateOf(false)
    private var hasMore by mutableStateOf(false)
    private var loadingOlder by mutableStateOf(false)
    private var olderLoadFailed by mutableStateOf(false)
    private var olderRequests = 0
    private var localSendRevision by mutableLongStateOf(0)
    private var nativeMarkdown = false
    private var targetMessageId by mutableStateOf(0L)

    private fun turn(id: Int, sender: String, lines: Int = 1) = MessageTurn(
        "$id", sender, "Agent", sender != "1", emptyList(), emptyList(), emptyList(),
        List(lines) { "内容 $id · $it" } + "消息 $id 结束", emptyList(), "", false,
    )

    private fun openTimeline(topPadding: Dp = 0.dp, onHeaderOverlap: ((Boolean) -> Unit)? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val host = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MessageReviewActivity>().single()
            host.setContent {
                CatsCoTheme {
                    Surface(Modifier.fillMaxSize().statusBarsPadding()) {
                        Box(Modifier.fillMaxSize()) {
                            ConversationTimeline(topic, turns, "1", loading, hasMore, {
                                olderRequests++
                                loading = true
                                loadingOlder = true
                                olderLoadFailed = false
                            }, Modifier.fillMaxWidth().height(viewport.dp), targetMessageId = targetMessageId,
                                loadingOlder = loadingOlder, olderLoadFailed = olderLoadFailed, localSendRevision = localSendRevision,
                                topContentPadding = topPadding, onMessagesBehindHeader = onHeaderOverlap) { entry ->
                                if (nativeMarkdown) {
                                    MessageTurnContent(entry, entry.senderId == "1", "Agent", false, true, {}, {})
                                } else Column(Modifier.fillMaxWidth().heightIn(min = 90.dp)) {
                                    entry.text.forEach { Text(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun headerOverlapOnlyStartsWhenMessagesScrollUnderTheTopBar() {
        turns = listOf(turn(1, "1"))
        var overlaps = false
        openTimeline(topPadding = 64.dp) { overlaps = it }
        compose.runOnIdle {
            assertEquals(false, overlaps)
            turns = (1..24).map { turn(it, if (it % 2 == 0) "9" else "1") }
        }
        compose.onNodeWithText("消息 24 结束").assertIsDisplayed()
        compose.runOnIdle { assertEquals(true, overlaps) }
    }

    @Test fun refreshingExistingMessagesKeepsTheReplyVisibleWithoutAnExtraProgressBar() {
        openTimeline()
        compose.onNodeWithText("消息 24 结束").assertIsDisplayed()
        val before = compose.onNodeWithText("消息 24 结束").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { loading = true }
        compose.onNodeWithText("消息 24 结束").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        val during = compose.onNodeWithText("消息 24 结束").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, during.top, 1f)
        compose.runOnIdle { loading = false }
        compose.onNodeWithText("消息 24 结束").assertIsDisplayed()
    }

    @Test fun searchTargetStartsAtTheMatchAndIncomingMessagesDoNotMoveIt() {
        turns = (1..24).map { turn(it, if (it % 2 == 0) "9" else "1").copy(messageId = it.toLong()) }
        targetMessageId = 4
        openTimeline()
        compose.onNodeWithText("消息 4 结束").assertIsDisplayed()
        compose.onNodeWithTag("conversation_latest").assertDoesNotExist()
        compose.runOnIdle { turns = turns + turn(25, "1").copy(messageId = 25) }
        compose.waitForIdle()
        compose.onNodeWithText("消息 4 结束").assertIsDisplayed()
        compose.onNodeWithText("消息 25 结束").assertDoesNotExist()
        compose.runOnIdle { targetMessageId = 0 }
        compose.waitForIdle()
        compose.onNodeWithText("消息 25 结束").assertIsDisplayed()
    }

    @Test fun draggingLongNativeMarkdownScrollsTheTimeline() {
        nativeMarkdown = true
        turns = listOf(turn(1, "1"), turn(2, "9").copy(text = listOf("连续正文用于检查消息区域的拖动手势。".repeat(150))))
        openTimeline()
        compose.onNodeWithTag("conversation_timeline").performTouchInput { swipeDown(durationMillis = 400) }
        compose.onNodeWithTag("conversation_latest").assertIsDisplayed()
        compose.onNodeWithTag("conversation_latest").performClick()
        compose.onNodeWithTag("conversation_latest").assertDoesNotExist()
    }

    @Test fun incomingReplyAndStreamingGrowthFollowBottomAcrossResize() {
        openTimeline()
        compose.onNodeWithText("消息 24 结束").assertIsDisplayed()
        compose.runOnIdle { turns = turns + turn(25, "1") + turn(26, "9", 35) }
        compose.onNodeWithText("消息 26 结束").assertIsDisplayed()
        compose.runOnIdle { turns = turns.dropLast(1) + turn(26, "9", 70) }
        compose.onNodeWithText("消息 26 结束").assertIsDisplayed()
        compose.runOnIdle { viewport = 280 }
        compose.onNodeWithText("消息 26 结束").assertIsDisplayed()
    }

    @Test fun historyStaysPutUntilLatestIsTappedOrUserSends() {
        openTimeline()
        compose.onNodeWithTag("conversation_timeline").performTouchInput { swipeDown(durationMillis = 400) }
        compose.onNodeWithTag("conversation_latest").assertIsDisplayed()
        val before = compose.onAllNodes(hasText("消息", substring = true)).fetchSemanticsNodes().first().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
        compose.runOnIdle { turns = turns + turn(25, "9") }
        compose.onNodeWithTag("conversation_latest").assertIsDisplayed()
        val after = compose.onAllNodes(hasText("消息", substring = true)).fetchSemanticsNodes().first().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
        assertEquals(before, after)
        compose.onNodeWithTag("conversation_latest").performClick()
        compose.onNodeWithText("消息 25 结束").assertIsDisplayed()
        compose.onNodeWithTag("conversation_timeline").performTouchInput { swipeDown(durationMillis = 400) }
        compose.runOnIdle { turns = turns + turn(26, "1") }
        compose.onNodeWithTag("conversation_latest").assertIsDisplayed()
        compose.onNodeWithText("消息 26 结束").assertDoesNotExist()
        compose.runOnIdle {
            localSendRevision++
            turns = turns + turn(27, "1")
        }
        compose.onNodeWithText("消息 27 结束").assertIsDisplayed()
    }

    @Test fun olderHistoryRetainsAnchorAndTopicSwitchStartsAtLatest() {
        openTimeline()
        compose.onNodeWithTag("conversation_timeline").performTouchInput { swipeDown(durationMillis = 400) }
        val before = compose.onAllNodes(hasText("消息", substring = true)).fetchSemanticsNodes().first().boundsInRoot
        compose.runOnIdle { turns = (-10..0).map { turn(it, "9") } + turns }
        val after = compose.onAllNodes(hasText("消息", substring = true)).fetchSemanticsNodes().first().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        compose.runOnIdle { topic = "two"; turns = listOf(turn(50, "1"), turn(51, "9", 40)) }
        compose.onNodeWithText("消息 51 结束").assertIsDisplayed()
    }

    @Test fun olderHistoryLoadsNearTheTopAndRetriesOnlyOnRequestAfterFailure() {
        hasMore = true
        openTimeline()
        compose.onNodeWithText("消息 24 结束").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, olderRequests) }

        val timeline = compose.onNodeWithTag("conversation_timeline")
        timeline.performTouchInput { swipeDown(durationMillis = 400) }
        timeline.performScrollToIndex(1)
        compose.onNodeWithText("正在加载更早的消息…").assertIsDisplayed()
        val anchor = compose.onNodeWithText("消息 2 结束")
        val before = anchor.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            assertEquals(1, olderRequests)
            loading = false
            loadingOlder = false
            olderLoadFailed = true
        }
        compose.onNodeWithText("加载失败，点此重试").performClick()
        compose.runOnIdle {
            assertEquals(2, olderRequests)
            turns = (-10..0).map { turn(it, "9") } + turns
            loading = false
            loadingOlder = false
        }

        assertEquals(before, anchor.fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { assertEquals(2, olderRequests) }
    }
}
