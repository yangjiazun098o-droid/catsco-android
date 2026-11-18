package cc.catsco.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.data.*
import cc.catsco.android.ui.*
import cc.catsco.core.Employee
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class ApplicationLibraryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var favorites by mutableStateOf(emptySet<String>())
    private var dark by mutableStateOf(false)
    private var resource by mutableStateOf(ResourceState())
    private var opened = ""
    private var shared = ""
    private var browser = ""
    private val agents = listOf(Employee("1", "自迭代", owner = true), Employee("2", "ty 的云"))
    private val apps = (1..18).map { index -> JSONObject()
        .put("id", "demo-$index").put("title", when (index) {
            1 -> "云端共享表格"
            2 -> "CatsCo 图片素材工作台"
            else -> "项目应用 $index"
        }).put("source_agent_id", if (index % 2 == 1) "1" else "2")
        .put("source_agent_name", if (index % 2 == 1) "自迭代" else "ty 的云")
        .put("can_manage", index % 2 == 1).put("updated_at", "2026-11-13T12:00:00Z")
        .put("url", "https://artifact.catsco.cc/app/demo-$index") }

    private fun show() {
        resource = ResourceState(loaded = true, value = JSONObject().put("apps", JSONArray(apps)))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity")
            .use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            host.setContent { CatsCoTheme(dark) {
                SideEffect { androidx.core.view.WindowCompat.getInsetsController(host.window, host.window.decorView).isAppearanceLightStatusBars = !dark }
                androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth()) {
                        CatsCoHeaderAction(R.drawable.e_sidebar, "打开侧栏", decorated = false) {}
                        androidx.compose.material3.Text("应用", Modifier.padding(top = 12.dp))
                    }
                    CatsCoLibraryTabs(false) {}
                    ApplicationsPanel(resource, agents, favorites, onRetry = {},
                        onFavorite = { app -> val key = applicationKey(app); favorites = if (key in favorites) favorites - key else favorites + key },
                        onOpen = { opened = it.first("id") }, onShare = { shared = it.first("id") },
                        onBrowser = { browser = it.first("id") })
                }
                }
            } }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(200)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "applications-review/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun filtersAreOrderedAndIntersectFavoritesWithAgent() {
        show()
        val left = compose.onNodeWithTag("application-scope").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("application-agent").fetchSemanticsNode().boundsInRoot
        assertTrue(left.top >= right.bottom)
        capture("light-list")
        compose.onNodeWithTag("application-more-demo-1").performClick()
        compose.onNode(hasText("收藏") and hasAnyAncestor(hasTestTag("application-action-menu")), useUnmergedTree = true).performClick()
        compose.onNodeWithText("收藏", useUnmergedTree = true).performClick()
        compose.onNodeWithText("云端共享表格").assertIsDisplayed()
        compose.onNodeWithText("CatsCo 图片素材工作台").assertDoesNotExist()
        compose.onNodeWithTag("application-agent").performClick()
        compose.onNodeWithText("ty 的云", useUnmergedTree = true).performClick()
        compose.onNodeWithText("还没有收藏的应用").assertIsDisplayed()
    }

    @Test fun actionsHaveEqualRowsAndInvokeTheSelectedApplication() {
        show()
        compose.onNodeWithTag("application-more-demo-1").performClick()
        val labels = listOf("打开", "收藏", "复制分享链接", "在浏览器中打开")
        val bounds = labels.map { compose.onNode(hasText(it) and hasAnyAncestor(hasTestTag("application-action-menu")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        val menu = compose.onNodeWithTag("application-action-menu").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds[1].center.y - bounds[0].center.y, bounds[3].center.y - bounds[2].center.y, 2f)
        assertEquals((4 * 48 + 12) * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density, menu.height, 2f)
        capture("light-menu")
        compose.onNodeWithText("复制分享链接").performClick()
        assertEquals("demo-1", shared)
        compose.onNodeWithTag("application-more-demo-2").performClick()
        compose.onNodeWithText("在浏览器中打开").performClick()
        assertEquals("demo-2", browser)
        compose.onNodeWithText("云端共享表格").performClick()
        assertEquals("demo-1", opened)
        compose.runOnIdle { dark = true }
        capture("dark-list")
    }

    @Test fun rightSwipeRevealsFavoriteAndSearchStaysAboveList() {
        show()
        compose.onNodeWithTag("application-row-demo-1").performTouchInput { swipeRight(startX = width * .3f, endX = width * .8f) }
        compose.onNodeWithTag("application-swipe-favorite").assertIsDisplayed().performClick()
        assertTrue(applicationKey(apps.first()) in favorites)
        compose.onNodeWithTag("application-list").performScrollToNode(hasText("项目应用 18"))
        compose.onNodeWithTag("application-list").performScrollToIndex(apps.lastIndex)
        val last = compose.onNodeWithText("项目应用 18").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("application-search").fetchSemanticsNode().boundsInRoot
        assertTrue("Search stays above the list", last.top > search.bottom)
        capture("list-bottom")
        compose.onNode(hasSetTextAction()).performTextInput("图片素材")
        compose.waitUntil(5_000) { compose.onNodeWithText("CatsCo 图片素材工作台").isDisplayed() }
        compose.onNodeWithText("CatsCo 图片素材工作台").assertIsDisplayed()
        compose.onNodeWithText("云端共享表格").assertDoesNotExist()
    }

    @Test fun failedAgentKeepsOtherCataloguesAndSharingNeverUsesLaunchCodes() = runBlocking {
        val result = loadSharedApplications(agents) { id ->
            if (id == "2") throw java.io.IOException("offline")
            JSONObject().put("apps", JSONArray(listOf(JSONObject().put("id", "a").put("title", "可用应用"))))
        }
        assertEquals(1, result.objects("apps").size)
        assertEquals("2", result.getJSONArray("failed_agents").getString(0))
        val app = JSONObject().put("url", "https://artifact.catsco.cc/app/public")
            .put("urls", JSONArray(listOf("https://artifact.catsco.cn/app/public")))
            .put("launch_url", "https://artifact.catsco.cc/launch?code=private")
        assertEquals("https://artifact.catsco.cn/app/public", applicationShareUrl(app, "https://app.catsco.cn"))
        assertEquals("", applicationShareUrl(JSONObject().put("launch_url", app.getString("launch_url")), "https://app.catsco.cc"))
        assertNotEquals(applicationKey(apps[0]), applicationKey(JSONObject(apps[0].toString()).put("source_agent_id", "2")))
    }
}
