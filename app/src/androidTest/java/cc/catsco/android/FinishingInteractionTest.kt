package cc.catsco.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.data.MessageTurn
import cc.catsco.android.data.VoiceCapture
import cc.catsco.android.data.VoiceCharacterLimit
import cc.catsco.android.data.VoiceDestination
import cc.catsco.android.data.takeVoiceCharacters
import cc.catsco.android.ui.*
import cc.catsco.core.Employee
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class FinishingInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: MessageReviewActivity
    private lateinit var drawer: DrawerState
    private val reply = MessageTurn("reply", "9", "Agent", true, emptyList(), emptyList(), emptyList(),
        listOf("这是用于检查正文手势的回复。".repeat(15)), emptyList(), "completed", false, messageId = 42)

    private val testApplication by lazy {
        object : android.app.Application() {
            private val prefix = "interaction-test-${java.util.UUID.randomUUID()}"
            init { attachBaseContext(InstrumentationRegistry.getInstrumentation().targetContext) }
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                baseContext.getSharedPreferences("$prefix.$name", mode)
        }
    }

    private fun isolatedModel(): WorkspaceViewModel =
        ViewModelProvider(host, ViewModelProvider.AndroidViewModelFactory(testApplication))[WorkspaceViewModel::class.java]

    private fun swipeOpenHomeSidebar() {
        compose.onNodeWithTag("swipe-back-page").performTouchInput {
            swipe(Offset(width * .03f, height * .48f), Offset(width * .72f, height * .48f))
        }
    }

    @Test fun sidebarHeaderShowsWordmarkAndClosesOverTheCurrentPage() {
        lateinit var model: WorkspaceViewModel
        show {
            model = remember { isolatedModel() }
            val state by model.state.collectAsState()
            CatsCoApp(state.copy(
                session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test"),
                resource = ResourceState(loaded = true),
            ), model)
        }
        compose.runOnIdle { model.navigate("chat/recent-task") }
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        compose.onNodeWithContentDescription("CatsCo").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回任务").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭侧栏").performClick()
        compose.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
        compose.runOnIdle { assertEquals("chat/recent-task", model.state.value.page) }
    }

    @Test fun shortDrawerCanScrollNavigationAwayAndOpenTaskActions() {
        val title = "横屏侧栏任务"
        show {
            val model = remember { isolatedModel() }
            Box(Modifier.height(340.dp)) {
                CatsCoApp(WorkspaceState(
                    session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test"),
                    workspace = cc.catsco.core.Workspace(conversations = listOf(cc.catsco.core.Conversation("short-drawer", title))),
                ), model)
            }
        }
        compose.onNodeWithContentDescription("打开侧栏").assertIsDisplayed().performClick()
        compose.onNodeWithTag("sidebar-history").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).assertIsDisplayed().performTouchInput { longClick() }
        listOf("重命名", "置顶", "多选", "从本机隐藏").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("重命名").performClick()
        val dialogTitle = compose.onNodeWithText("重命名会话").assertIsDisplayed()
        val input = compose.onNode(hasSetTextAction() and hasText(title)).assertIsDisplayed()
        val save = compose.onNodeWithText("保存").assertIsNotEnabled()
        val inputBounds = input.fetchSemanticsNode().boundsInRoot
        assertEquals(dialogTitle.fetchSemanticsNode().boundsInRoot.left, inputBounds.left, 1f)
        assertEquals(save.fetchSemanticsNode().boundsInRoot.right, inputBounds.right, 1f)
        input.performTextReplacement("修改后的任务名称")
        save.assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("关闭侧栏").performClick()
        compose.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
    }

    @Test fun hiddenTasksCanBeRestoredFromSettings() {
        val first = "待恢复任务一"
        val second = "待恢复任务二"
        val session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only",
            java.util.UUID.randomUUID().toString(), "Test")
        show {
            val model = remember { isolatedModel() }
            val state by model.state.collectAsState()
            CatsCoApp(state.copy(
                session = session,
                workspace = cc.catsco.core.Workspace(conversations = listOf(
                    cc.catsco.core.Conversation("restore-first", first),
                    cc.catsco.core.Conversation("restore-second", second),
                )),
                resource = ResourceState(loaded = true),
            ), model)
        }
        val history = compose.onNodeWithTag("sidebar-history")
        fun hide(title: String) {
            history.performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performTouchInput { longClick() }
            compose.onNodeWithText("从本机隐藏").performClick()
            compose.onNodeWithText("可以在「设置 → 应用偏好 → $HiddenTasksSettingsLabel」中恢复。").assertIsDisplayed()
            compose.onNodeWithText("隐藏").performClick()
            compose.onNodeWithText(title).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        hide(first)
        hide(second)
        compose.onNodeWithText(HiddenTasksSettingsLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription("打开设置").performClick()
        val settingsList = compose.onNode(hasScrollToIndexAction() and !hasTestTag("sidebar-history"))
        settingsList.performScrollToNode(hasText(HiddenTasksSettingsLabel))
        compose.onNodeWithText(HiddenTasksSettingsLabel).assertIsDisplayed().performClick()
        compose.onNode(hasText(first)).assertIsDisplayed()
        compose.onNode(hasText(second)).assertIsDisplayed()
        compose.onAllNodesWithText("恢复").onFirst().performClick()
        compose.onNodeWithText(first).assertIsNotDisplayed()
        compose.onNodeWithText(second).assertIsDisplayed()
        compose.onNodeWithText("全部恢复").performClick()
        compose.onNodeWithText(HiddenTasksSettingsLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭设置").performClick()
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        history.performScrollToNode(hasText(first))
        compose.onNodeWithText(first).assertIsDisplayed()
        history.performScrollToNode(hasText(second))
        compose.onNodeWithText(second).assertIsDisplayed()
    }

    @Test fun contactsSearchMatchesUsernamesAndKeepsGroupActionsAvailable() {
        lateinit var model: WorkspaceViewModel
        show {
            model = remember { isolatedModel() }
            val friends = org.json.JSONArray()
                .put(JSONObject().put("uid", 1).put("display_name", "设计同事").put("username", "design-member"))
                .put(JSONObject().put("uid", 2).put("display_name", "研发同事").put("username", "engineer"))
            (3..25).forEach { friends.put(JSONObject().put("uid", it).put("display_name", "测试联系人 $it")) }
            val value = JSONObject().put("friends", friends)
                .put("groups", org.json.JSONArray().put(JSONObject().put("id", 3).put("name", "项目讨论")))
            val session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test")
            CatsCoApp(WorkspaceState(session = session, page = "contacts", resource = ResourceState(value = value, loaded = true)), model)
        }
        val add = compose.onNodeWithContentDescription("添加联系人或群组")
        val addBounds = add.fetchSemanticsNode().boundsInRoot
        val backBounds = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText("联系人与群组").fetchSemanticsNode().boundsInRoot
        assertEquals(backBounds.center.y, addBounds.center.y, 1f)
        assertEquals((backBounds.center.x + addBounds.center.x) / 2, titleBounds.center.x, 1f)
        compose.onNodeWithText("设计同事").assertIsDisplayed()
        val search = compose.onNode(hasSetTextAction() and hasText("搜索联系人或群组"))
        val searchBounds = search.fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollToIndexAction() and !hasTestTag("sidebar-history")).performScrollToIndex(14)
        assertEquals(searchBounds, search.fetchSemanticsNode().boundsInRoot)
        search.performTextInput("engineer")
        compose.waitForIdle()
        captureContextMenu("contacts-search-keyboard")
        compose.onNodeWithText("研发同事").assertIsDisplayed()
        compose.onNodeWithText("设计同事").assertDoesNotExist()
        compose.onNodeWithContentDescription("清除搜索").performClick()
        search.performTextInput("不存在的联系人")
        compose.onNodeWithText("没有找到相关联系人或群组").assertIsDisplayed()
        add.performClick()
        compose.onNodeWithText("添加好友").assertIsDisplayed()
        compose.onNodeWithText("创建群组").performClick()
        val groupName = compose.onNode(hasSetTextAction() and hasText("群组名称"))
        groupName.performTextInput("本机群组草稿")
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("创建").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        add.performClick()
        compose.onNodeWithText("创建群组").performClick()
        compose.onNodeWithText("本机群组草稿").assertDoesNotExist()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOff()
        compose.onNodeWithText("创建").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        add.performClick()
        compose.onNodeWithText("添加好友").performClick()
        compose.runOnIdle { assertEquals("people-search", model.state.value.page) }
    }

    @Test fun openingSidebarClearsComposerFocusAndKeepsDraft() {
        val draft = "检查侧栏草稿"
        show {
            val model = remember { isolatedModel() }
            val session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test")
            CatsCoApp(WorkspaceState(session = session, draft = draft), model)
        }
        val composer = compose.onNode(hasSetTextAction() and hasText(draft))
        composer.performTouchInput { click() }
        composer.assertIsFocused()
        swipeOpenHomeSidebar()
        compose.onNodeWithText("账户与设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭侧栏").performClick()
        composer.assertIsDisplayed().assertIsNotFocused()
    }

    @Test fun blankTapDismissesComposerKeyboardAndKeepsTheDraftReadyToEdit() {
        val draft = "点空白处收起键盘"
        show {
            val model = remember { isolatedModel() }
            val session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test")
            CatsCoApp(WorkspaceState(session = session, draft = draft), model)
        }
        fun keyboardVisible(): Boolean {
            var visible = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            return visible
        }
        val composer = compose.onNode(hasSetTextAction() and hasText(draft))
        val greeting = compose.onNodeWithText("需要为您做什么，Test？")
        val greetingBounds = greeting.fetchSemanticsNode().boundsInRoot
        composer.performTouchInput { click() }
        compose.waitUntil(5000) { keyboardVisible() }
        assertEquals(greetingBounds, greeting.fetchSemanticsNode().boundsInRoot)
        val page = compose.onNodeWithTag("swipe-back-page")
        val inputTop = composer.fetchSemanticsNode().boundsInRoot.top - page.fetchSemanticsNode().boundsInRoot.top
        val gap = with(compose.density) { 54.dp.toPx() }
        page.performTouchInput {
            val blank = Offset(width * .15f, inputTop - gap)
            swipe(blank, Offset(blank.x, blank.y - gap), durationMillis = 250)
        }
        assertTrue(keyboardVisible())
        page.performTouchInput { click(Offset(width * .15f, inputTop - gap)) }
        compose.waitUntil(5000) { !keyboardVisible() }
        composer.assertIsDisplayed().assertIsFocused()

        composer.performTouchInput { click() }
        compose.waitUntil(5000) { keyboardVisible() }
        assertEquals(greetingBounds, greeting.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("添加附件").performTouchInput { click() }
        compose.waitUntil(5000) { !keyboardVisible() && compose.onNodeWithText("文件").isDisplayed() }
        compose.onNodeWithText("文件").assertIsDisplayed()
        assertFalse(keyboardVisible())
        assertEquals(greetingBounds, greeting.fetchSemanticsNode().boundsInRoot)
        composer.performTouchInput { click() }
        compose.waitUntil(5000) { keyboardVisible() && !compose.onNodeWithText("文件").isDisplayed() }
        compose.onNodeWithText("文件").assertDoesNotExist()
        composer.assertIsFocused()
        assertTrue(keyboardVisible())
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test fun composerInsetsFollowKeyboardAnimationAndReserveTheSharedAreaDuringResize() {
        val current = MutableWindowInsets(WindowInsets(bottom = 220))
        val source = MutableWindowInsets(WindowInsets(bottom = 0))
        val target = MutableWindowInsets(WindowInsets(bottom = 420))
        val insets = ComposerImeInsets(current, source, target)
        val density = androidx.compose.ui.unit.Density(1f)
        assertEquals(220, insets.getBottom(density))

        current.insets = WindowInsets(bottom = 380)
        source.insets = WindowInsets(bottom = 420)
        target.insets = WindowInsets(bottom = 510)
        assertEquals(420, insets.getBottom(density))

        source.insets = WindowInsets(bottom = 510)
        target.insets = WindowInsets(bottom = 0)
        current.insets = WindowInsets(bottom = 220)
        assertEquals(220, insets.getBottom(density))
        current.insets = WindowInsets(bottom = 0)
        assertEquals(0, insets.getBottom(density))
    }

    @Test fun employeeManagementShowsCollaboratorsAndKeepsInviteInItsDialog() {
        lateinit var model: WorkspaceViewModel
        val owned = JSONObject().put("uid", 9).put("display_name", "测试员工").put("is_owner", true)
        val collaborator = JSONObject().put("uid", 10).put("display_name", "邀请加入的员工").put("relation", "friend")
        val roster = JSONObject().put("bots", org.json.JSONArray().put(owned).put(collaborator))
        show {
            model = remember { isolatedModel() }
            val session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test")
            CatsCoApp(WorkspaceState(session = session, page = "agents", resource = ResourceState(value = roster, loaded = true)), model)
        }
        compose.onNodeWithText("我的员工").assertIsDisplayed()
        compose.onNodeWithText("协作员工").assertIsDisplayed()
        compose.onNodeWithText("员工邀请码").assertDoesNotExist()
        compose.onNodeWithText("添加员工").assertDoesNotExist()
        val add = compose.onNodeWithContentDescription("添加员工")
        val addBounds = add.fetchSemanticsNode().boundsInRoot
        val backBounds = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText("员工管理").fetchSemanticsNode().boundsInRoot
        assertEquals(backBounds.center.y, addBounds.center.y, 1f)
        assertEquals((backBounds.center.x + addBounds.center.x) / 2, titleBounds.center.x, 1f)
        add.performClick()
        compose.onNodeWithText("创建员工").assertIsDisplayed()
        compose.onNodeWithText("使用邀请码").performClick()
        compose.onNodeWithText("员工邀请码").assertIsDisplayed()
        compose.onNodeWithText("加入").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("邀请加入的员工").performClick()
        compose.runOnIdle { assertEquals("agent-access/10", model.state.value.page) }
        compose.onNodeWithText("测试员工").performClick()
        compose.runOnIdle { assertEquals("agent/9", model.state.value.page) }
        show {
            WorkspaceModuleScreen(WorkspaceState(page = "agent-access/10",
                resource = ResourceState(value = collaborator, loaded = true)), remember { isolatedModel() })
        }
        compose.onNodeWithText("邀请加入的员工").assertIsDisplayed()
        compose.onNodeWithText("编辑员工资料").assertDoesNotExist()
        compose.onNodeWithText("删除员工").assertDoesNotExist()
    }

    @Test fun personalizationChangesThemeAndIndependentBubbleColorsAndOpensWallpaper() {
        lateinit var model: WorkspaceViewModel
        var recreation by mutableIntStateOf(0)
        val session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "personalization-test", "Test")
        show {
            model = remember { isolatedModel() }
            val state by model.state.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current
            val fixture = remember(context) {
                object : android.content.ContextWrapper(context) {
                    private val prefix = "personalization-${java.util.UUID.randomUUID()}"
                    override fun getApplicationContext(): android.content.Context = this
                    override fun getNoBackupFilesDir() = java.io.File(context.cacheDir, prefix)
                    override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$prefix.$name", mode)
                }
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides fixture) {
                CatsCoTheme(dark = state.appearance == "dark") {
                    key(recreation) {
                        CatsCoApp(state.copy(session = session, resource = ResourceState(loaded = true)), model)
                    }
                }
            }
        }
        compose.runOnIdle { model.navigate("settings") }
        compose.onNodeWithText(PersonalizationTitle).performScrollTo().performClick()
        compose.onNodeWithText("我的气泡预览").assertIsDisplayed()
        compose.onNodeWithText("主题", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("深色").performClick()
        compose.runOnIdle { assertEquals("dark", model.state.value.appearance) }
        compose.onNodeWithText("我的气泡", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("雾蓝").performClick()
        compose.onNodeWithText("Agent 气泡", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("淡紫").performClick()
        compose.runOnIdle { recreation++ }
        compose.onNodeWithText("雾蓝").assertExists()
        compose.onNodeWithText("淡紫").assertExists()
        compose.onNodeWithText("会话壁纸").performScrollTo().performClick()
        compose.onNodeWithText("从相册选择").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals("appearance", model.state.value.page) }
        compose.onNodeWithText("Agent 气泡", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNode(hasText("默认") and hasClickAction() and !hasText("会话壁纸")).performClick()
        compose.onNodeWithText("淡紫").assertDoesNotExist()
        compose.onNodeWithText("雾蓝").assertExists()
    }

    @Test fun modelQuotaAndServiceDetailsShareOneCard() {
        show {
            ModelUsageCard(JSONObject().put("model", "test-model").put("provider", "shared")
                .put("status", "active").put("quota_configured", true).put("remaining_percent", 63.75382920398), "共享模型服务")
        }
        compose.onNodeWithText("63%").assertIsDisplayed()
        compose.onNodeWithText("已用额度").assertDoesNotExist()
        val disclosure = compose.onNodeWithText("模型服务详情")
        fun assertArrowPoints(expanded: Boolean) {
            val pixels = disclosure.captureToImage().toPixelMap()
            val iconSize = with(compose.density) { 23.dp.roundToPx() }
            val centerY = pixels.height / 2
            val background = pixels[pixels.width / 2, centerY]
            val points = buildList {
                for (x in pixels.width - iconSize until pixels.width) {
                    for (y in centerY - iconSize / 2 until centerY + iconSize / 2) {
                        val color = pixels[x, y]
                        if (kotlin.math.abs(color.red - background.red) > .32f) add(x to y)
                    }
                }
            }
            assertTrue(points.isNotEmpty())
            val tips = if (expanded) points.map { it.second } else points.map { it.first }
            val cross = if (expanded) points.map { it.first } else points.map { it.second }
            val first = tips.min()
            val last = tips.max()
            val span = (last - first) / 3
            val base = cross.filterIndexed { index, _ -> tips[index] <= first + span }
            val tip = cross.filterIndexed { index, _ -> tips[index] >= last - span }
            assertTrue((base.max() - base.min()) > (tip.max() - tip.min()))
        }
        fun rowBackground(): Color {
            val pixels = disclosure.captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }
        assertArrowPoints(expanded = false)
        val idleColor = rowBackground()
        disclosure.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300L)
        assertEquals(idleColor, rowBackground())
        disclosure.performTouchInput { up() }
        compose.onNodeWithText("服务来源").assertIsDisplayed()
        assertArrowPoints(expanded = true)
        assertEquals(idleColor, rowBackground())
        disclosure.performClick()
        compose.onNodeWithText("服务来源").assertDoesNotExist()
    }

    @Test fun conversationBubbleTextStaysTheSameWhenOnlyTheBackgroundChanges() {
        val userText = "用户正文"
        val agentText = "Agent 正文"
        var dark by mutableStateOf(false)
        var custom by mutableStateOf(false)
        var backgrounds = emptyList<Color>()
        show {
            CatsCoTheme(dark = dark) {
                val tones = if (custom) ConversationBubbleTones(BubbleTone.Blue, BubbleTone.Rose) else ConversationBubbleTones()
                CompositionLocalProvider(LocalConversationBubbleTones provides tones) {
                    val userBackground = conversationBubbleColor(mine = true)
                    val agentBackground = conversationBubbleColor(mine = false)
                    SideEffect { backgrounds = listOf(userBackground, agentBackground) }
                    Column {
                        MessageTurnContent(reply.copy(text = listOf(userText)), true, "", false, false, {}, {})
                        MessageTurnContent(reply.copy(text = listOf(agentText)), false, "Agent", false, false, {}, {})
                    }
                }
            }
        }
        fun foregrounds(): Pair<Int, Int> {
            val action = compose.onNodeWithText(userText, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!
            return compose.runOnIdle {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(action(layouts))
                val views = arrayListOf<android.view.View>()
                host.window.decorView.findViewsWithText(views, agentText, android.view.View.FIND_VIEWS_WITH_TEXT)
                layouts.single().layoutInput.style.color.toArgb() to views.filterIsInstance<MessageTextView>().single().currentTextColor
            }
        }
        val defaultForegrounds = mutableListOf<Pair<Int, Int>>()
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme; custom = false }
            val initial = foregrounds()
            val initialBackgrounds = compose.runOnIdle { backgrounds }
            compose.runOnIdle { custom = true }
            assertEquals(initial, foregrounds())
            compose.runOnIdle {
                assertTrue(initialBackgrounds.zip(backgrounds).all { (default, tinted) -> default != tinted })
            }
            defaultForegrounds += initial
        }
        assertNotEquals(defaultForegrounds.first().first, defaultForegrounds.last().first)
        assertNotEquals(defaultForegrounds.first().second, defaultForegrounds.last().second)
    }

    @Test fun computerConnectionOptionsExpandInPlace() {
        show {
            val model = remember { isolatedModel() }
            WorkspaceModuleScreen(WorkspaceState(page = "devices", resource = ResourceState(loaded = true)), model)
        }
        val heading = compose.onNodeWithText("电脑连接")
        val connection = compose.onNodeWithText("连接电脑")
        val audit = compose.onNodeWithText("连接记录")
        val cloud = compose.onNodeWithText("云托管")
        val generate = compose.onNodeWithText("生成连接码")
        generate.assertDoesNotExist()
        connection.performClick()
        heading.assertIsDisplayed()
        generate.assertIsDisplayed()
        connection.performClick()
        generate.assertDoesNotExist()
        audit.performClick()
        audit.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
        cloud.performClick()
        audit.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已收起"))
        cloud.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "已展开"))
        heading.assertIsDisplayed()
    }

    @Test fun projectSheetExpandsAndPinnedFiltersToggleTheDrawerHistory() {
        val extraProjects = (3..7).map { cc.catsco.core.Project(it.toString(), "移动端设计项目 $it") }
        show {
            val model = remember { isolatedModel() }
            val session = remember {
                cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"),
                    "test-only", java.util.UUID.randomUUID().toString(), "Test")
            }
            CatsCoApp(WorkspaceState(session = session, workspace = cc.catsco.core.Workspace(
                projects = listOf(cc.catsco.core.Project("1", "安卓项目"), cc.catsco.core.Project("2", "产品研究")) + extraProjects,
                conversations = listOf(
                    cc.catsco.core.Conversation("grp_10", "项目里的任务", task = true, projectId = "1"),
                    cc.catsco.core.Conversation("grp_11", "未归档的任务", task = true),
                    cc.catsco.core.Conversation("usr_12", "普通会话", projectId = "1"),
                ),
            )), model)
        }
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        val projectButton = compose.onNodeWithContentDescription("项目").assertIsDisplayed()
        val closeButton = compose.onNodeWithContentDescription("关闭侧栏").assertIsDisplayed()
        val projectBounds = projectButton.fetchSemanticsNode().boundsInRoot
        val closeBounds = closeButton.fetchSemanticsNode().boundsInRoot
        assertEquals(closeBounds.center.y, projectBounds.center.y, .5f)
        assertEquals(with(compose.density) { 40.dp.toPx() }, closeBounds.center.x - projectBounds.center.x, 1f)
        projectButton.performClick()
        val projectTitle = compose.onNodeWithText("项目")
        val addProject = compose.onNodeWithContentDescription("新建项目")
        val projectSorting = compose.onNodeWithText("按最近任务排列")
        fun expandProjects() {
            if (!projectSorting.isDisplayed()) projectTitle.performTouchInput {
                swipe(center, center - Offset(0f, height * 9f), durationMillis = 350)
            }
            projectSorting.assertIsDisplayed()
        }
        fun dismissProjects() {
            repeat(2) {
                if (!addProject.isDisplayed()) return
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4")
                    .use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
                compose.waitForIdle()
            }
            addProject.assertDoesNotExist()
        }
        compose.onNodeWithText("查看全部").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭项目").assertDoesNotExist()
        addProject.assertIsDisplayed().performClick()
        compose.onNodeWithText("项目名称").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("置顶 安卓项目").performClick()
        expandProjects()
        dismissProjects()
        val filter = compose.onNode(hasText("安卓项目") and isSelectable())
        val label = compose.onNode(hasText("安卓项目") and hasAnyAncestor(isSelectable()), useUnmergedTree = true)
        val filterBounds = filter.fetchSemanticsNode().boundsInRoot
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        assertEquals(filterBounds.center.x, labelBounds.center.x, 1f)
        assertEquals(filterBounds.center.y, labelBounds.center.y, 1f)
        assertEquals(with(compose.density) { 18.dp.toPx() }, filterBounds.width - labelBounds.width, 2f)
        filter.assertIsDisplayed().assertIsNotSelected().performTouchInput { click() }
        filter.assertIsSelected()
        compose.onNodeWithText("项目里的任务").assertIsDisplayed()
        compose.onAllNodesWithText("安卓项目").assertCountEquals(1)
        compose.onNodeWithText("未归档的任务").assertDoesNotExist()
        compose.onNodeWithText("普通会话").assertDoesNotExist()
        filter.performTouchInput { click() }
        compose.onNodeWithText("未归档的任务").assertIsDisplayed()

        projectButton.performClick()
        expandProjects()
        extraProjects.forEach { project ->
            compose.onNodeWithContentDescription("置顶 ${project.name}").performClick()
            compose.onNodeWithContentDescription("取消置顶 ${project.name}").assertExists()
        }
        dismissProjects()
        val strip = compose.onNodeWithTag("sidebar-project-filters")
        val searchIcon = compose.onNodeWithContentDescription("搜索", useUnmergedTree = true)
        val searchBounds = searchIcon.fetchSemanticsNode().boundsInRoot
        val stripBounds = strip.fetchSemanticsNode().boundsInRoot
        val searchIconInset = with(compose.density) { CatsCoMobileSpec.sidebarContentInset.toPx() }
        assertEquals(stripBounds.left, searchBounds.left - searchIconInset, 2f)
        assertEquals(stripBounds.left, filter.fetchSemanticsNode().boundsInRoot.left, 1f)
        repeat(3) { strip.performTouchInput { swipeLeft() } }
        compose.onNodeWithText(extraProjects.last().name).assertIsDisplayed()
        closeButton.assertIsDisplayed()
        compose.onNodeWithTag("sidebar-history").performTouchInput {
            swipe(Offset(width * .9f, height * .65f), Offset(width * .08f, height * .65f), durationMillis = 230)
        }
        closeButton.assertIsNotDisplayed()
    }

    @Test fun settingsAccountAndCloudHostingKeepTheirNavigationActions() {
        var opened = ""
        show {
            val model = remember { isolatedModel() }
            NativeSettings(WorkspaceState(session = cc.catsco.core.Session(
                cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "参考账号")),
                model.accountImages) { opened = it }
        }
        compose.onNodeWithText("参考账号").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("个人资料", opened) }
        compose.onNodeWithText("云托管").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("云托管", opened) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("退出登录"))
        compose.onNodeWithText("退出登录").assertIsDisplayed()
    }

    @Test fun longInformationValuesWrapWithoutLosingLabelsAtLargeTextScale() {
        val address = "https://example.test/a-long-service-address/with/additional/path/segments"
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.6f)) {
                Column(Modifier.width(330.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    ModuleSection("模型服务", spacing = 0.dp) {
                        ModuleInfoRows(listOf("服务地址" to address, "状态" to "生效中"))
                    }
                }
            }
        }
        compose.onNodeWithText("服务地址").assertIsDisplayed()
        compose.onNodeWithText(address).assertIsDisplayed()
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(address).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertFalse(layout.single().hasVisualOverflow)
        compose.onNodeWithText("生效中").performScrollTo().assertIsDisplayed()
    }

    @Test fun longTaskTitleAndHeaderActionsShareTheSameVerticalCenter() {
        val title = "健身的时候的无氧动作有没有更合适的安排与建议"
        show {
            val model = remember { isolatedModel() }
            CatsCoApp(WorkspaceState(
                session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test"),
                page = "chat/header-check",
                workspace = cc.catsco.core.Workspace(conversations = listOf(cc.catsco.core.Conversation("header-check", title))),
            ), model)
        }
        val center = compose.onNodeWithContentDescription("打开侧栏").fetchSemanticsNode().boundsInRoot.center.y
        val newTask = compose.onNodeWithContentDescription("新建任务").fetchSemanticsNode().boundsInRoot.center.y
        val more = compose.onAllNodesWithContentDescription("更多操作", useUnmergedTree = true).onLast()
            .fetchSemanticsNode().boundsInRoot.center.y
        val titleCenter = compose.onAllNodesWithText(title).onFirst().fetchSemanticsNode().boundsInRoot.center.y
        assertEquals(center, newTask, 1f)
        assertEquals(center, more, 1f)
        assertEquals(center, titleCenter, 1f)
        captureContextMenu("header-long-title-aligned")
    }

    @Test fun longNativeReplyKeepsTheComposerVisibleWhenPagePreviewsAreEnabled() {
        show {
            val model = remember { isolatedModel() }
            CatsCoApp(WorkspaceState(
                session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test"),
                page = "chat/reply-preview",
                workspace = cc.catsco.core.Workspace(employees = listOf(Employee("9", "Agent")),
                    conversations = listOf(cc.catsco.core.Conversation("reply-preview", "长回复绘制检查", agentId = "9"))),
                messages = listOf(cc.catsco.core.ChatMessage(42, "9", "Agent", "这是一段检查原生消息绘制和输入栏遮挡的长回复。".repeat(60))),
                connection = "已连接",
            ), model)
        }
        val send = compose.onNodeWithContentDescription("语音输入").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.waitForIdle()
        Thread.sleep(250)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val color = screenshot.asImageBitmap().toPixelMap()[(send.center.x - send.width * .2f).toInt(), send.center.y.toInt()]
        captureContextMenu("native-reply-composer-visible")
        assertTrue("The send button must remain painted above the native reply, color=$color",
            color.green > color.red * 1.15f && color.green > color.blue * 1.03f)
        screenshot.recycle()
    }

    @Test fun secondaryPageRightSwipeReturnsToPreviousPageWithoutOpeningTheDrawer() {
        lateinit var model: WorkspaceViewModel
        show {
            model = remember { isolatedModel() }
            val state by model.state.collectAsState()
            CatsCoApp(state.copy(
                session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test"),
                resource = ResourceState(loaded = true),
            ), model)
        }
        compose.runOnIdle { model.navigate("appearance"); model.navigate("devices") }
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .5f, height * .7f), Offset(width * .5f, height * .35f), 350)
        }
        compose.runOnIdle { assertEquals("devices", model.state.value.page) }
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .25f, height * .5f), Offset(width * .85f, height * .5f), 350)
        }
        compose.runOnIdle { assertEquals("appearance", model.state.value.page) }
        compose.onNodeWithText("搜索历史任务").assertIsNotDisplayed()
    }

    @Test fun settingsSwipeBackPreservesExpandedComposerAndHomeLayout() {
        lateinit var model: WorkspaceViewModel
        show {
            model = remember { isolatedModel() }
            val state by model.state.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current
            // This pixel check samples the default background rather than the device's saved wallpaper.
            val fixtureContext = remember(context) {
                object : android.content.ContextWrapper(context) {
                    private val localFiles = java.io.File(context.cacheDir, "swipe-preview-${java.util.UUID.randomUUID()}")
                    override fun getApplicationContext(): android.content.Context = this
                    override fun getNoBackupFilesDir(): java.io.File = localFiles
                }
            }
            CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides fixtureContext) {
                CatsCoApp(state.copy(
                    session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test"),
                    resource = ResourceState(loaded = true),
                ), model)
            }
        }
        val greeting = compose.onNodeWithText("需要为您做什么，Test？")
        val before = greeting.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("拍照").assertIsDisplayed()
        assertEquals(before, greeting.fetchSemanticsNode().boundsInRoot)
        compose.waitForIdle()
        Thread.sleep(200)
        val photoAction = compose.onNode(hasClickAction() and hasText("照片")).fetchSemanticsNode()
        val photoLabel = compose.onNodeWithText("照片", useUnmergedTree = true).fetchSemanticsNode()
        val iconArea = compose.runOnIdle {
            val cell = photoAction.layoutInfo.coordinates
            val topLeft = cell.localToScreen(Offset.Zero)
            val labelTop = photoLabel.layoutInfo.coordinates.localToScreen(Offset.Zero).y
            android.graphics.Rect((topLeft.x + cell.size.width * .2f).toInt(), topLeft.y.toInt(),
                (topLeft.x + cell.size.width * .8f).toInt(), labelTop.toInt())
        }
        val reference = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val reviewFiles = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "context-menu-review")
            .apply { mkdirs() }
        fun saveFrame(bitmap: android.graphics.Bitmap, name: String) {
            java.io.File(reviewFiles, "settings-return-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val referencePixels = reference.asImageBitmap().toPixelMap()
        fun silhouette(area: android.graphics.Rect) = buildList {
            for (y in area.top until area.bottom step 2) {
                for (x in area.left until area.right step 2) {
                    if (referencePixels[x, y].red < .65f) add(x to y)
                }
            }
        }
        val ink = silhouette(iconArea)
        val heading = greeting.fetchSemanticsNode().layoutInfo.coordinates
        val headingArea = compose.runOnIdle {
            val position = heading.localToScreen(Offset.Zero)
            android.graphics.Rect(position.x.toInt(), position.y.toInt(),
                (position.x + heading.size.width / 2).toInt(), (position.y + heading.size.height).toInt())
        }
        val headingInk = silhouette(headingArea)
        assertTrue("Reference photo pictogram must be painted within $iconArea", ink.size >= 8)
        assertTrue("Reference greeting must be painted within $headingArea", headingInk.size >= 8)
        saveFrame(reference, "expanded-reference")
        reference.recycle()
        fun assertAttachmentPreview(expanded: Boolean) {
            compose.waitForIdle()
            var best = 0f
            // Compose may be idle before SurfaceFlinger presents the dragged frame.
            // Find the home heading first, then inspect the tray on that same frame.
            compose.waitUntil(timeoutMillis = 5_000) {
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
                try {
                    val pixels = bitmap.asImageBitmap().toPixelMap()
                    val shifts = maxOf(-(.16f * bitmap.width).toInt(), -headingArea.left)..0
                    fun overlap(points: List<Pair<Int, Int>>, shift: Int, dy: Int = 0): Float =
                        points.count { (x, y) -> pixels[x + shift, y + dy].red < .75f }.toFloat() / points.size
                    val shift = shifts.maxBy { overlap(headingInk, it) }
                    if (overlap(headingInk, shift) < .90f) return@waitUntil false
                    best = (-1..1).maxOf { dy -> overlap(ink, shift, dy) }
                    saveFrame(bitmap, if (expanded) "expanded-preview" else "collapsed-preview")
                    true
                } finally {
                    bitmap.recycle()
                }
            }
            if (expanded) assertTrue("Expanded preview must include the photo pictogram: overlap=$best area=$iconArea", best >= .80f)
            else assertTrue("Collapsed preview must remove the photo pictogram: overlap=$best", best <= .20f)
        }
        swipeOpenHomeSidebar()
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.runOnIdle { assertEquals("settings", model.state.value.page) }
        compose.onRoot().performTouchInput {
            down(Offset(width * .12f, height * .6f))
            moveTo(Offset(width * .9f, height * .6f), 500)
        }
        assertAttachmentPreview(expanded = true)
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertEquals("home", model.state.value.page) }
        compose.onNodeWithContentDescription("收起附件").assertIsDisplayed()
        compose.onNodeWithText("拍照").assertIsDisplayed()
        assertEquals(before, greeting.fetchSemanticsNode().boundsInRoot)
        // A deliberate collapse must also survive the next return.
        compose.onNodeWithContentDescription("收起附件").performClick()
        assertEquals(before, greeting.fetchSemanticsNode().boundsInRoot)
        compose.waitForIdle()
        compose.onNodeWithText("拍照").assertDoesNotExist()
        val collapsed = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        try {
            saveFrame(collapsed, "collapsed-reference")
            val pixels = collapsed.asImageBitmap().toPixelMap()
            val visible = ink.count { (x, y) -> pixels[x, y].red < .75f }.toFloat() / ink.size
            assertTrue("The live home must finish collapsing before navigating: overlap=$visible", visible <= .20f)
        } finally {
            collapsed.recycle()
        }
        swipeOpenHomeSidebar()
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onRoot().performTouchInput {
            down(Offset(width * .12f, height * .6f))
            moveTo(Offset(width * .9f, height * .6f), 500)
        }
        assertAttachmentPreview(expanded = false)
        compose.onRoot().performTouchInput { up() }
        compose.onNodeWithContentDescription("添加附件").assertIsDisplayed()
        compose.onNodeWithText("拍照").assertDoesNotExist()
    }

    @Test fun operationFailureDoesNotAddAPersistentPageBanner() {
        show {
            val model = remember { isolatedModel() }
            CatsCoApp(WorkspaceState(
                session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test"),
                error = "请求失败（400），请稍后重试。",
            ), model)
        }
        compose.onNodeWithText("暂时无法完成操作，请重试。").assertIsDisplayed()
        compose.onNodeWithText("请求失败（400），请稍后重试。").assertDoesNotExist()
        compose.onNodeWithText("刷新").assertDoesNotExist()
        compose.onNodeWithText("需要为您做什么，Test？").assertIsDisplayed()
    }

    private fun show(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            host.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            host.setContent { CatsCoTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) { content() } } }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun showReply(table: Boolean = false) {
        show {
            drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(drawerState = drawer, drawerContent = {
                ModalDrawerSheet { Text("任务侧栏", Modifier.padding(24.dp)) }
            }) {
                CompositionLocalProvider(LocalOpenSidebar provides { scope.launch { drawer.open() }; Unit }) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        val turn = if (table) reply.copy(text = listOf(
                            "| 第一列 | 第二列 | 第三列 |\n| --- | --- | --- |\n| ${"横向内容".repeat(12)} | ${"横向内容".repeat(12)} | ${"横向内容".repeat(12)} |",
                        )) else reply
                        MessageTurnContent(turn, false, "Agent", false, true, {}, {}, onQuote = {}, onRegenerate = {})
                    }
                }
            }
        }
    }

    @Test fun rightSwipeInsideNativeReplyTextOpensTheDrawer() {
        showReply()
        compose.onNodeWithTag("message-reply-42").performTouchInput {
            swipe(Offset(width * .25f, 80f), Offset(width * .8f, 80f), 350)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(drawer.isOpen) }
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
    }

    @Test fun leftSwipeInsideNativeReplyTextStillOpensMessageActions() {
        showReply()
        compose.onNodeWithTag("message-reply-42").performTouchInput {
            swipe(Offset(width * .8f, 80f), Offset(width * .25f, 80f), 350)
        }
        compose.onNodeWithTag("message-action-card").assertIsDisplayed()
        compose.runOnIdle { assertTrue(drawer.isClosed) }
    }

    @Test fun horizontalTableScrollKeepsTheDrawerAndMessageActionsClosed() {
        showReply(table = true)
        compose.onNodeWithTag("message-reply-42").performTouchInput {
            swipe(Offset(width * .8f, 140f), Offset(width * .25f, 140f), 350)
        }
        compose.onNodeWithTag("message-reply-42").performTouchInput {
            swipe(Offset(width * .25f, 140f), Offset(width * .8f, 140f), 350)
        }
        compose.onNodeWithTag("message-action-card").assertDoesNotExist()
        compose.runOnIdle { assertTrue(drawer.isClosed) }
    }

    @Test fun requiredFieldExplainsTheErrorAfterLeavingItAndClearsAfterCorrection() {
        show {
            var name by remember { mutableStateOf("") }
            Column {
                ModuleTextField(name, { name = it }, Modifier.testTag("required-name"), label = { Text("名称") },
                    errorMessage = requiredFieldError(name, "名称"))
                ModuleTextField("", {}, Modifier.testTag("next-field"))
            }
        }
        compose.onNodeWithText("请填写名称").assertDoesNotExist()
        compose.onNodeWithTag("required-name").performClick()
        compose.onNodeWithTag("next-field").performClick()
        compose.onNodeWithText("请填写名称").assertIsDisplayed()
        compose.onNodeWithTag("required-name").performTextInput("有效名称")
        compose.onNodeWithTag("next-field").performClick()
        compose.onNodeWithText("请填写名称").assertDoesNotExist()
    }

    @Test fun homeAgentTapOpensSettingsAndLongPressSelectsAnotherAgent() {
        var opened = ""
        var selected by mutableStateOf("2")
        show {
            Column {
                CatsCoHomeAgent(listOf(Employee("1", "第一位"), Employee("2", "第二位"), Employee("3", "第三位")), selected,
                    { selected = it }, onSettings = { opened = it })
            }
        }
        compose.onNodeWithTag("home-agent-card").performClick()
        compose.runOnIdle { assertEquals("2", opened); assertEquals("2", selected) }
        compose.onNodeWithTag("home-agent-card").performTouchInput { longClick() }
        compose.onNodeWithTag("agent-option-2").assertIsSelected()
        compose.onNodeWithTag("agent-option-3").performClick()
        compose.runOnIdle { assertEquals("3", selected); assertEquals("2", opened) }
        compose.onNodeWithTag("agent-picker-menu").assertDoesNotExist()
    }

    @Test fun emptyComposerUsesOneVoiceButtonAndTextOrAttachmentsEnableSending() {
        lateinit var model: WorkspaceViewModel
        var attachments by mutableStateOf(emptyList<cc.catsco.core.Attachment>())
        var voices = 0
        var sends = 0
        show {
            model = remember { isolatedModel() }
            val state by model.state.collectAsState()
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                Composer(state.copy(employeeId = "9", attachments = attachments), model, {}, { voices++ }, { sends++ })
            }
        }
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("文件").assertIsDisplayed()
        compose.onNodeWithText("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起附件").performClick()
        compose.onAllNodesWithContentDescription("语音输入").assertCountEquals(1)
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("发送任务").assertDoesNotExist()
        val bounds = compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot
        captureContextMenu("composer-empty-voice")
        compose.onNode(hasSetTextAction()).performTextInput("测试箭头")
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("继续语音输入").assertIsEnabled().performClick()
        compose.onNodeWithText("测试箭头").assertExists()
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("文件").assertIsDisplayed()
        compose.onNodeWithText("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起附件").performClick()
        compose.onNodeWithText("测试箭头").assertExists()
        compose.onNodeWithContentDescription("发送任务").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(bounds.size, compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot.size)
        captureContextMenu("composer-text-arrow")
        compose.runOnIdle { model.draft("   ") }
        compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
        compose.runOnIdle {
            model.draft("")
            attachments = listOf(cc.catsco.core.Attachment("资料.txt", "", "text/plain", "{}"))
        }
        compose.onNodeWithContentDescription("发送任务").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("文件").assertIsDisplayed()
        compose.onNodeWithText("语音输入").assertDoesNotExist()
        assertEquals(2, voices)
        assertEquals(2, sends)
    }

    @Test fun emptyComposerAlternatesHintsAndStartsVoiceOnHold() {
        var phase by mutableStateOf<VoiceCapture?>(null)
        var moves = 0
        var releases = 0
        show {
            val model = remember { isolatedModel() }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                Composer(WorkspaceState(employeeId = "9", voiceCapture = phase), model, {}, {}, {},
                    voiceHold = VoiceHoldCallbacks(
                        start = { phase = VoiceCapture.Recording; true },
                        move = { moves++ },
                        release = { releases++; phase = null },
                        cancel = { phase = null },
                    ))
            }
        }
        compose.onNodeWithText("描述你的任务").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_750)
        compose.onNodeWithText("长按语音转文字").assertIsDisplayed()
        val editor = compose.onNode(hasSetTextAction())
        editor.performTouchInput { down(center) }
        compose.waitUntil(4_000) { phase == VoiceCapture.Recording }
        editor.assertIsNotFocused()
        editor.performTouchInput {
            moveTo(center - Offset(0f, height * 2f))
            up()
        }
        compose.runOnIdle {
            assertTrue(moves > 0)
            assertEquals(1, releases)
            assertNull(phase)
        }
        editor.performTouchInput { click() }
        editor.assertIsFocused()
        compose.onNodeWithText("描述你的任务").assertIsDisplayed()
    }

    @Test fun voiceLongPressKeepsItsPointerUntilItReleasesOverSend() {
        val targets = VoiceDropTargets()
        var phase by mutableStateOf<VoiceCapture?>(null)
        var preview by mutableStateOf("")
        var destination: VoiceDestination? = null
        show {
            val backdrop = rememberHazeState()
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().then(if (phase == null) Modifier else Modifier.hazeSource(backdrop))) {
                    val barWidth = 3.dp.toPx()
                    drawRect(Color.White)
                    repeat(5) { index ->
                        drawRect(Color(0xFF222222), Offset(index * 2 * barWidth, 0f), Size(barWidth, size.height))
                    }
                }
                Box(Modifier.align(Alignment.BottomEnd).size(52.dp).testTag("voice-press-test")
                    .holdToTalk(VoiceHoldCallbacks(
                        start = { targets.holding = true; phase = VoiceCapture.Recording; true },
                        move = { targets.selected = targets.hit(it) },
                        release = { destination = targets.hit(it) ?: VoiceDestination.Edit; targets.reset(); phase = null },
                        cancel = { targets.reset(); phase = null },
                    ), onTap = {}))
                phase?.let {
                    VoiceInputOverlay(it, preview, .08f, VoiceCharacterLimit, null, preview.isNotBlank(), targets,
                        onComplete = { destination = it; phase = null }, onCancel = { phase = null }, backdrop = backdrop)
                }
            }
        }
        val press = compose.onNodeWithTag("voice-press-test")
        val origin = press.fetchSemanticsNode().boundsInRoot.topLeft
        press.performTouchInput { down(center) }
        compose.waitUntil(4_000) { phase == VoiceCapture.Recording }
        compose.onNodeWithText("编辑").assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsDisplayed()
        compose.runOnIdle { assertNull(targets.send) }
        // Allow the frame to reach the window compositor before taking its screenshot.
        Thread.sleep(160)
        captureContextMenu("voice-overlay-listening-text-actions")
        val frame = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val pixels = frame.asImageBitmap().toPixelMap()
        val barWidth = with(compose.density) { 3.dp.toPx() }
        fun stripeContrast(fraction: Float): Float {
            val y = (pixels.height * fraction).toInt()
            return pixels[(barWidth * 1.5f).toInt(), y].red - pixels[(barWidth * 2.5f).toInt(), y].red
        }
        assertTrue("Top of the recording overlay should leave the page clear", stripeContrast(.5f) > .75f)
        assertTrue("Bottom of the recording overlay should soften the page", stripeContrast(.92f) < .30f)
        frame.recycle()
        compose.runOnIdle { preview = "转写中的文字" }
        compose.onNodeWithText("转写中的文字").assertIsDisplayed()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        val send = compose.onNodeWithText("发送").fetchSemanticsNode().boundsInRoot.center - origin
        press.performTouchInput { moveTo(send) }
        compose.runOnIdle { assertEquals(VoiceDestination.Send, targets.selected); assertNull(destination) }
        captureContextMenu("voice-overlay-send-selected")
        press.performTouchInput { up() }
        compose.runOnIdle { assertEquals(VoiceDestination.Send, destination) }
        assertEquals("中🐈", "中🐈文".takeVoiceCharacters(2))
    }

    @Test fun voiceTimerShowsTheRecordedDurationDuringFinalization() {
        var phase by mutableStateOf(VoiceCapture.Recording)
        val targets = VoiceDropTargets().apply { holding = true }
        show {
            VoiceInputOverlay(phase, "转写中的文字", 0f, VoiceCharacterLimit, null, true,
                targets, {}, {}, startedAt = 2_000L, stoppedAt = 3_607_000L)
        }
        val transcript = compose.onNodeWithText("转写中的文字")
        val initialBounds = transcript.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { targets.holding = false }
        assertEquals(initialBounds, transcript.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { phase = VoiceCapture.Finalizing }
        compose.onNodeWithText("正在完成转写… · 1:00:05").assertIsDisplayed()
        assertEquals(initialBounds, transcript.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun sendButtonDismissesTheKeyboardAndClearsInputFocus() {
        var sent = 0
        show {
            val model = remember { isolatedModel() }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                Composer(WorkspaceState(draft = "本机输入测试", employeeId = "9"), model, {}, {}, { sent++ })
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        fun keyboardVisible(): Boolean {
            var visible = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                visible = ViewCompat.getRootWindowInsets(host.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            return visible
        }
        compose.waitUntil(5000) { keyboardVisible() }
        compose.onNodeWithContentDescription("发送任务").performClick()
        compose.waitUntil(5000) { !keyboardVisible() }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.runOnIdle { assertEquals(1, sent) }
    }

    @Test fun attachmentPreviewAndRemovalHaveSeparateTouchTargets() {
        val image = cc.catsco.core.Attachment("fe2b95d9-2570-4d02-b19a-8a3ce572ee4a.jpg", "", "image/jpeg", "{}")
        val document = cc.catsco.core.Attachment("项目调研与需求说明第一版补充材料.pdf", "", "application/pdf", "{\"size\":2048}")
        var files by mutableStateOf(listOf(image, document))
        var previewed: cc.catsco.core.Attachment? = null
        val removed = mutableListOf<cc.catsco.core.Attachment>()
        show {
            val loader = remember { coil.ImageLoader(host) }
            DisposableEffect(loader) { onDispose { loader.shutdown() } }
            Box(Modifier.width(360.dp)) {
                ComposerAttachments(files, null, loader, true, { previewed = it }, { index ->
                    removed.add(files[index])
                    files = files.filterIndexed { position, _ -> position != index }
                })
            }
        }
        compose.onNodeWithText(image.name).assertDoesNotExist()
        compose.onNodeWithContentDescription("预览图片 1").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(image, previewed); assertTrue(removed.isEmpty()) }
        compose.onNodeWithContentDescription("移除图片 1").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(image), removed) }
        compose.onNodeWithContentDescription("预览 ${document.name}").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(document, previewed) }
        compose.onNodeWithContentDescription("移除 ${document.name}").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(listOf(image, document), removed); assertTrue(files.isEmpty()) }
    }

    @Test fun skillVersionStateUsesTheChosenAgentsActualReferences() {
        val skill = JSONObject().put("skillId", "author/skill").put("latestVersion", "1.1.0")
        assertEquals("", skillCatalogueStatus(skill, null, showAdded = true))
        val reference = JSONObject().put("skillId", "author/skill").put("version", "1.1.0")
        assertEquals("已添加", skillCatalogueStatus(skill, reference, showAdded = true))
        assertEquals("", skillCatalogueStatus(skill, reference, showAdded = false))
        reference.put("version", "1.0.0")
        assertEquals("可更新", skillCatalogueStatus(skill, reference, showAdded = false))
        assertEquals("author/skill", skillCatalogueTitle(skill))
        skill.put("name", "测试技能").put("author", "发布者").put("description", "整理并生成表格")
        assertEquals("测试技能", skillCatalogueTitle(skill))
        assertEquals("整理并生成表格", skillCatalogueDescription(skill))
        skill.put("summary", "整理表格")
        assertEquals("整理表格", skillCatalogueDescription(skill))
        assertNotNull(serverAddressError("http://example.com"))
        assertNull(serverAddressError("https://example.com"))
    }

    @Test fun skillHubShowsNamesAboveDescriptionsAndKeepsEmployeeAndFiltersTogether() {
        show {
            val model = remember { isolatedModel() }
            val skill = JSONObject().put("skillId", "test/skill").put("name", "测试技能")
                .put("description", "整理工作资料").put("latestVersion", "1.0.0")
            WorkspaceModuleScreen(WorkspaceState(page = "skills", employeeId = "9",
                workspace = cc.catsco.core.Workspace(employees = listOf(Employee("9", "测试员工", owner = true), Employee("10", "其他员工"))),
                resource = ResourceState(value = JSONObject().put("skills", org.json.JSONArray().put(skill)), loaded = true)), model)
        }
        val employee = compose.onNodeWithTag("skill-agent").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("skill-search").fetchSemanticsNode().boundsInRoot
        val scope = compose.onNodeWithTag("skill-scope").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("skill-row-test/skill").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("测试技能", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val description = compose.onNodeWithText("整理工作资料", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(search.bottom <= employee.top)
        assertTrue(kotlin.math.abs(employee.center.y - scope.center.y) <= 1f)
        assertTrue(employee.right <= scope.left)
        assertTrue(scope.bottom <= row.top)
        assertTrue(title.bottom <= description.top)
        assertTrue(title.left - row.left < 12 * host.resources.displayMetrics.density)
        compose.onNodeWithText("v1.0.0", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("skill-filter-all").assertIsSelected().assertIsEnabled()
        compose.onNodeWithTag("skill-agent").performClick()
        compose.onNodeWithTag("skill-agent-menu").assertIsDisplayed()
        compose.onNodeWithText("其他员工").assertDoesNotExist()
        compose.onNodeWithText("确定").assertDoesNotExist()
    }

    @Test fun slowRefreshAndFailureKeepExistingContentReadable() {
        val value = JSONObject().put("orders", org.json.JSONArray().put(JSONObject().put("order_no", "本机测试订单")))
        var resource by mutableStateOf(ResourceState(value = value, loaded = true))
        show {
            val model = remember { isolatedModel() }
            WorkspaceModuleScreen(WorkspaceState(page = "orders", resource = resource), model)
        }
        compose.onNodeWithText("本机测试订单").assertIsDisplayed()
        compose.runOnIdle { resource = resource.copy(loading = true) }
        compose.onNodeWithText("本机测试订单").assertIsDisplayed()
        compose.onNodeWithText("暂无订单").assertDoesNotExist()
        compose.runOnIdle { resource = resource.copy(loading = false, error = "网络连接超时") }
        compose.onNodeWithText("本机测试订单").assertIsDisplayed()
        compose.onNodeWithText("网络连接超时").assertIsDisplayed()
        compose.onNodeWithText("重新读取").assertIsDisplayed()
    }

    @Test fun homeAgentAtDoubleFontScaleKeepsSettingsAccessible() {
        var opened = ""
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                Column {
                    CatsCoHomeAgent(listOf(Employee("1", "第一位"), Employee("2", "第二位"), Employee("3", "第三位")), "2", {},
                        onSettings = { opened = it })
                }
            }
        }
        compose.onNodeWithTag("home-agent-card").assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals("2", opened) }
    }
}
