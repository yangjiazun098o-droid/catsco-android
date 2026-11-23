package cc.catsco.android

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import cc.catsco.android.data.AccountStorage
import cc.catsco.core.ServerAddress
import cc.catsco.core.Session
import cc.catsco.core.WorkspaceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class OfflineCacheTest {
    @Test fun reopeningARecentConversationReusesParsedMessagesWhileServerSyncs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = ViewModelStore()
        val session = Session(ServerAddress.parse("https://example.test"), "fixture", "101", "测试")
        val prefix = "recent-history-${UUID.randomUUID()}"
        val app = object : Application() {
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("$prefix.$name", mode)
        }
        val history = AccountStorage(app)
        val retry = CompletableDeferred<Unit>()
        var visits = 0
        val fixture = object : WorkspaceRepository by DebugEntry.repository() {
            override suspend fun resource(session: Session, path: String, query: Map<String, String>, method: String, body: String?): String {
                if (path != "/api/messages" || query["topic_id"] != "grp_1") return """{"messages":[],"has_more":false}"""
                if (++visits > 1) retry.await()
                return """{"messages":[{"seq_id":1,"from_uid":101,"content":"历史会话样例"}],"has_more":false}"""
            }
        }
        lateinit var model: WorkspaceViewModel
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[WorkspaceViewModel::class.java]
                WorkspaceViewModel::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(model, fixture)
                @Suppress("UNCHECKED_CAST")
                val state = WorkspaceViewModel::class.java.getDeclaredField("mutable")
                    .apply { isAccessible = true }.get(model) as MutableStateFlow<WorkspaceState>
                state.value = WorkspaceState(session = session)
                model.navigate("chat/grp_1")
            }
            runBlocking {
                withTimeout(5_000) {
                    model.state.first { it.messages.any { message -> message.text == "历史会话样例" } && !it.loadingMessages }
                    while (history.read(session, "messages:grp_1") == null) delay(10)
                }
            }
            history.removeHistory(session, "grp_1")
            instrumentation.runOnMainSync {
                model.navigate("chat/grp_2")
                model.navigate("chat/grp_1")
            }
            val reopened = runBlocking {
                withTimeout(5_000) { model.state.first { it.page == "chat/grp_1" && it.messages.isNotEmpty() } }
            }
            assertTrue(reopened.loadingMessages)
            assertEquals("历史会话样例", reopened.messages.single().text)
        } finally {
            retry.complete(Unit)
            instrumentation.runOnMainSync { store.clear() }
            context.deleteSharedPreferences("$prefix.encrypted_workspace")
        }
    }

    @Test fun clearingOfflineCacheKeepsDraftsOrdersAndOtherAccounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "cache-fixture-${UUID.randomUUID()}"
        val testContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("$prefix.$name", mode)
        }
        val storage = AccountStorage(testContext)
        val session = Session(ServerAddress.parse("https://example.test"), "local", "1", "测试")
        val anotherAccount = session.copy(userId = "2")
        try {
            val draftKeys = listOf("draft:new-task", "module-draft:profile")
            val retained = listOf("commercial:topup:1000", "notifications", "new-task:last-employee")
            for (key in listOf("workspace", "messages:grp_1") + draftKeys + retained) storage.write(session, key, key)
            storage.write(anotherAccount, "workspace", "other account")

            storage.clearOfflineData(session, includeDrafts = false)
            assertNull(storage.read(session, "workspace"))
            assertNull(storage.read(session, "messages:grp_1"))
            for (key in draftKeys + retained) assertEquals(key, storage.read(session, key))

            storage.clearOfflineData(session, includeDrafts = true)
            for (key in draftKeys) assertNull(storage.read(session, key))
            for (key in retained) assertEquals(key, storage.read(session, key))
            assertEquals("other account", storage.read(anotherAccount, "workspace"))
        } finally {
            context.deleteSharedPreferences("$prefix.encrypted_workspace")
        }
    }
}
