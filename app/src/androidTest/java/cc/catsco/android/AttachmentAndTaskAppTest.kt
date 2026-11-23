package cc.catsco.android

import androidx.test.platform.app.InstrumentationRegistry
import cc.catsco.android.data.ApiFailure
import cc.catsco.android.data.AttachmentFiles
import cc.catsco.android.data.TaskAppLaunch
import cc.catsco.core.Attachment
import cc.catsco.core.ServerAddress
import cc.catsco.core.Session
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttachmentAndTaskAppTest {
    @Test fun attachmentCacheIsScopedAndFailedMediaDoesNotInvalidateSession() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "attachment-test-${UUID.randomUUID()}")
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertNull(chain.request().header("Authorization"))
            val status = if (chain.request().url.encodedPath == "/denied") 401 else 200
            val body = if (chain.request().url.encodedPath == "/partial") Buffer().writeUtf8("part").asResponseBody(null, 100)
                else "fixture".toResponseBody()
            Response.Builder().request(chain.request()).code(status).message("fixture").protocol(Protocol.HTTP_1_1)
                .body(body).build()
        }.build()
        try {
            val cache = AttachmentFiles(root, client)
            val account = Session(ServerAddress.parse("https://api.test"), "local", "1", "one")
            val file = Attachment("demo.txt", "https://files.test/demo", "text/plain", "")
            val first = cache.read(account, file).first
            assertEquals(first, cache.read(account, file).first)
            val other = cache.read(account.copy(userId = "2"), file).first
            assertNotEquals(first, other)
            assertEquals(2, requests)
            val failure = runCatching { cache.read(account, file.copy(url = "https://files.test/denied")) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure is ApiFailure)
            repeat(2) {
                assertTrue(runCatching { cache.read(account, file.copy(url = "https://files.test/partial")) }.isFailure)
            }
            assertEquals(5, requests)
            assertEquals(2, root.walkTopDown().count { it.isFile })
            cache.clear(account)
            assertFalse(first.exists())
            assertTrue(other.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun taskAppFeedbackUsesTheOpeningReferenceAndRejectsAnotherTopic() {
        val app = JSONObject().put("id", "board").put("title", "任务表")
        val url = "https://gateway.test/_launch/fixture?next=/board/"
        fun response(topic: String, ref: String) = JSONObject().put("open_binding", JSONObject()
            .put("contract_version", "catsco.artifact-open-binding.v1").put("topic_id", topic).put("open_ref", ref)
            .put("app_id", "board").put("agent_uid", 9).put("app_origin", "https://gateway.test")
            .put("expires_at", Instant.now().plusSeconds(300).toString()))
        val first = response("grp_1", "aob_one")
        val second = response("grp_2", "aob_two")
        assertTrue(runCatching { TaskAppLaunch.from(first, url, "grp_2", app) }.isFailure)
        assertTrue(runCatching { TaskAppLaunch.from(first, url.replace("gateway.test", "other.test"), "grp_1", app) }.isFailure)
        for ((topic, result) in listOf("grp_1" to first, "grp_2" to second)) {
            val binding = TaskAppLaunch.from(result, url, topic, app)
            val payload = binding.feedback("更新标题", "msg-1")
            assertEquals(result.getJSONObject("open_binding").getString("open_ref"), payload.getString("open_ref"))
            assertFalse(payload.has("topic_id"))
            assertEquals("/board/", payload.getJSONObject("gateway_annotations").getJSONObject("page").getString("path"))
        }
    }
}
