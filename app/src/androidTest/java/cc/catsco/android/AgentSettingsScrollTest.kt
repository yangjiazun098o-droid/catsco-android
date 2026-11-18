package cc.catsco.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.compose.ui.unit.dp
import cc.catsco.android.ui.AgentSettingsLayout
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.SettingsEntry
import cc.catsco.android.ui.SettingsGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

@OptIn(ExperimentalMaterial3Api::class)
class AgentSettingsScrollTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var dismissed = false
    private lateinit var sheet: SheetState

    private fun openPanel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val host = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MessageReviewActivity>().single()
            host.setContent { Panel() }
        }
        compose.waitForIdle()
    }

    @Composable private fun Panel() {
        CatsCoTheme {
            sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
            AgentSettingsLayout(sheet, { dismissed = true }) {
                Surface { Box(Modifier.fillMaxWidth().height(88.dp)) { Text("Agent") } }
                SettingsGroup("模型", listOf(
                    SettingsEntry("当前模型", value = "model", action = null),
                    SettingsEntry("剩余额度", value = "50%", action = null),
                    SettingsEntry("模型设置", R.drawable.e_settings),
                ), {})
                SettingsGroup("能力与工作空间", listOf(
                    SettingsEntry("云端文件", R.drawable.e_cloud),
                    SettingsEntry("系统提示词", R.drawable.e_menu),
                    SettingsEntry("知识库", R.drawable.e_file),
                    SettingsEntry("应用", R.drawable.e_apps),
                    SettingsEntry("管理员工", R.drawable.e_settings),
                    SettingsEntry("技能管理", R.drawable.e_sparkle),
                ), {})
                SettingsGroup("身份", listOf(
                    SettingsEntry("用户名", value = "agent", action = null),
                    SettingsEntry("员工 ID", value = "1", action = null),
                ), {})
            }
        }
    }

    @Test fun consecutiveUpwardGesturesReachIdentityWithoutDismissing() {
        openPanel()
        compose.onNode(hasScrollAction()).performTouchInput {
            repeat(6) {
                swipe(Offset(center.x, height - 40f), Offset(center.x, height * .2f), 120)
                advanceEventTime(25)
            }
        }
        compose.onNodeWithText("员工 ID").assertIsDisplayed()
        compose.onNodeWithText("用户名").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(dismissed)
            assertEquals(SheetValue.Expanded, sheet.currentValue)
        }
    }

    @Test fun identityIsReachableAgainAfterReversingDirection() {
        openPanel()
        compose.onNode(hasScrollAction()).performTouchInput {
            repeat(3) { swipeUp(durationMillis = 180) }
        }
        compose.onNodeWithText("员工 ID").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performTouchInput {
            swipeDown(durationMillis = 400)
            repeat(4) { swipeUp(durationMillis = 180) }
        }
        compose.onNodeWithText("员工 ID").assertIsDisplayed()
        compose.runOnIdle { assertFalse(dismissed) }
    }
}
