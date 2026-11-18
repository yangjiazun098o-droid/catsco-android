package cc.catsco.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
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
import cc.catsco.android.ui.*
import cc.catsco.core.Employee
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
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        compose.onNodeWithTag("sidebar-history").performScrollToNode(hasText(title))
        compose.onNodeWithText(title).assertIsDisplayed().performTouchInput { longClick() }
        listOf("重命名", "置顶", "多选", "删除").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("重命名").performClick()
        compose.onNodeWithText("重命名会话").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("关闭侧栏").performClick()
        compose.onNodeWithContentDescription("打开侧栏").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("打开侧栏").performClick()
        compose.onNodeWithText("账户与设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭侧栏").performClick()
        composer.assertIsDisplayed().assertIsNotFocused()
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

    @Test fun settingsAppearanceChoosesInlineAndDismissesWithoutConfirmation() {
        var opened = ""
        show {
            val model = remember { isolatedModel() }
            NativeSettings(WorkspaceState(), model.accountImages) { opened = it }
        }
        compose.onNodeWithText("外观").performScrollTo().performClick()
        compose.onNodeWithText("浅色").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("appearance/light", opened) }
        compose.onNodeWithText("浅色").assertDoesNotExist()
    }

    @Test fun modelQuotaAndServiceDetailsShareOneCard() {
        show {
            ModelUsageCard(JSONObject().put("model", "test-model").put("provider", "shared")
                .put("status", "active").put("quota_configured", true).put("remaining_percent", 63.75382920398), "共享模型服务")
        }
        compose.onNodeWithText("63%").assertIsDisplayed()
        compose.onNodeWithText("已用额度").assertDoesNotExist()
        compose.onNodeWithText("模型服务详情").performClick()
        compose.onNodeWithText("服务来源").assertIsDisplayed()
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
        var density = 1f
        show {
            density = LocalDensity.current.density
            model = remember { isolatedModel() }
            val state by model.state.collectAsState()
            CatsCoApp(state.copy(
                session = cc.catsco.core.Session(cc.catsco.core.ServerAddress.parse("https://example.test"), "test-only", "test", "Test"),
                resource = ResourceState(loaded = true),
            ), model)
        }
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("拍照").assertIsDisplayed()
        val photoLabel = compose.onNodeWithText("照片", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.waitForIdle()
        Thread.sleep(200)
        val reference = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val referencePixels = reference.asImageBitmap().toPixelMap()
        val sampleX = photoLabel.center.x.toInt()
        val sampleY = ((photoLabel.top - 70f * density).toInt()..(photoLabel.top - 7f * density).toInt())
            .minBy { referencePixels[sampleX, it].red }
        assertTrue("Reference photo pictogram must be painted", referencePixels[sampleX, sampleY].red < .75f)
        reference.recycle()
        fun assertAttachmentPreview(expanded: Boolean) {
            compose.waitForIdle()
            Thread.sleep(200)
            val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
            val pixels = screenshot.asImageBitmap().toPixelMap()
            val y = sampleY
            // The semantics tag precedes the translated graphics layer. Search the
            // preview's parallax range instead of treating its bounds as a draw offset.
            val color = ((sampleX - .16f * width).toInt()..sampleX)
                .map { pixels[it, y] }.minBy { it.red }
            screenshot.recycle()
            captureContextMenu(if (expanded) "settings-return-expanded-preview" else "settings-return-collapsed-preview")
            if (expanded) assertTrue("Expanded preview must include the photo pictogram: $color", color.red < .75f)
            else assertTrue("Collapsed preview must remove the photo pictogram: $color",
                color.red > .85f && color.green > .85f && color.blue > .85f)
        }
        val greeting = compose.onNodeWithText("需要为您做什么，Test？")
        val before = greeting.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("打开侧栏").performClick()
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
        compose.waitForIdle()
        Thread.sleep(200) // PixelCopy completion uses the real window clock.
        compose.onNodeWithContentDescription("打开侧栏").performClick()
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
        compose.onAllNodesWithContentDescription("语音输入").assertCountEquals(1)
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("发送任务").assertDoesNotExist()
        val bounds = compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot
        captureContextMenu("composer-empty-voice")
        compose.onNode(hasSetTextAction()).performTextInput("测试箭头")
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
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
        assertEquals(1, voices)
        assertEquals(2, sends)
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
        assertEquals("未配置", skillConfigurationStatus(skill, emptyList()))
        val reference = JSONObject().put("skillId", "author/skill").put("version", "1.1.0")
        assertEquals("已配置此版本", skillConfigurationStatus(skill, listOf(reference)))
        reference.put("version", "1.0.0")
        assertEquals("已配置 v1.0.0 · 可更新", skillConfigurationStatus(skill, listOf(reference)))
        skill.put("name", "测试技能").put("author", "发布者")
        assertFalse(skillCatalogueMetadata(skill, "未配置").contains("测试技能"))
        skill.put("description", "整理并生成表格")
        assertEquals("整理并生成表格", skillCatalogueTitle(skill))
        assertTrue(skillCatalogueMetadata(skill, "未配置").contains("测试技能"))
        assertNotNull(serverAddressError("http://example.com"))
        assertNull(serverAddressError("https://example.com"))
    }

    @Test fun skillHubUsesTheApplicationHeaderOrderAndFlatRows() {
        show {
            val model = remember { isolatedModel() }
            val skill = JSONObject().put("skillId", "test/skill").put("name", "测试技能")
                .put("description", "整理工作资料").put("latestVersion", "1.0.0")
            WorkspaceModuleScreen(WorkspaceState(page = "skills", employeeId = "9",
                workspace = cc.catsco.core.Workspace(employees = listOf(Employee("9", "测试员工", owner = true), Employee("10", "其他员工"))),
                resource = ResourceState(value = JSONObject().put("skills", org.json.JSONArray().put(skill)), loaded = true)), model)
        }
        val employee = compose.onNodeWithTag("skill-agent-row").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("skill-search").fetchSemanticsNode().boundsInRoot
        val scope = compose.onNodeWithTag("skill-scope").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("skill-row-test/skill").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("整理工作资料", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(employee.bottom <= search.top)
        assertTrue(search.bottom <= scope.top)
        assertTrue(scope.bottom <= row.top)
        assertTrue(title.left - row.left < 12 * host.resources.displayMetrics.density)
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
