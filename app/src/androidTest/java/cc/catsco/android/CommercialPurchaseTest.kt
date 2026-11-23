package cc.catsco.android

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.data.*
import cc.catsco.android.ui.CatsCoApp
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.desktopPlanOverview
import cc.catsco.core.ServerAddress
import cc.catsco.core.Session
import cc.catsco.core.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

class CommercialPurchaseTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun topupRetriesLostResponseAndShowsFulfillmentOnlyAfterRefresh() = withFixture { model, fixture ->
        click("点数充值")
        see("充值点数余额")
        compose.onNodeWithText("确认充值").assertIsNotEnabled()
        click("¥10")
        see("1000 点")
        capture("topup")
        click("确认充值")
        see("确认订单")
        assertTrue(fixture.posted.isEmpty())
        capture("confirm")

        fixture.loseFirstResponse = true
        click("创建订单")
        see("连接失败，请检查网络后重试。")
        click("创建订单")
        see("待支付")
        assertEquals(2, fixture.posted.size)
        assertEquals(fixture.posted[0].getString("client_request_id"), fixture.posted[1].getString("client_request_id"))
        assertEquals("credit_topup", fixture.posted[1].getString("kind"))
        assertEquals(1000L, fixture.posted[1].getLong("amount_fen"))
        assertFalse(fixture.posted[1].has("plan_id"))
        compose.onNodeWithText("前往支付宝").assertIsEnabled()
        assertNull(model.state.value.openUrl)
        capture("pending")

