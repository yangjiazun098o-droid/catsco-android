package cc.catsco.android

import android.app.Application
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.ui.*
import cc.catsco.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/** Native screenshots use isolated preferences and a local, read-only repository. */
class TypographyReviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: MessageReviewActivity
    private lateinit var model: WorkspaceViewModel
    private var page by mutableStateOf("settings")
    private var dark by mutableStateOf(false)
    private var fontScale by mutableFloatStateOf(1f)
    private val layouts = JSONArray()
    private val session = Session(ServerAddress.parse("https://example.test"), "fixture-only", "type-review", "排版检查")
    private val workspace = Workspace(employees = listOf(
        Employee("9", "产品研究与资料整理助手", true, true, "处理产品研究和长文档整理", cloud = true),
        Employee("10", "第二位 Agent"),
    ))
    private val routes = listOf(
        "home", "settings", "profile", "appearance", "notifications", "surfaces", "offline", "feedback", "server",
        "commercial", "orders", "usage", "relay-key", "devices", "device-audit", "desktop-connect", "select-device/grp_1",
        "cloud-workers", "contacts", "people-search", "message-search", "agents", "agent/9", "model/9", "service-agents",
        "apps", "agent-apps/9", "skills", "skill/author/research", "agent-skills/9", "agent-prompt/9", "agent-knowledge/9",
        "files/9", "topic-files/grp_1",
    )

    private fun fixture(): JSONObject {
        val record = JSONObject().put("id", "9").put("uid", "9").put("skillId", "author/research")
            .put("display_name", "产品资料整理与分析").put("displayName", "产品资料整理与分析")
            .put("name", "产品资料整理与分析").put("username", "research-assistant")
            .put("description", "这是一段较长的说明，用于检查多行文字的间距和显示完整性。")
            .put("summary", "多行说明与辅助信息").put("state", "active").put("status", "active")
            .put("created_at", "2026-11-17T01:00:00Z").put("version", "1.0.0")
            .put("latestVersion", "1.0.0").put("contentHash", "a".repeat(64))
            .put("title", "产品研究报告与资料汇总.pdf").put("mime_type", "application/pdf")
            .put("is_owner", true).put("routable", true).put("order_no", "REVIEW-20261117")
            .put("amount_fen", 5990).put("currency", "CNY").put("remaining", "100%")
            .put("deviceName", "资料整理工作电脑").put("deviceId", "review-device")
        val records = JSONArray().put(record)
        return JSONObject().apply {
            listOf("devices", "events", "orders", "friends", "groups", "bots", "agents", "skills", "items", "results", "users", "workers", "files", "artifacts", "entitlements").forEach {
                put(it, JSONArray(records.toString()))
            }
            put("display_name", "排版检查").put("username", "type-review").put("email", "review@example.test")
            put("revision", 1).put("prompt", "## 工作方式\n\n先明确任务，再整理资料。\n\n- 核对输入\n- 给出结果")
            put("summary", JSONObject().put("model", "review-model").put("provider", "模型服务").put("quota_configured", true)
                .put("remaining_percent", 100).put("percent", 0))
            put("config", JSONObject().put("desired", JSONObject().put("selected", "local"))
                .put("applied", JSONObject().put("selected", "local")))
            put("skill", JSONObject(record.toString()).put("readme", "## 使用说明\n\n将资料整理为便于阅读的结构。"))
            put("definition", JSONObject().put("revision", 1).put("skills", JSONArray(records.toString()))
                .put("prompt", JSONObject().put("customSystemPrompt", "先明确用户目标，再处理任务。")))
        }
    }

    private fun show() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity")
            .use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            val application = object : Application() {
                private val prefix = "typography-${java.util.UUID.randomUUID()}"
                init { attachBaseContext(instrumentation.targetContext) }
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences("$prefix.$name", mode)
            }
            model = ViewModelProvider(host, ViewModelProvider.AndroidViewModelFactory(application))
                .get("typography-${java.util.UUID.randomUUID()}", WorkspaceViewModel::class.java)
            val repository = object : WorkspaceRepository by DebugEntry.repository() {
                override suspend fun resource(session: Session, path: String, query: Map<String, String>, method: String, body: String?): String {
                    check(method == "GET")
                    return fixture().toString()
                }
            }
            WorkspaceViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(model, repository)
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    CatsCoTheme(dark) {
                        SideEffect {
                            WindowCompat.getInsetsController(host.window, host.window.decorView).apply {
                                isAppearanceLightStatusBars = !dark
                                isAppearanceLightNavigationBars = !dark
                            }
                        }
                        val resource = if (page.startsWith("agent-knowledge/")) {
                            ResourceState(error = "知识库需要已连接的 Agent。", errorStatus = 403, loaded = true)
                        } else ResourceState(value = fixture(), loaded = true)
                        key(page) {
                            CatsCoApp(WorkspaceState(session = session, page = page, workspace = workspace, employeeId = "9", resource = resource), model)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(200)
        val texts = JSONArray()
        compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { action ->
                val results = mutableListOf<TextLayoutResult>()
                compose.runOnIdle { action(results) }
                results.forEach { result ->
                    assertEquals("字体遗漏: ${result.layoutInput.text.text.take(40)}", CatsCoFontFamily, result.layoutInput.style.fontFamily)
                    texts.put(JSONObject().put("text", result.layoutInput.text.text.take(100))
                        .put("size", result.layoutInput.style.fontSize.toString())
                        .put("lineHeight", result.layoutInput.style.lineHeight.toString())
                        .put("overflow", result.hasVisualOverflow))
                }
            }
        }
        layouts.put(JSONObject().put("screen", name).put("texts", texts))
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val directory = File(host.getExternalFilesDir(null), "typography-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(directory, "layouts.json").writeText(layouts.toString())
        File(directory, "$name.json").writeText(texts.toString())
    }

    private fun reviewRoutes(label: String) {
        routes.forEachIndexed { index, route ->
            compose.runOnIdle { page = route }
            compose.waitForIdle()
            val title = when (route) { "home" -> "新任务"; "settings" -> "设置"; "skills" -> "应用"; "agents" -> "员工"; else -> moduleTitle(route) }
            compose.onAllNodesWithText(title, useUnmergedTree = true).onFirst().assertIsDisplayed()
            capture("$label-${index.toString().padStart(2, '0')}")
            if (route in setOf("settings", "model/9", "agent/9", "relay-key", "commercial")) {
                compose.onRoot().performTouchInput { swipeUp() }
                capture("$label-${index.toString().padStart(2, '0')}-scrolled")
            }
        }
    }

    @Test fun allScreensRenderInLightAndDarkAppearance() {
        show()
        reviewRoutes("light")
        compose.runOnIdle { dark = true }
        reviewRoutes("dark")
    }

    @Test fun secondaryAndTertiaryScreensSupportLargerText() {
        fontScale = 1.3f
        show()
        reviewRoutes("large")
    }

    @Test fun choiceDialogsShareTheNativeTypographyRoles() {
        show()
        compose.runOnIdle { page = "files/9" }
        compose.onNodeWithText("Agent").performClick()
        compose.onAllNodesWithText("Agent", useUnmergedTree = true).onLast()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<TextLayoutResult>()
                assertTrue(action(results))
                assertEquals(20.sp, results.single().layoutInput.style.fontSize)
                assertEquals(28.sp, results.single().layoutInput.style.lineHeight)
            }
        capture("choice-dialog")
        compose.onNodeWithText("取消").performClick()
    }
}
