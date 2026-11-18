package cc.catsco.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
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
import cc.catsco.android.ui.CatsCoTheme
import cc.catsco.android.ui.Composer
import cc.catsco.android.ui.ComposerSendButton
import cc.catsco.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
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
    private var response: suspend (String, JSONObject) -> String = { _, _ -> throw IOException("Local test failure") }

    private fun show(home: Boolean = false) {
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
                    if (path == "/api/messages") return "{\"messages\":[]}"
                    val payload = JSONObject(body ?: "{}")
                    if (path == "/api/messages/send") {
                        val saved = storage.draft(session, payload.getString("topic_id"))
                        assertEquals(payload.getString("client_msg_id"), saved.clientId)
                        assertEquals(payload.getString("content"), saved.text)
                        requests.add(payload)
                    }
                    return response(path, payload)
                }
            }
            WorkspaceViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(model, repository)
            @Suppress("UNCHECKED_CAST")
            val state = WorkspaceViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
                .get(model) as MutableStateFlow<WorkspaceState>
            state.value = WorkspaceState(session = session, workspace = Workspace(employees = listOf(Employee("9", "Agent"))),
                employeeId = "9", page = if (home) "home" else "chat/grp_700")
            val host = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            host.setContent {
                val current by model.state.collectAsState()
                CatsCoTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
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
        accepted.complete(Unit)
        compose.waitUntil(5000) { !model.state.value.sending }
        assertTrue(model.state.value.messages.any { it.text == "创建任务" })
        assertEquals("grp_701", requests.single().getString("topic_id"))
        assertEquals("chat/grp_701", model.state.value.page)
        assertEquals("", model.state.value.draft)
        compose.waitUntil(5000) { storage.draft(session, "new-task") == ComposerDraft() && storage.draft(session, "grp_701") == ComposerDraft() }
        assertFalse(application.mainThreadCommit)
    }

    @Test fun aCreatedTaskWithFailedDeliveryOpensItsDraftAndRetriesWithoutCreatingAnotherTask() {
        show(home = true)
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

    @Test fun voiceSendAndStopShareBoundsAndDispatchOnlyTheirOwnAction() {
        show()
        var voiceMode by mutableStateOf(true)
        var running by mutableStateOf(false)
        var stopping by mutableStateOf(false)
        var sending by mutableStateOf(false)
        var voices = 0
        var sends = 0
        var stops = 0
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MessageReviewActivity>().single()
            activity.setContent { CatsCoTheme {
                ComposerSendButton(running, stopping, true, { sends++ }, { stops++ },
                    sending = sending, voiceMode = voiceMode, onVoice = { voices++ })
            } }
        }
        val bounds = compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("语音输入").assertIsEnabled().performClick()
        compose.runOnIdle { voiceMode = false }
        compose.onNodeWithContentDescription("发送任务").performClick()
        assertEquals(bounds, compose.onNodeWithTag("composer-primary-action").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { voiceMode = true; running = true }
        compose.onNodeWithContentDescription("停止生成").performClick()
        compose.onNodeWithContentDescription("语音输入").assertDoesNotExist()
        compose.runOnIdle { stopping = true }
        compose.onNodeWithContentDescription("正在停止").assertIsNotEnabled()
        compose.runOnIdle { running = false; stopping = false; sending = true }
        compose.onNodeWithContentDescription("正在发送").assertIsNotEnabled()
        assertEquals(1, voices)
        assertEquals(1, sends)
        assertEquals(1, stops)
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
