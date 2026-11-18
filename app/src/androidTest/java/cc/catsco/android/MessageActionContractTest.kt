package cc.catsco.android

import androidx.test.platform.app.InstrumentationRegistry
import cc.catsco.android.data.*
import cc.catsco.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageActionContractTest {
    private fun message(raw: String) = chatMessage(JSONObject(raw))

    @Test fun regenerationUsesTheTaskBeforeTheSelectedReply() {
        val messages = listOf(
            message("""{"id":1,"from_uid":1,"content":"原任务"}"""),
            message("""{"id":2,"from_uid":9,"content":"原回复"}"""),
            message("""{"id":3,"from_uid":1,"content":"后续任务"}"""),
            message("""{"id":4,"from_uid":9,"content":"后续回复"}"""),
        )
        val turns = presentMessages(messages, "1", setOf("9"))
        assertEquals("原任务", regenerationDraft(messages, turns[1], "1")?.text)
        assertEquals("后续任务", regenerationDraft(messages, turns[3], "1")?.text)
        assertNull(regenerationDraft(messages, turns[0], "1"))
        assertNull(regenerationDraft(messages, turns[1].copy(streaming = true), "1"))
        assertNull(regenerationDraft(messages.drop(1), turns[1], "1"))
    }

    @Test fun regenerationRetainsOriginalAttachmentsAndQuote() {
        val messages = listOf(
            message("""{"id":1,"from_uid":9,"from_name":"Agent","content":"被引用的内容"}"""),
            message("""{"id":2,"from_uid":1,"reply_to":1,"content_blocks":[{"type":"text","text":"检查附件"},{"type":"image","payload":{"name":"photo.png","url":"/image","mime_type":"image/png"}}]}"""),
            message("""{"id":3,"from_uid":9,"content":"检查结果"}"""),
        )
        val draft = regenerationDraft(messages, presentMessages(messages, "1", setOf("9")).last(), "1")!!
        val body = messageSendBody("grp_1", draft, "retry-id")
        assertEquals("检查附件", body.optString("content"))
        assertEquals(1L, body.optLong("reply_to"))
        assertEquals("image", body.getJSONArray("content_blocks").getJSONObject(1).optString("type"))
        assertEquals("/image", draft.attachments.single().url)
        assertEquals("retry-id", body.optString("client_msg_id"))
        assertEquals(1L, presentMessages(messages, "1", setOf("9"))[1].quote?.messageId)
    }

    @Test fun quotesUseTheFinalResponseIdAfterGroupedTools() {
        val messages = listOf(
            message("""{"id":1,"from_uid":1,"content":"任务"}"""),
            message("""{"id":2,"from_uid":9,"type":"tool_use","content_blocks":[{"type":"tool_use","id":"a","name":"read_file"}]}"""),
            message("""{"id":3,"from_uid":9,"content_blocks":[{"type":"tool_result","tool_use_id":"a","content":"ok"},{"type":"text","text":"完整结果"}]}"""),
        )
        val turn = presentMessages(messages, "1", setOf("9")).last()
        assertEquals(3L, turn.messageId)
        assertEquals(listOf("完整结果"), turn.text)
        assertFalse(messageSendBody("grp_1", ComposerDraft(text = "普通消息"), "normal").has("reply_to"))
    }

    @Test fun sentQuoteResolvesAnUnnamedAgentFromTheRoster() {
        val messages = listOf(
            message("""{"id":1,"from_uid":9,"content":"被引用的内容"}"""),
            message("""{"id":2,"from_uid":1,"reply_to":1,"content":"继续"}"""),
        )
        val quote = presentMessages(messages, "1", setOf("9"), mapOf("9" to "自迭代")).last().quote!!
        assertEquals("自迭代", quote.senderName)
        assertEquals("被引用的内容", quote.text)
    }

    @Test fun encryptedDraftRestoresAndClearsQuotesAlongsideLegacyDrafts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = Session(ServerAddress.parse("https://example.invalid"), "test-only", "message-action-test", "Test")
        val storage = AccountStorage(context)
        try {
            storage.write(session, "draft:grp_test", """{"text":"旧草稿","client_id":"existing-id"}""")
            assertNull(storage.draft(session, "grp_test").quote)
            assertEquals("existing-id", storage.draft(session, "grp_test").clientId)
            val quote = MessageQuote(42, "Agent", "完整引用正文")
            storage.saveDraft(session, "grp_test", ComposerDraft(text = "新草稿", quote = quote))
            assertEquals(quote, AccountStorage(context).draft(session, "grp_test").quote)
            storage.saveDraft(session, "grp_test", storage.draft(session, "grp_test").copy(quote = null))
            assertNull(storage.draft(session, "grp_test").quote)
            assertEquals("新草稿", storage.draft(session, "grp_test").text)
        } finally { storage.clear(session) }
    }
}
