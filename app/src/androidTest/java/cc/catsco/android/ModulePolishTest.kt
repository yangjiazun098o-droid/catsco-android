package cc.catsco.android

import android.app.Application
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.ui.*
import cc.catsco.core.ServerAddress
import cc.catsco.core.Session
import cc.catsco.core.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class ModulePolishTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun show(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.setContent { CatsCoTheme { Column(Modifier.fillMaxSize()) { content() } } }
        }
        compose.waitForIdle()
    }

    @Test fun floatingFieldLabelStaysInsideTheScrollableForm() {
        show {
            Column(Modifier.fillMaxWidth().height(180.dp).verticalScroll(rememberScrollState()).testTag("field-scroll")) {
                ModuleTextField("员工", {}, label = { Text("显示名称") }, singleLine = true)
            }
        }
        val label = compose.onNodeWithText("显示名称", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("field-scroll").getUnclippedBoundsInRoot()
        assertTrue("Floating labels need space above the field border", label.top >= viewport.top)
    }

    @Test fun refreshPreservesEditedFieldsAndUpdatesUntouchedFields() {
        var saved = emptyMap<String, String>()
        val draft = ModuleDraft(mapOf("name" to "旧名称", "description" to "旧说明"), emptyMap()) { saved = it }
        draft.update("name", "我的编辑")
        draft.reconcile(mapOf("name" to "服务端名称", "description" to "新说明"))
        assertEquals("我的编辑", draft["name"])
        assertEquals("新说明", draft["description"])
        assertTrue(draft.dirty)
        assertEquals("我的编辑", saved["name"])
        draft.discard()
        assertEquals("服务端名称", draft["name"])
        assertTrue(saved.isEmpty())
    }

    @Test fun saveCompletionRetainsEditsMadeDuringTheRequest() {
        var saved = emptyMap<String, String>()
        val draft = ModuleDraft(mapOf("name" to "原值"), emptyMap()) { saved = it }
        draft.update("name", "提交值")
        val submitted = draft.snapshot()
        draft.update("name", "后续输入")
        draft.accept(submitted)
        assertEquals("后续输入", draft["name"])
        assertTrue(draft.dirty)
        assertEquals("后续输入", saved["name"])
    }

    @Test fun successfulUnchangedSubmissionClearsSavedDraft() {
        var saved = mapOf("name" to "已编辑")
        val draft = ModuleDraft(mapOf("name" to "初始值"), saved) { saved = it }
        draft.accept(draft.snapshot())
        assertFalse(draft.dirty)
        assertTrue(saved.isEmpty())
    }

    @Test fun restorationIgnoresFieldsOutsideTheCurrentForm() {
        val draft = ModuleDraft(mapOf("name" to "当前值"), mapOf("name" to "草稿", "old" to "旧字段")) {}
        assertEquals(mapOf("name" to "草稿"), draft.snapshot())
    }

    @Test fun permissionErrorsOfferNavigationInsteadOfAnIneffectiveRetry() {
        var wentBack = false
        show { ModuleError("没有权限", 403, { fail("A permission error cannot retry here") }, { wentBack = true }) }
        compose.onNodeWithText("重新读取").assertDoesNotExist()
        compose.onNodeWithText("返回上一页").performClick()
        compose.runOnIdle { assertTrue(wentBack) }
    }

    @Test fun networkErrorsKeepAnExplicitRetry() {
        var retried = false
        show { ModuleError("网络暂不可用", null, { retried = true }, {}) }
        compose.onNodeWithText("网络暂不可用").assertIsDisplayed()
        compose.onNodeWithText("重新读取").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test fun backingOutOfInviteRestoresEntitlementsUntilRedemptionSucceeds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        val session = Session(ServerAddress.parse("https://example.test"), "fixture", "101", "测试")
        val prefix = "commercial-${java.util.UUID.randomUUID()}"
        val application = object : Application() {
            init { attachBaseContext(instrumentation.targetContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences("$prefix.$name", mode)
        }
        var requests = 0
        val repository = object : WorkspaceRepository by DebugEntry.repository() {
            override suspend fun resource(session: Session, path: String, query: Map<String, String>, method: String, body: String?): String =
                if (path == "/api/relay/commercial") JSONObject().put("credits", ++requests).toString() else "{}"
        }
        lateinit var model: WorkspaceViewModel
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[WorkspaceViewModel::class.java]
                WorkspaceViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(model, repository)
                @Suppress("UNCHECKED_CAST")
                val state = WorkspaceViewModel::class.java.getDeclaredField("mutable")
                    .apply { isAccessible = true }.get(model) as MutableStateFlow<WorkspaceState>
                state.value = WorkspaceState(session = session, preview = true)
                model.navigate("commercial")
            }
            val loaded = runBlocking { withTimeout(5_000) { model.state.first { it.resource.loaded } } }.resource
            instrumentation.runOnMainSync {
                model.navigate("redeem")
                model.back()
                assertSame(loaded.value, model.state.value.resource.value)
                assertFalse(model.state.value.resource.loading)
                assertEquals(1, requests)
                model.navigate("redeem")
                model.refreshEntitlementsAfterRedemption()
                model.back()
            }
            runBlocking { withTimeout(5_000) { model.state.first { it.resource.value.optInt("credits") == 2 } } }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    @Test fun skillPublishersUseReadableNamesFromBothSupportedFormats() {
        val author = JSONObject().put("name", "publisher-id").put("displayName", "发布者")
        assertEquals("发布者", skillPublisher(JSONObject().put("author", author)))
        assertEquals("旧版发布者", skillPublisher(JSONObject().put("author", "旧版发布者")))
        assertEquals("备用名称", skillPublisher(JSONObject().put("author", JSONObject()).put("owner_name", "备用名称")))
        assertEquals("", skillPublisher(JSONObject()))
    }

    @Test fun currencyUsesFenAndKeepsMissingAmountsUnknown() {
        assertEquals("¥59.9", orderAmount(JSONObject().put("amount_fen", 5990).put("currency", "CNY")))
        assertEquals("USD 12.5", orderAmount(JSONObject().put("amount_fen", 1250).put("currency", "USD")))
        assertEquals("", orderAmount(JSONObject().put("amount", 500)))
    }

    @Test fun datesIncludeTheYearAndUnknownStatusesRemainReadable() {
        assertTrue(displayModuleDate("2026-10-27T02:22:10.574763Z").startsWith("2026年"))
        assertEquals("2026年10月27日", displayModuleDate("2026-10-27", includeTime = false))
        assertEquals("已关闭", displayModuleStatus("closed"))
        assertEquals("待支付", displayOrderStatus("pending"))
        assertEquals("future-state", displayModuleStatus("future-state"))
    }

    @Test fun searchSnippetsUseReadableTextAndHighlightMatches() {
        assertEquals("重点 链接 code", searchSnippetText("**重点** [链接](https://example.com) `code`"))
        val highlighted = highlightedSnippet("Skill and skill", "skill", androidx.compose.ui.graphics.Color.Black)
        assertEquals(2, highlighted.spanStyles.size)
    }

    @Test fun capsuleCanReverseRepeatedlyAndDispatchesOneAction() {
        var expanded by mutableStateOf(false)
        var actions = 0
        show { CatsCoHeaderActionMenu(expanded, { expanded = it }, {}, { actions++ }, {}, {}) }
        compose.mainClock.autoAdvance = false
        repeat(4) {
            compose.runOnUiThread { expanded = !expanded }
            compose.mainClock.advanceTimeBy(40)
        }
        compose.runOnUiThread { expanded = true }
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("新建任务").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, actions); assertFalse(expanded) }
        compose.onNodeWithText("新建任务").assertDoesNotExist()
    }

    @Test fun knowledgeAppearanceUsesScopedNativeTokens() {
        val light = knowledgeAppearanceScript(false)
        assertTrue(light.contains("#f8f8f8"))
        assertTrue(light.contains(".cc-knowledge-wiki"))
        assertTrue(light.contains(".cc-knowledge-wiki-search input { --cc-field-surface:transparent"))
        assertTrue(light.contains("--cc-field-ink:#1f1f1f"))
        assertTrue(light.contains("应用更新"))
        assertTrue(knowledgeAppearanceScript(true).contains("#0f0f0f"))
    }
}
