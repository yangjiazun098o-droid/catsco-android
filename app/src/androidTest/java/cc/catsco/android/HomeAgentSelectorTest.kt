package cc.catsco.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.ui.*
import cc.catsco.core.Employee
import cc.catsco.core.Workspace
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class HomeAgentSelectorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var selected by mutableStateOf("2")
    private var dark by mutableStateOf(false)
    private var visible by mutableStateOf(false)
    private var settings = ""
    private lateinit var host: MessageReviewActivity
    private val agents = (1..18).map { index -> Employee(index.toString(), when (index) {
        1 -> "云准助手"
        2 -> "官网演示 · 市场洞察助理"
        else -> "员工 $index"
    }, online = index % 2 == 1) }

    private fun show(fontScale: Float = 1f, compact: Boolean = false, avatarUrl: String = "", imageLoader: coil.ImageLoader? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity")
            .use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    CatsCoTheme(dark) {
                        SideEffect { WindowCompat.getInsetsController(host.window, host.window.decorView).isAppearanceLightStatusBars = !dark }
                        Surface(Modifier.fillMaxSize().then(Modifier.testTag(if (visible) "home-picker-open" else "home-picker-closed")),
                            color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    CatsCoHeaderAction(R.drawable.e_sidebar, "打开侧栏", decorated = false) {}
                                    Text("新任务", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                }
                                TaskHome(WorkspaceState(
                                    session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "测试用户"),
                                    workspace = Workspace(employees = agents.map { if (it.id == selected) it.copy(avatarUrl = avatarUrl) else it }), employeeId = selected),
                                    { selected = it }, if (compact) Modifier.height(220.dp) else Modifier.weight(1f),
                                    onEmployeeSettings = { settings = it }, imageLoader = imageLoader, onPickerVisibility = { visible = it })
                                Spacer(Modifier.height(76.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            var focused = false
            instrumentation.runOnMainSync { focused = host.hasWindowFocus() }
            focused
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(200)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "home-agent-review/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun pickerScrollsToLastEmployeeAndTapKeepsSettingsAction() {
        show()
        compose.onNodeWithText("需要为您做什么，测试用户？").assertIsDisplayed()
        compose.onNodeWithText("离线").assertDoesNotExist()
        capture("light-home")
        compose.onNodeWithTag("home-agent-card").performTouchInput { longClick() }
        compose.runOnIdle { assertTrue(visible); assertEquals("", settings) }
        compose.onNodeWithTag("agent-option-2").assertIsSelected()
        capture("light-picker")
        compose.onNodeWithTag("agent-picker-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("agent-option-2").assertIsNotDisplayed()
        compose.onNodeWithTag("agent-picker-list").performScrollToNode(hasTestTag("agent-option-18"))
        compose.onNodeWithTag("agent-option-18").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("18", selected); assertFalse(visible) }
        compose.onNodeWithTag("home-agent-card").performClick()
        compose.runOnIdle { assertEquals("18", settings) }
    }

    @Test fun backAndOutsideDismissWithoutChangingAgent() {
        show()
        compose.onNodeWithTag("home-agent-card").performTouchInput { longClick() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 29) compose.waitUntil(5_000) {
            var focused = false
            instrumentation.runOnMainSync {
                focused = android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it !== host.window.decorView && it.hasWindowFocus()
                }
            }
            focused
        }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(3_000) { !visible }
        compose.runOnIdle { assertEquals("2", selected) }
        compose.onNodeWithTag("home-agent-card").performTouchInput { longClick() }
        compose.onNodeWithTag("agent-picker-dismiss").performTouchInput { click(androidx.compose.ui.geometry.Offset(5f, height / 2f)) }
        compose.runOnIdle { assertFalse(visible); assertEquals("2", selected) }
    }

    @Test fun doubleFontScaleAndDarkThemeKeepOptionsReachable() {
        show(fontScale = 2f)
        compose.onNodeWithTag("home-agent-card").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("agent-picker-list").performScrollToNode(hasTestTag("agent-option-18"))
        compose.onNodeWithTag("agent-option-18").assertIsDisplayed()
        capture("large-type-picker")
        compose.onNodeWithTag("agent-option-18").performClick()
        compose.runOnIdle { dark = true }
        compose.onNodeWithTag("home-agent-card").performTouchInput { longClick() }
        capture("dark-picker")
        compose.onNodeWithTag("agent-option-18").assertIsSelected()
    }

    @Test fun compactHomeKeepsBothTapAndLongPress() {
        show(compact = true)
        compose.onNodeWithTag("home-agent-card").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("2", settings) }
        compose.onNodeWithTag("home-agent-card").performTouchInput { longClick() }
        compose.onNodeWithTag("agent-option-3").performClick()
        compose.runOnIdle { assertEquals("3", selected) }
    }

    @Test fun horizontalSwipesSelectNeighborsAndFollowPickerSelection() {
        show()
        val card = compose.onNodeWithTag("home-agent-card")
        card.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals("3", selected); assertFalse(visible); assertEquals("", settings) }
        card.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals("2", selected) }

        card.performTouchInput { longClick() }
        compose.onNodeWithTag("agent-picker-list").performScrollToNode(hasTestTag("agent-option-18"))
        compose.onNodeWithTag("agent-option-18").performClick()
        card.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals("18", selected); assertFalse(visible) }
        card.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals("17", selected) }
        card.performClick()
        compose.runOnIdle { assertEquals("17", settings) }
    }

    @Test fun homeDisplaysTheSelectedAgentsConfiguredAvatar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val avatarUrl = "https://images.example.test/configured-agent.png"
        val received = java.util.concurrent.atomic.AtomicReference<String>()
        val imageColor = android.graphics.Color.rgb(80, 130, 200)
        val images = coil.ImageLoader.Builder(context).components {
            add(coil.intercept.Interceptor { chain ->
                received.set(chain.request.data.toString())
                coil.request.SuccessResult(android.graphics.drawable.ColorDrawable(imageColor), chain.request, coil.decode.DataSource.MEMORY)
            })
        }.build()
        try {
            show(avatarUrl = avatarUrl, imageLoader = images)
            compose.waitUntil(5_000) { received.get() == avatarUrl }
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag("home-agent-card").captureToImage().toPixelMap()
            assertEquals(imageColor, pixels[pixels.width / 2, (pixels.height * .3f).toInt()].toArgb())
        } finally {
            images.shutdown()
        }
    }
}