        fixture.status = "paid"
        compose.runOnIdle {
            model.foreground(false)
            model.foreground(true)
        }
        see("点数入账中。", substring = true)
        compose.onNodeWithText("前往支付宝").assertDoesNotExist()
        fixture.status = "fulfilled"
        click("刷新订单状态")
        see("已到账")
    }

    @Test fun planUsesServerPriceAndAnExistingOrderCanBeCancelled() = withFixture { model, fixture ->
        click("购买套餐")
        see("当前套餐 · 生效中")
        see("协作套餐")
        capture("plans")
        click("选择套餐")
        see("确认订单")
        click("创建订单")
        see("待支付")
        val purchase = fixture.posted.single()
        assertEquals(2L, purchase.getLong("plan_id"))
        assertFalse(purchase.has("amount_fen"))
        assertTrue(compose.onAllNodesWithText("¥39").fetchSemanticsNodes().isNotEmpty())

        compose.runOnIdle { model.back() }
        see("协作套餐")
        click("选择套餐")
        click("创建订单")
        see("待支付")
        assertEquals(1, fixture.posted.size)
        click("取消订单")
        see("取消订单？")
        click("确认取消")
        see("已关闭")
        assertEquals(fixture.order.getString("order_no"), fixture.cancelledOrder)

        compose.runOnIdle { model.navigate("orders") }
        see("协作套餐")
        click("协作套餐")
        see("已关闭")
    }

    @Test fun desktopPlanIntroductionsAreVisibleInTheNativeCatalog() = withFixture { _, fixture ->
        fixture.desktopCatalog = true
        click("购买套餐")
        see("短文阅读与重点摘要")
        compose.onNodeWithText("短文阅读与重点摘要").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("数据分析与图表解读").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("9 折").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("最多 10 人").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("集中管理和共享知识库").performScrollTo().assertIsDisplayed()
        assertTrue(fixture.posted.isEmpty())
    }

    @Test fun mismatchedPlansDoNotInheritDesktopAllowanceClaims() {
        val plan = JSONObject().put("slug", "catsco-credit-v3-monthly").put("price_fen", 15900)
            .put("plan_tier", 3).put("duration_days", 30).put("currency", "CNY").put("model_credit_units", 22_500_000_000L)
        assertNotNull(desktopPlanOverview(plan))
        assertNull(desktopPlanOverview(plan.put("model_credit_units", 20_000_000_000L)))
        assertNull(desktopPlanOverview(plan.put("model_credit_units", 22_500_000_000L).put("price_fen", 19900)))
    }

    @Test fun amountsAreExactAndOnlyTheExpectedCheckoutCanOpen() {
        assertEquals(1029L, amountInFen("10.29"))
        assertNull(amountInFen("10.299"))
        assertNull(amountInFen("1e3"))
        assertNull(amountInFen("-10"))
        assertEquals("1029", topUpCreditAmount(1029L, 100L))
        val order = JSONObject().put("status", "pending").put("channel", AlipayCheckoutChannel)
            .put("expires_at", "2030-01-01T00:00:00Z")
        val now = Instant.parse("2028-01-01T00:00:00Z")
        fun checkout(url: String) = alipayCheckoutUrl(order.put("checkout_url", url), now)
        assertEquals("https://openapi.alipay.com/gateway.do", checkout("https://openapi.alipay.com/gateway.do"))
        assertNull(checkout("https://alipay.com.other.example/gateway.do"))
        assertNull(checkout("http://openapi.alipay.com/gateway.do"))
        assertNull(checkout("https://user@alipay.com/gateway.do"))
        assertNull(checkout("https://openapi.alipay.com:444/gateway.do"))
        assertNull(alipayCheckoutUrl(order.put("checkout_url", "https://openapi.alipay.com/")
            .put("expires_at", "2027-01-01T00:00:00Z"), now))
    }

    private fun see(label: String, substring: Boolean = false) {
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(label: String) {
        see(label)
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "commercial-review/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    private fun withFixture(block: (WorkspaceViewModel, Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        val prefix = "commercial-review-${UUID.randomUUID()}"
        val app = object : Application() {
            init { attachBaseContext(instrumentation.targetContext) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences("$prefix.$name", mode)
        }
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        val fixture = Fixture()
        lateinit var model: WorkspaceViewModel
        try {
            instrumentation.runOnMainSync {
                val host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MessageReviewActivity>().single()
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[WorkspaceViewModel::class.java]
                WorkspaceViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(model, fixture)
                @Suppress("UNCHECKED_CAST")
                val state = WorkspaceViewModel::class.java.getDeclaredField("mutable")
                    .apply { isAccessible = true }.get(model) as MutableStateFlow<WorkspaceState>
                state.value = WorkspaceState(session = Session(ServerAddress.parse("https://example.test"), "fixture", "101", "测试"),
                    preview = true)
                model.navigate("commercial")
                host.setContent {
                    val current by model.state.collectAsState()
                    CatsCoTheme { CatsCoApp(current, model) }
                }
            }
            block(model, fixture)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private class Fixture : WorkspaceRepository by DebugEntry.repository() {
        val posted = mutableListOf<JSONObject>()
        var order = JSONObject()
        @Volatile var status = "pending"
        @Volatile var desktopCatalog = false
        var loseFirstResponse = false
        var cancelledOrder = ""

        override suspend fun resource(session: Session, path: String, query: Map<String, String>, method: String, body: String?): String =
            when (path) {
                "/api/relay/commercial" -> """{"summary":{"entitlements":[{"plan_id":1,"plan_name":"基础套餐","state":"active"}]}}"""
                "/api/relay/commercial/catalog" -> if (desktopCatalog) """
                    {"enabled":true,"payment_configured":true,"channels":[{"id":"alipay_page"}],
                     "plans":[{"id":1,"slug":"catsco-credit-v1-monthly","name":"入门版","price_fen":3900,"currency":"CNY",
                                "duration_days":30,"plan_tier":1,"model_credit_units":4000000000},
                              {"id":2,"slug":"catsco-credit-v2-monthly","name":"日常版","price_fen":7900,"currency":"CNY",
                                "duration_days":30,"plan_tier":2,"model_credit_units":12000000000},
                              {"id":3,"slug":"catsco-credit-v3-monthly","name":"专业版","price_fen":15900,"currency":"CNY",
                                "duration_days":30,"plan_tier":3,"model_credit_units":22500000000},
                              {"id":4,"slug":"catsco-team","name":"团队版","price_fen":59900,"currency":"CNY",
                                "duration_days":30,"plan_tier":4}]}
                """.trimIndent() else """
                    {"enabled":true,"payment_configured":true,"channels":[{"id":"alipay_page"}],
                     "plans":[{"id":1,"name":"基础套餐","price_fen":1900,"currency":"CNY","duration_days":30,"plan_tier":1},
                              {"id":2,"name":"协作套餐","price_fen":3900,"currency":"CNY","duration_days":30,
                               "plan_tier":2,"description":"适合日常任务与多人协作。","model_credit_units":3900000000}],
                     "credit_topup":{"enabled":true,"min_amount_fen":100,"max_amount_fen":100000,
                                     "credits_per_cny":100,"presets_fen":[1000,5000,10000]}}
                """.trimIndent()
                "/api/relay/credits" -> """{"enabled":true,"units_per_credit":1000000,"paid_units":1250000000}"""
                CommercialOrdersPath -> {
                    if (method == "POST") {
                        val request = JSONObject(requireNotNull(body))
                        posted.add(request)
                        val topup = request.optString("kind") == "credit_topup"
                        val amount = if (topup) request.getLong("amount_fen") else 3900L
                        order = JSONObject().put("order_no", "CC-LOCAL-TEST")
                            .put("kind", if (topup) "credit_topup" else "plan").put("plan_id", request.optLong("plan_id"))
                            .put("plan_name", if (topup) "点数充值" else "协作套餐").put("amount_fen", amount)
                            .put("currency", "CNY").put("channel", AlipayCheckoutChannel)
                            .put("plan_model_credit_units", amount * 1_000_000)
                            .put("expires_at", Instant.now().plusSeconds(1200).toString())
                            .put("checkout_url", "https://openapi.alipay.com/gateway.do")
                        if (loseFirstResponse && posted.size == 1) throw IOException("fixture lost response after creating order")
                    }
                    val result = JSONObject(order.toString()).put("status", status)
                    if (method == "GET" && "order_no" !in query) JSONObject().put("orders", JSONArray(listOf(result))).toString()
                    else JSONObject().put("order", result).toString()
                }
                "$CommercialOrdersPath/cancel" -> {
                    cancelledOrder = JSONObject(requireNotNull(body)).getString("order_no")
                    status = "closed"
                    """{"ok":true}"""
                }
                else -> "{}"
            }
    }
}
