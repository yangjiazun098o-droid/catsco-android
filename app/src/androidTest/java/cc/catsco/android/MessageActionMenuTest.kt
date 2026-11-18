package cc.catsco.android

import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.data.MessageTurn
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.MessageQuotePreview
import cc.catsco.android.ui.MessageTurnContent
import cc.catsco.core.MessageQuote
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class MessageActionMenuTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var quote by mutableStateOf<MessageQuote?>(null)
    private var regenerated = 0
    private var showTable = false
    private var responseId by mutableLongStateOf(42L)
    private lateinit var host: MessageReviewActivity

    private fun waitForHostFocus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitUntil(5_000) {
            var focused = false
            instrumentation.runOnMainSync { focused = host.hasWindowFocus() }
            focused
        }
    }
    private val paragraphs = listOf("这是一段用于检查消息操作的完整正文。".repeat(8), "## 结果\n\n完整尾部，复制时需要包含这一段。")
    private val turn = MessageTurn("reply", "9", "Agent", true, emptyList(), emptyList(), emptyList(), paragraphs, emptyList(), "completed", false, messageId = 42)

    private fun open(enabled: Boolean = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.setContent {
                CatsCoTheme {
                    Surface(Modifier.fillMaxSize().testTag("message-action-test-host")) {
                        Column(Modifier.statusBarsPadding().padding(16.dp)) {
                            val displayed = if (showTable) turn.copy(text = listOf(
                                "| 字段一 | 字段二 | 字段三 |\n| --- | --- | --- |\n| ${"较长内容".repeat(12)} | ${"较长内容".repeat(12)} | ${"较长内容".repeat(12)} |",
                            )) else turn
                            MessageTurnContent(displayed.copy(messageId = responseId), false, "Agent", false, true, {}, {},
                                onQuote = { quote = MessageQuote(it.messageId, it.senderName, it.text.joinToString("\n\n")) },
                                onRegenerate = { regenerated++ }, regenerateEnabled = enabled)
                            quote?.let { MessageQuotePreview(it) { quote = null } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        waitForHostFocus()
    }

    private fun swipeReply() {
        compose.onNodeWithTag("message-reply-42").performTouchInput {
            swipe(Offset(center.x + 160f, 80f), Offset(center.x - 100f, 80f), 300)
        }
        compose.onNodeWithTag("message-action-card").assertExists()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            compose.waitUntil(5_000) {
                var popupFocused = false
                instrumentation.runOnMainSync {
                    popupFocused = android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                        it !== host.window.decorView && it.hasWindowFocus()
                    }
                }
                popupFocused
            }
        }
    }

    @Test fun leftSwipeShowsThreeEqualRowsAndCopyIncludesEveryParagraph() {
        open()
        swipeReply()
        compose.onNodeWithTag("message-action-card").assertHeightIsEqualTo(156.dp)
        compose.onNodeWithText("引用").assertIsDisplayed()
        compose.onNodeWithText("重新生成").assertIsDisplayed()
        captureContextMenu("message-light")
        compose.onNodeWithText("复制全文").performClick()
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
        waitForHostFocus()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            assertEquals(paragraphs.joinToString("\n\n"), context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun quoteCanBeCancelledAndRegenerateInvokesOnce() {
        open()
        swipeReply()
        compose.onNodeWithText("引用").performClick()
        compose.onNodeWithContentDescription("取消引用").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(quote) }
        swipeReply()
        compose.onNodeWithText("重新生成").performClick()
        compose.runOnIdle { assertEquals(1, regenerated) }
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
    }

    @Test fun sendingDisablesRegenerationAndOutsideTapDismissesMenu() {
        open(enabled = false)
        swipeReply()
        compose.onNodeWithText("重新生成").assertIsNotEnabled()
        val height = compose.onNodeWithTag("message-action-test-host").fetchSemanticsNode().boundsInRoot.height
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = android.os.SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, 12f, height * .85f, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        waitForHostFocus()
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, regenerated) }
    }

    @Test fun draggingAcrossTextDoesNotOpenTheMenu() {
        open()
        compose.onNodeWithTag("message-reply-42").performTouchInput {
            swipe(Offset(center.x + 60f, 45f), Offset(center.x - 60f, 45f), 200)
        }
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
    }

    @Test fun tappingAndSwipingRightKeepTheMenuClosed() {
        open()
        compose.onNodeWithTag("message-reply-42").performTouchInput { click(Offset(center.x, 80f)) }
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
        compose.onNodeWithTag("message-reply-42").performTouchInput {
            swipe(Offset(center.x - 100f, 80f), Offset(center.x + 160f, 80f), 300)
        }
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
    }

    @Test fun tableHorizontalScrollingDoesNotOpenMessageActions() {
        showTable = true
        open()
        compose.onNodeWithTag("message-reply-42").performTouchInput {
            swipe(Offset(center.x + 160f, 140f), Offset(center.x - 100f, 140f), 300)
        }
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
    }

    @Test fun anUpdatedResponseKeepsItsSwipeActions() {
        open()
        swipeReply()
        compose.onNodeWithText("复制全文").performClick()
        compose.runOnIdle { responseId = 43L }
        compose.onNodeWithTag("message-reply-43").performTouchInput {
            swipe(Offset(center.x + 160f, 80f), Offset(center.x - 100f, 80f), 300)
        }
        compose.onNodeWithText("引用").assertIsDisplayed()
        compose.onNodeWithText("重新生成").performClick()
        compose.runOnIdle { assertEquals(1, regenerated) }
    }
}
