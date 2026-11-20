package cc.catsco.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import cc.catsco.android.data.AccountStorage
import cc.catsco.android.ui.CatsCoApp
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.Composer
import cc.catsco.android.ui.ComposerSendButton
import cc.catsco.android.ui.ConversationScreen
import cc.catsco.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses separate preferences and an in-process repository. No account credentials or network writes. */
class SendResponsivenessTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application = DraftApplication(instrumentation.targetContext)
    private val session = Session(ServerAddress.parse("https://example.invalid"), "", "send-test", "Test")
    private val storage = AccountStorage(application)
    private val store = ViewModelStore()
    private lateinit var model: WorkspaceViewModel
    private val requests = CopyOnWriteArrayList<JSONObject>()
    private val storedFollowups = CopyOnWriteArrayList<JSONObject>()
    private var response: suspend (String, JSONObject) -> String = { _, _ -> throw IOException("Local test failure") }

    private fun show(home: Boolean = false, renderWorkspace: Boolean = false, runningConversation: Boolean = false) {
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n cc.catsco.android.debug/cc.catsco.android.MessageReviewActivity",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[WorkspaceViewModel::class.java]
            model.foreground(false)
            val repository = object : WorkspaceRepository {
                override suspend fun login(server: ServerAddress, account: String, password: String) = session
                override suspend fun restore(session: Session) = session
                override suspend fun workspace(session: Session): Workspace = throw IOException("Local test refresh")
                override suspend fun renameConversation(session: Session, topicId: String, name: String) = Unit
                override suspend fun resource(session: Session, path: String, query: Map<String, String>, method: String, body: String?): String {
                    if (path == "/api/messages") return if (runningConversation) {
                        val history = JSONArray("""[{"seq_id":1,"from_uid":"send-test","content":"先整理讨论"},{"seq_id":2,"from_uid":"9","content":"正在核对记录","metadata":{"turn_id":"current-run","execution_state":"running"}}]""")
                        storedFollowups.forEach { history.put(it) }
                        JSONObject().put("messages", history).toString()
                    } else "{\"messages\":[]}"
                    val payload = JSONObject(body ?: "{}")
                    if (path == "/api/messages/send") {
                        if (!runningConversation || payload.optString("content") != "停止") {
                            val saved = storage.draft(session, payload.getString("topic_id"))
                            assertEquals(payload.getString("client_msg_id"), saved.clientId)
                            assertEquals(payload.getString("content"), saved.text)
                        }
                        requests.add(payload)
                    }
                    return response(path, payload)
                }
            }
            WorkspaceViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(model, repository)
            @Suppress("UNCHECKED_CAST")
            val state = WorkspaceViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                .get(model) as MutableStateFlow<WorkspaceState>
            state.value = WorkspaceState(session = session, workspace = Workspace(employees = listOf(
                Employee("9", "Agent", owner = true), Employee("10", "Alternate"),
            ), conversations = if (runningConversation) listOf(Conversation("grp_700", "Task", agentId = "9", active = true)) else emptyList()),
                employeeId = "9", page = if (home || runningConversation) "home" else "chat/grp_700")
            if (runningConversation) model.openConversation("grp_700", "Task")
            val host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.setContent {
                val current by model.state.collectAsState()
                CatsCoTheme {
                    Surface(Modifier.fillMaxSize()) {
                        if (renderWorkspace) CatsCoApp(current.copy(preview = true), model)
                        else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                            if (runningConversation) ConversationScreen(current, model, Modifier.weight(1f))
                            Composer(current, model, {}, {}, { model.send() })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun close() {
        application.commitGate?.countDown()
        instrumentation.runOnMainSync { store.clear() }
    }

    @Test fun tappingAnOwnedAgentCanOpenItsModelChooser() {
        response = { path, _ ->
            when (path) {
                "/api/agents/quota" -> "{}"
                "/api/bots/model-config" -> """{"config":{"management_enabled":true},"models":[{"id":"catalog","name":"测试模型"}]}"""
                else -> throw IOException("Unexpected test request")
            }
        }
        show(home = true, renderWorkspace = true)
        compose.onNodeWithTag("home-agent-card").performClick()
        compose.onNodeWithText("切换模型").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("选择模型").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("测试模型").assertIsDisplayed().performClick()
        compose.onNodeWithText("选择模型").assertDoesNotExist()
        compose.onNodeWithText("测试模型").assertIsDisplayed()
    }

    @Test fun closingTheLibraryReturnsToTheEntryPageAfterSwitchingTabs() {
        show(renderWorkspace = true)
        compose.runOnIdle {
            model.navigate("apps")
            model.selectLibraryTab(skills = true)
        }
        compose.onNodeWithContentDescription("关闭应用").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("chat/grp_700", model.state.value.page)
            model.navigate("home")
            model.navigate("apps")
            model.selectLibraryTab(skills = true)
            model.navigate("apps")
        }
        compose.onNodeWithContentDescription("关闭应用").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("home", model.state.value.page) }
    }

    @Test fun revisitingLibraryTabsKeepsTheirLoadedContentWithoutAnotherRequest() {
        val loadedPaths = CopyOnWriteArrayList<String>()
        response = { path, _ ->
            when (path) {
                "/api/artifacts/apps", "/api/skillhub/skills" -> {
                    loadedPaths.add(path)
                    "{}"
                }
                "/api/bots/definition/skills" -> "{}"
                else -> throw IOException("Unexpected test request")
            }
        }
        show(home = true, renderWorkspace = true)
        compose.runOnIdle { model.navigate("apps") }
        compose.waitUntil(5000) { model.state.value.resource.loaded }
        compose.onNodeWithTag("library-tab-1").performClick()
        compose.waitUntil(5000) { model.state.value.page == "skills" && model.state.value.resource.loaded }
        val initial = loadedPaths.toList()
        compose.onNodeWithTag("library-tab-0").performClick()
        compose.onNodeWithTag("library-tab-1").performClick()
        compose.onNodeWithTag("library-tab-1").assertIsSelected()
        compose.runOnIdle {
            assertTrue(model.state.value.resource.loaded)
            assertEquals(initial, loadedPaths.toList())
            assertEquals(1, loadedPaths.count { it == "/api/skillhub/skills" })
        }
    }

    @Test fun aBlockedDiskWriteKeepsSendingResponsiveAndRetryReusesItsId() {
        show()
        application.commitGate = CountDownLatch(1)
        compose.runOnIdle { model.draft("首次发送"); model.send(); model.send() }
        assertTrue(application.commitStarted.await(5, TimeUnit.SECONDS))
        compose.onNodeWithContentDescription("正在发送").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(model.state.value.sending)
        assertTrue(requests.isEmpty())
        application.commitGate!!.countDown()
        compose.waitUntil(5000) { requests.size == 1 && !model.state.value.sending }
        assertEquals("首次发送", model.state.value.draft)
        compose.runOnIdle { model.send() }
        compose.waitUntil(5000) { requests.size == 2 && !model.state.value.sending }
        assertEquals(requests[0].getString("client_msg_id"), requests[1].getString("client_msg_id"))
        assertFalse(application.mainThreadCommit)
    }

    @Test fun typingDuringDeliveryPreservesTheNextDraft() {
        show()
        val accepted = CompletableDeferred<Unit>()
        response = { _, payload ->
            accepted.await()
            JSONObject().put("message", JSONObject().put("id", 1).put("content", payload.getString("content"))).toString()
        }
        compose.runOnIdle { model.draft("发送这一条"); model.send() }
        compose.waitUntil(5000) { requests.size == 1 }
        compose.runOnIdle { model.draft("下一条草稿") }
        accepted.complete(Unit)
        compose.waitUntil(5000) { !model.state.value.sending }
        assertEquals("下一条草稿", model.state.value.draft)
        compose.waitUntil(5000) { storage.draft(session, "grp_700").text == "下一条草稿" }
        assertFalse(application.mainThreadCommit)
    }

    @Test fun aNewTaskPersistsThePendingMarkerAndTransfersItsDraftBeforeSending() {
        show(home = true)
        val accepted = CompletableDeferred<Unit>()
        response = { path, payload ->
            if (path == "/api/groups/create") {
                assertEquals("9", storage.draft(session, "new-task").pendingTaskAgent)
                "{\"group_id\":701}"
            } else {
                accepted.await()
                JSONObject().put("seq_id", 1).toString()
            }
        }
        compose.runOnIdle { model.draft("创建任务"); model.send() }
        compose.waitUntil(5000) { requests.size == 1 }
        assertEquals("home", model.state.value.page)
        assertEquals("9", storage.draft(session, "new-task").pendingTaskAgent)
        assertEquals("", storage.lastTaskEmployeeId(session))
        accepted.complete(Unit)
        compose.waitUntil(5000) { !model.state.value.sending }
        assertTrue(model.state.value.messages.any { it.text == "创建任务" })
        assertEquals("grp_701", requests.single().getString("topic_id"))
        assertEquals("chat/grp_701", model.state.value.page)
        assertEquals("", model.state.value.draft)
        assertEquals("9", AccountStorage(application).lastTaskEmployeeId(session))
        compose.waitUntil(5000) { storage.draft(session, "new-task") == ComposerDraft() && storage.draft(session, "grp_701") == ComposerDraft() }
        compose.runOnIdle {
            model.navigate("home")
            model.selectEmployee("10")
            assertEquals("10", model.state.value.employeeId)
            model.openConversation("grp_701", "创建任务")
            model.navigate("home")
            assertEquals("9", model.state.value.employeeId)
        }
        assertFalse(application.mainThreadCommit)
    }

    @Test fun sendingShowsProgressAndHandsOffToTheAgentReply() {
        show(home = true, renderWorkspace = true)
        val accepted = CompletableDeferred<Unit>()
        response = { path, _ ->
            when (path) {
                "/api/groups/create" -> """{"group_id":704}"""
                "/api/messages/send" -> {
                    accepted.await()
                    """{"seq_id":1}"""
                }
                else -> throw IOException("Unexpected test request")
            }
        }
        val welcome = compose.onNodeWithTag("home-agent-card")
        welcome.assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { model.draft("发送进度测试"); model.send() }
            compose.mainClock.advanceTimeBy(60)
            welcome.assertIsDisplayed()
            compose.mainClock.advanceTimeBy(180)
            welcome.assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5000) { requests.size == 1 }
        compose.onNodeWithContentDescription("正在发送").assertIsDisplayed().assertIsNotEnabled()
        compose.onNode(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
                and hasAnyAncestor(hasTestTag("composer-primary-action")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("等待回复").assertDoesNotExist()
        val firstConversationFrame = CopyOnWriteArrayList<WorkspaceState>()
        val observer = CoroutineScope(Dispatchers.Unconfined).launch {
            firstConversationFrame.add(model.state.first { it.page == "chat/grp_704" })
        }
        try {
            accepted.complete(Unit)
            compose.waitUntil(5000) { firstConversationFrame.isNotEmpty() && !model.state.value.sending }
        } finally {
            observer.cancel()
        }
        val first = firstConversationFrame.single()
        welcome.assertDoesNotExist()
        assertTrue(first.messages.any { it.text == "发送进度测试" })
        assertEquals(1L, first.awaitingReplyAfter["grp_704"])
        assertFalse(first.loadingMessages)
        assertTrue(model.state.value.messages.any { it.text == "发送进度测试" })
        val waitingStatus = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "等待回复")
        compose.onNodeWithText("等待回复").assertDoesNotExist()
        compose.onNode(waitingStatus).assertIsDisplayed()
        compose.onNodeWithTag("agent-loading-bubble").assertIsDisplayed()
        val receive = WorkspaceViewModel::class.java.getDeclaredMethod("receive", JSONObject::class.java)
            .apply { isAccessible = true }
        fun delta(text: String) = JSONObject().put("data", JSONObject()
            .put("topic_id", "grp_704").put("from_uid", "9").put("type", "stream_delta")
            .put("_stream_id", "reply").put("content", text))
        compose.runOnIdle {
            receive.invoke(model, delta("首段"))
            assertEquals("首段", model.state.value.messages.last().text)
            repeat(40) { receive.invoke(model, delta("字")) }
            assertEquals("首段", model.state.value.messages.last().text)
        }
        compose.waitUntil(5000) { model.state.value.messages.last().text == "首段" + "字".repeat(40) }
        compose.onNodeWithText("正在生成").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "正在生成",
        )).assertIsDisplayed()
        compose.runOnIdle {
            receive.invoke(model, delta("尾"))
            val event = JSONObject("""{"data":{"topic_id":"grp_704","seq_id":2,"from_uid":"9","_stream_id":"reply","content":"实际回复"}}""")
            receive.invoke(model, event)
            assertFalse(model.state.value.messages.any { it.streaming })
        }
        assertTrue(model.state.value.messages.any { it.text == "实际回复" })
        compose.onNode(waitingStatus).assertDoesNotExist()
        assertFalse(model.state.value.awaitingReplyAfter.containsKey("grp_704"))
    }

    @Test fun aQuickReceiptKeepsTheWelcomeFadeOverTheFirstConversationFrame() {
        show(home = true, renderWorkspace = true)
        response = { path, _ ->
            when (path) {
                "/api/groups/create" -> """{"group_id":705}"""
                "/api/messages/send" -> """{"seq_id":1}"""
                else -> throw IOException("Unexpected test request")
            }
        }
        val welcome = compose.onNodeWithTag("home-agent-card")
        welcome.assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { model.draft("快速回执测试"); model.send() }
            compose.waitUntil(5000) { model.state.value.page == "chat/grp_705" }
            compose.mainClock.advanceTimeBy(60)
            welcome.assertIsDisplayed()
            compose.onNodeWithTag("agent-loading-bubble").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(180)
            welcome.assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun projectTaskUsesItsOwnDraftAndIsAssignedBeforeSending() {
        val operations = CopyOnWriteArrayList<String>()
        response = { path, payload ->
            operations.add(path)
            when (path) {
                "/api/groups/create" -> {
                    assertEquals("项目任务", payload.getString("name"))
                    """{"group_id":703}"""
                }
                "/api/projects/topic" -> {
                    assertEquals("grp_703", payload.getString("topic_id"))
                    assertEquals(42L, payload.getLong("project_id"))
                    "{}"
                }
                "/api/messages/send" -> """{"seq_id":1}"""
                else -> throw IOException("Unexpected test request")
            }
        }
        show(home = true)
        compose.runOnIdle {
            model.renameNewTask("普通任务")
            model.draft("普通草稿")
            model.startProjectTask("42")
            assertEquals("", model.state.value.newTaskTitle)
            assertEquals("", model.state.value.draft)
            model.renameNewTask("项目任务")
            model.draft("项目草稿")
            model.startProjectTask("43")
            assertEquals("", model.state.value.newTaskTitle)
            model.startProjectTask("42")
            assertEquals("项目任务", model.state.value.newTaskTitle)
            assertEquals("项目草稿", model.state.value.draft)
            model.send()
        }
        compose.waitUntil(5000) { !model.state.value.sending }
        assertEquals(listOf("/api/groups/create", "/api/projects/topic", "/api/messages/send"), operations.toList())
        assertEquals("项目草稿", requests.single().getString("content"))
        assertEquals("42", model.state.value.workspace.conversations.single { it.id == "grp_703" }.projectId)
        assertEquals("chat/grp_703", model.state.value.page)
        compose.runOnIdle {
            model.startProjectTask("42")
            assertEquals("", model.state.value.newTaskTitle)
            assertEquals("", model.state.value.draft)
            model.startProjectTask()
            assertEquals("普通任务", model.state.value.newTaskTitle)
            assertEquals("普通草稿", model.state.value.draft)
        }
    }

    @Test fun aCreatedTaskWithFailedDeliveryOpensItsDraftAndRetriesWithoutCreatingAnotherTask() {
        show(home = true)
        storage.saveLastTaskEmployeeId(session, "8")
        var creations = 0
        response = { path, _ ->
            if (path == "/api/groups/create") {
                creations++
                "{\"group_id\":702}"
            } else throw IOException("Local delivery failure")
        }
        compose.runOnIdle { model.draft("保留失败任务"); model.send() }
        compose.waitUntil(5000) { requests.size == 1 && !model.state.value.sending }
        assertEquals("chat/grp_702", model.state.value.page)
        assertEquals("保留失败任务", model.state.value.draft)
        assertEquals("8", storage.lastTaskEmployeeId(session))
        compose.runOnIdle { model.send() }
        compose.waitUntil(5000) { requests.size == 2 && !model.state.value.sending }
        assertEquals(1, creations)
        assertEquals(requests[0].getString("client_msg_id"), requests[1].getString("client_msg_id"))
        compose.waitUntil(5000) { storage.draft(session, "new-task") == ComposerDraft() }
    }

    @Test fun aNextTaskDraftSurvivesTheConfirmedTaskHandoff() {
        show(home = true)
        val accepted = CompletableDeferred<Unit>()
        response = { path, payload ->
            if (path == "/api/groups/create") "{\"group_id\":703}"
            else {
                accepted.await()
                JSONObject().put("message", JSONObject().put("id", 1).put("from_uid", session.userId).put("content", payload.getString("content"))).toString()
            }
        }
        compose.runOnIdle { model.draft("本次任务"); model.send() }
        compose.waitUntil(5000) { requests.size == 1 }
        compose.runOnIdle { model.draft("下次任务草稿") }
        accepted.complete(Unit)
        compose.waitUntil(5000) { !model.state.value.sending }
        assertEquals("chat/grp_703", model.state.value.page)
        compose.runOnIdle { model.navigate("home") }
        assertEquals("下次任务草稿", model.state.value.draft)
        assertFalse(model.state.value.taskCreationUncertain)
    }

    @Test fun runningComposerRestoresStopInThePrimarySlotWhenTheDraftClears() {
        show()
        var voiceMode by mutableStateOf(true)
        var running by mutableStateOf(false)
        var stopping by mutableStateOf(false)
        var sending by mutableStateOf(false)
        var sendEnabled by mutableStateOf(true)
        var voices = 0
        var sends = 0
        var stops = 0
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            activity.setContent { CatsCoTheme {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    ComposerSendButton(running, stopping, sendEnabled, { sends++ }, { stops++ },
                        hasDraft = !voiceMode, sending = sending, voiceMode = voiceMode, onVoice = { voices++ })
                }
            } }
        }
        val bounds = compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled().performClick()
        compose.runOnIdle { voiceMode = false }
        compose.onNodeWithContentDescription("发送任务").performClick()
        assertEquals(bounds, compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { voiceMode = true; running = true; sendEnabled = false }
        compose.onNode(hasTestTag("composer-primary-action") and hasContentDescription("停止生成")).assertIsEnabled().performClick()
        compose.onNodeWithTag("composer-stop-action").assertDoesNotExist()
        compose.runOnIdle { voiceMode = false; sendEnabled = true }
        compose.onNodeWithContentDescription("发送补充消息").assertIsEnabled().performClick()
        assertEquals(bounds, compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot)
        val stopBounds = compose.onNodeWithTag("composer-stop-action").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { sending = true }
        compose.onNodeWithContentDescription("正在发送").assertIsNotEnabled()
        assertEquals(stopBounds, compose.onNodeWithTag("composer-stop-action").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { sending = false; voiceMode = true; sendEnabled = false }
        compose.onNode(hasTestTag("composer-primary-action") and hasContentDescription("停止生成")).assertIsEnabled()
        compose.onNodeWithTag("composer-stop-action").assertDoesNotExist()
        assertEquals(bounds, compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { voiceMode = false; sendEnabled = true }
        compose.onNode(hasTestTag("composer-stop-action") and hasContentDescription("停止生成")).performClick()
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        compose.runOnIdle { stopping = true }
        compose.onNodeWithContentDescription("正在停止").assertIsNotEnabled()
        compose.runOnIdle { running = false; stopping = false; sending = true }
        compose.onNodeWithContentDescription("正在发送").assertIsNotEnabled()
        assertEquals(1, voices)
        assertEquals(2, sends)
        assertEquals(2, stops)
    }

    @Test fun activeTaskAcceptsRepeatedFollowupsAndStoppingKeepsTheDraft() {
        response = { _, payload ->
            if (requests.size == 1) throw IOException("Local test send failure")
            val receipt = JSONObject().put("seq_id", requests.size + 2).put("content", payload.optString("content"))
            if (payload.optString("content") != "停止") {
                storedFollowups.add(JSONObject(receipt.toString()).put("from_uid", session.userId))
            }
            receipt.toString()
        }
        show(runningConversation = true)
        compose.waitUntil(5000) { model.state.value.messages.size == 2 }
        val input = compose.onNode(hasSetTextAction())
        input.performClick().performTextInput("补充一")
        input.performImeAction()
        compose.waitUntil(5000) { requests.size == 1 && !model.state.value.sending }
        assertEquals("补充一", model.state.value.draft)
        input.assertIsFocused().performImeAction()
        compose.waitUntil(5000) { requests.size == 2 && !model.state.value.sending }
        assertEquals(requests[0].getString("client_msg_id"), requests[1].getString("client_msg_id"))
        compose.waitUntil(5000) { model.state.value.messages.any { it.text == "补充一" && it.clientId.isBlank() } }
        assertTrue(model.state.value.awaitingReplyAfter.isEmpty())
        compose.onNodeWithText("已发送").assertExists()
        compose.onNodeWithTag("agent-loading-bubble").assertDoesNotExist()
        compose.onNode(hasTestTag("composer-primary-action") and hasContentDescription("停止生成")).assertIsEnabled()

        input.performTextInput("补充二")
        compose.onNodeWithContentDescription("发送补充消息").performClick()
        compose.waitUntil(5000) { requests.size == 3 && !model.state.value.sending }
        assertEquals(listOf("grp_700"), requests.map { it.getString("topic_id") }.distinct())
        assertEquals(listOf("补充一", "补充二"), model.state.value.messages.filter { it.sequence > 2 }.map { it.text })
        compose.onNode(hasTestTag("composer-primary-action") and hasContentDescription("停止生成")).assertIsEnabled()

        input.performTextInput("留下的草稿")
        compose.onNodeWithContentDescription("停止生成").performClick()
        compose.waitUntil(5000) { requests.size == 4 }
        compose.onNodeWithContentDescription("正在停止").assertIsNotEnabled()
        assertEquals("停止", requests.last().getString("content"))
        assertEquals("留下的草稿", model.state.value.draft)
    }

    private class DraftApplication(private val context: Context) : Application() {
        private val prefix = "send-test-${UUID.randomUUID()}"
        val commitStarted = CountDownLatch(1)
        @Volatile var commitGate: CountDownLatch? = null
        @Volatile var mainThreadCommit = false

        init { attachBaseContext(context) }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = context.getSharedPreferences("$prefix.$name", mode)
            if (name != "encrypted_workspace") return prefs
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun commit(): Boolean {
                            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadCommit = true
                            commitStarted.countDown()
                            check(commitGate?.await(10, TimeUnit.SECONDS) != false) { "Test disk gate timed out" }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }
}
