package cc.catsco.android

import android.app.UiAutomation
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

/** Optional signed-in integration check. Uses existing conversations and creates no messages. */
class ConversationLinkLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun cloudFilesDefaultToTheCurrentAgentAndSwitchWithoutAddingBackSteps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MainActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        var model: WorkspaceViewModel? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            model = ViewModelProvider(activity)[WorkspaceViewModel::class.java]
        }
        val workspace = requireNotNull(model)
        assumeTrue("Requires an existing signed-in account", workspace.state.value.session != null)
        compose.waitUntil(20_000) { !workspace.state.value.busy && workspace.state.value.workspace.employees.isNotEmpty() }
        val original = workspace.state.value.employeeId
        val agents = workspace.state.value.workspace.employees
        compose.runOnIdle { workspace.navigate("home"); workspace.navigate("files") }
        assertEquals("files/$original", workspace.state.value.page)
        if (agents.size > 1) {
            compose.runOnIdle { workspace.selectFileAgent(agents.first { it.id != original }.id) }
            assertNotEquals("files/$original", workspace.state.value.page)
        }
        compose.runOnIdle { workspace.back() }
        assertEquals("home", workspace.state.value.page)
        assertEquals(original, workspace.state.value.employeeId)
        val conversation = workspace.state.value.workspace.conversations.firstOrNull { chat -> agents.any { it.id == chat.agentId } }
        if (conversation != null) {
            compose.runOnIdle {
                workspace.openConversation(conversation.id, conversation.name)
                workspace.navigate("apps")
                workspace.navigate("files")
            }
            assertEquals("files/${conversation.agentId}", workspace.state.value.page)
            compose.runOnIdle { workspace.back() }
            assertEquals("apps", workspace.state.value.page)
            compose.runOnIdle { workspace.navigate("home") }
        }
    }

    @Test fun systemBackClosesAttachmentsBeforeLeavingHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MainActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        var model: WorkspaceViewModel? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            model = ViewModelProvider(activity)[WorkspaceViewModel::class.java]
        }
        val workspace = requireNotNull(model)
        assumeTrue("Requires an existing signed-in test account", workspace.state.value.session != null)
        compose.runOnIdle { workspace.navigate("home") }
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("拍照").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("拍照").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").assertIsDisplayed()
        assertEquals("home", workspace.state.value.page)
    }

    @Test fun systemBackDuringDrawerOpeningKeepsTheCurrentConversation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MainActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        var model: WorkspaceViewModel? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            model = ViewModelProvider(activity)[WorkspaceViewModel::class.java]
        }
        val workspace = requireNotNull(model)
        assumeTrue("Requires an existing signed-in account", workspace.state.value.session != null)
        compose.waitUntil(20_000) { !workspace.state.value.busy && workspace.state.value.workspace.conversations.isNotEmpty() }
        val conversation = workspace.state.value.workspace.conversations.first()
        compose.runOnIdle { workspace.openConversation(conversation.id, conversation.name) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        compose.mainClock.advanceTimeBy(32)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("chat/${conversation.id}", workspace.state.value.page)
        compose.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
    }

    @Test fun rotationDoesNotReopenAnAlreadyHandledLink() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        fun currentModel(): WorkspaceViewModel {
            var model: WorkspaceViewModel? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
                model = ViewModelProvider(activity)[WorkspaceViewModel::class.java]
            }
            return requireNotNull(model)
        }
        shell("am start -W -n cc.catsco.android.debug/cc.catsco.android.MainActivity")
        instrumentation.waitForIdleSync()
        val model = currentModel()
        assumeTrue("Requires an existing signed-in test account", model.state.value.session != null)
        compose.waitUntil(20_000) { !model.state.value.busy && model.state.value.workspace.conversations.size >= 2 }
        val conversations = model.state.value.workspace.conversations.take(2)
        val first = conversations[0]
        val second = conversations[1]
        compose.runOnIdle { model.navigate("home") }
        shell("am start -W -a android.intent.action.VIEW -d catsco://conversation/${first.id} -n cc.catsco.android.debug/cc.catsco.android.MainActivity")
        compose.waitUntil(10_000) { model.state.value.page == "chat/${first.id}" }
        compose.runOnIdle { model.openConversation(second.id, second.name) }
        try {
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            compose.waitUntil(10_000) {
                instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            compose.waitForIdle()
            assertEquals("chat/${second.id}", currentModel().state.value.page)
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            compose.waitUntil(10_000) {
                instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            }
            compose.waitForIdle()
            assertEquals("chat/${second.id}", currentModel().state.value.page)
        } finally {
            instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }
}
