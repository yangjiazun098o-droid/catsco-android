package cc.catsco.android

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.ui.CatsCoConversationActionMenu
import cc.catsco.android.ui.CatsCoMorphingMenu
import cc.catsco.android.ui.CatsCoMessageActionMenu
import cc.catsco.android.ui.CatsCoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream
import java.io.File

internal fun captureContextMenu(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
    val output = File(instrumentation.targetContext.getExternalFilesDir(null), "context-menu-review/$name.png")
    output.parentFile!!.mkdirs()
    output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}

/** Uses a standalone Compose activity, leaving the user's session untouched. */
class MenuLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun showMenu(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val host = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.setContent(content = content)
        }
        compose.waitForIdle()
    }

    @Test fun fourEqualRowsFillTheCardAndSelectWorks() {
        var expanded by mutableStateOf(true)
        var selected = false
        showMenu {
            CatsCoTheme {
                Box(Modifier.fillMaxSize().wrapContentSize(Alignment.Center)) {
                    CatsCoConversationActionMenu(expanded, false, { expanded = false }, {}, {}, { selected = true }, {})
                }
            }
        }
        compose.onNodeWithText("重命名").assertIsDisplayed()
        val rows = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
        assertEquals(4, rows.size)
        val height = with(compose.density) { 48.dp.toPx() }
        rows.forEach { assertEquals(height, it.height, 1f) }
        val card = compose.onNodeWithTag("conversation-action-card").fetchSemanticsNode().boundsInRoot
        val inset = with(compose.density) { 6.dp.toPx() }
        assertEquals(card.top + inset, rows.first().top, 1f)
        assertEquals(card.bottom - inset, rows.last().bottom, 1f)
        assertEquals(height * 4 + inset * 2, card.height, 1f)
        captureContextMenu("history-light")
        compose.onNodeWithText("多选").performClick()
        compose.runOnIdle { assertTrue(selected); assertFalse(expanded) }
    }

    @Test fun menuAtBottomRemainsVisibleAndBackDismisses() {
        var expanded by mutableStateOf(true)
        showMenu {
            CatsCoTheme(dark = true) {
                Box(Modifier.fillMaxSize().wrapContentSize(Alignment.BottomEnd).padding(16.dp)) {
                    CatsCoConversationActionMenu(expanded, false, { expanded = false }, {}, {}, {}, {})
                }
            }
        }
        listOf("重命名", "置顶", "多选", "删除").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        captureContextMenu("history-dark")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil { !expanded }
    }

    @Test fun capsuleActionsShareMenuTypographyAndRowsAndCloseAfterSelection() {
        var expanded by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var refreshed = false
        var newTasks = 0
        var computers = 0
        showMenu {
            CatsCoTheme(dark = dark) {
                CatsCoMorphingMenu(expanded, { expanded = it }, { computers++ }, { newTasks++ }, {}, { refreshed = true })
            }
        }
        val labels = listOf("我的电脑", "新建任务", "云端文件", "刷新")
        labels.forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed().assertHeightIsEqualTo(48.dp)
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue(action(results))
                    assertEquals(17.sp, results.single().layoutInput.style.fontSize)
                    assertEquals(24.sp, results.single().layoutInput.style.lineHeight)
                }
        }
        compose.onNodeWithContentDescription("更多操作菜单").assertHeightIsEqualTo(204.dp)
        compose.onNodeWithContentDescription("更多操作").assertDoesNotExist()
        captureContextMenu("capsule-light")
        compose.runOnIdle { dark = true }
        labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        captureContextMenu("capsule-dark")
        compose.onNodeWithText("我的电脑").performClick()
        compose.runOnIdle { assertEquals(1, computers); assertFalse(expanded); expanded = true }
        compose.onNodeWithText("刷新").performClick()
        compose.runOnIdle { assertTrue(refreshed); assertFalse(expanded) }
        compose.onNodeWithText("刷新").assertDoesNotExist()
        compose.onNodeWithContentDescription("新建任务").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, newTasks); assertEquals(1, computers) }
    }

    @Test fun largerMenuTextKeepsEveryActionOnOneCompleteLine() {
        var messageMenu by mutableStateOf(false)
        showMenu {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                CatsCoTheme {
                    if (messageMenu) {
                        Box(Modifier.fillMaxSize().wrapContentSize(Alignment.Center)) {
                            CatsCoMessageActionMenu(true, null, true, {}, {}, {}, {})
                        }
                    } else CatsCoMorphingMenu(true, {}, {}, {}, {}, {})
                }
            }
        }
        fun checkLabels(labels: List<String>) {
            labels.forEach { label ->
                compose.onNodeWithText(label).assertIsDisplayed()
                compose.onNodeWithText(label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                        val results = mutableListOf<TextLayoutResult>()
                        assertTrue(action(results))
                        val result = results.single()
                        assertEquals(2f, result.layoutInput.density.fontScale, 0f)
                        assertEquals(1, result.lineCount)
                        assertFalse(result.isLineEllipsized(0))
                        assertEquals(label.length, result.getLineEnd(0))
                        // Text layout rounds its floating-point glyph bounds to whole pixels.
                        assertTrue("Clipped width for $label", result.getLineRight(0) <= result.size.width + 1f)
                        assertTrue("Clipped height for $label", result.getLineBottom(0) <= result.size.height + 1f)
                    }
            }
        }
        captureContextMenu("capsule-large-type")
        checkLabels(listOf("我的电脑", "新建任务", "云端文件", "刷新"))
        compose.runOnIdle { messageMenu = true }
        checkLabels(listOf("引用", "复制全文", "重新生成"))
        captureContextMenu("message-large-type")
    }
}
