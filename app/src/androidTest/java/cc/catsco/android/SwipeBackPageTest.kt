package cc.catsco.android

import androidx.activity.compose.setContent
import androidx.activity.BackEventCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.SwipeBackPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class SwipeBackPageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var page by mutableStateOf("previous")
    private var backs = 0
    private lateinit var host: MessageReviewActivity

    private fun show(capturePreview: Boolean = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.setContent {
                CatsCoTheme {
                    SwipeBackPage(page, "previous", page == "details", { backs++; page = "previous" },
                        captureEnabled = capturePreview) {
                        Column(Modifier.fillMaxSize().background(if (page == "previous") Color(0xFF168044) else Color(0xFF1845C0))) {
                            Text(page, Modifier.padding(24.dp), color = Color.White)
                            if (page == "previous") Button(onClick = { page = "details" }) { Text("打开详情") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        Thread.sleep(250)
        compose.onNodeWithText("打开详情").performTouchInput { click() }
        compose.onNodeWithText("details").assertIsDisplayed()
        // Allow the retained render layer to finish its asynchronous readback.
        compose.waitForIdle()
        Thread.sleep(250)
    }

    private fun assertPreviousPageVisible(name: String) {
        compose.waitForIdle()
        Thread.sleep(200)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val pixels = screenshot.asImageBitmap().toPixelMap()
        val behind = pixels[(pixels.width * .1f).toInt(), (pixels.height * .6f).toInt()]
        val front = pixels[(pixels.width * .85f).toInt(), (pixels.height * .6f).toInt()]
        captureContextMenu(name)
        assertTrue("Previous page color $behind", behind.green > behind.blue * 1.5f && behind.green > behind.red * 2)
        assertTrue("Current page color $front", front.blue > front.green * 2)
        screenshot.recycle()
    }

    @Test fun draggingRevealsPreviousPageBeforeCommittingAndCanReturnToTheCurrentPage() {
        show()
        compose.onRoot().performTouchInput {
            down(Offset(width * .12f, height * .6f))
            moveTo(Offset(width * .6f, height * .6f), 400)
        }
        compose.runOnIdle { assertEquals("details", page); assertEquals(0, backs) }
        assertPreviousPageVisible("swipe-back-held")
        compose.onRoot().performTouchInput {
            moveTo(Offset(width * .15f, height * .6f), 400)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("details", page); assertEquals(0, backs) }
        compose.onNodeWithTag("swipe-back-page").assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test fun fullDragCompletesOneBackAndQuickShortFlickAlsoCompletes() {
        show()
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .12f, height * .6f), Offset(width * .8f, height * .6f), 400)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("previous", page); assertEquals(1, backs) }
        compose.onNodeWithText("打开详情").performClick()
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .12f, height * .6f), Offset(width * .36f, height * .6f), 90)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("previous", page); assertEquals(2, backs) }
    }

    @Test fun uncachedDestinationDoesNotExposeBlankBackgroundDuringTheDrag() {
        show(capturePreview = false)
        compose.onRoot().performTouchInput {
            down(Offset(width * .12f, height * .6f))
            moveTo(Offset(width * .8f, height * .6f), 400)
        }
        compose.runOnIdle { assertEquals("details", page); assertEquals(0, backs) }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try {
            val pixels = screenshot.asImageBitmap().toPixelMap()
            val left = pixels[(pixels.width * .1f).toInt(), (pixels.height * .6f).toInt()]
            assertTrue("The outgoing page should still fill the left side: $left", left.blue > left.green * 2)
        } finally { screenshot.recycle() }
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertEquals("previous", page); assertEquals(1, backs) }
    }

    @Test fun shortSlowDragAndVerticalScrollDoNotNavigate() {
        show()
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .12f, height * .6f), Offset(width * .3f, height * .6f), 600)
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .5f, height * .7f), Offset(width * .5f, height * .3f), 300)
        }
        compose.runOnIdle { assertEquals("details", page); assertEquals(0, backs) }
        compose.onNodeWithTag("swipe-back-page").assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test fun predictiveSystemBackFollowsProgressAndHandlesCancellation() {
        show()
        compose.runOnIdle {
            host.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            host.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(400f, 600f, .5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("details", page); assertEquals(0, backs) }
        assertPreviousPageVisible("swipe-back-system-held")
        compose.runOnIdle { host.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("details", page); assertEquals(0, backs) }
        compose.onNodeWithTag("swipe-back-page").assertLeftPositionInRootIsEqualTo(0.dp)
        compose.runOnIdle {
            host.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 600f, 0f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            host.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(400f, 600f, .5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            host.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("previous", page); assertEquals(1, backs) }
    }
}
