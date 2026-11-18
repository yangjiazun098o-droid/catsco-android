package cc.catsco.android

import cc.catsco.android.data.chatMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageParsingTest {
    @Test fun readsNestedAndEncodedStoredBlocks() {
        val raw = JSONObject("""{"id":11,"from_uid":9,"type":"text","content":"{\"content_blocks\":[{\"type\":\"tool_result\",\"content\":\"actual tool output\"}]}"}""")
        val message = chatMessage(raw)
        assertEquals("tool_result", message.kind)
        assertEquals("actual tool output", message.text)
        assertEquals(1, message.blocks.size)
        val encoded = JSONObject("""{"id":12,"content_blocks":"[{\"type\":\"text\",\"text\":\"stored text\"}]"}""")
        assertEquals("stored text", chatMessage(encoded).text)
    }
    @Test fun readsStringMetadataAndStoredAttachmentBlocks() {
        val raw = JSONObject("""{"id":7,"from_uid":9,"content":"fallback","metadata":"{\"stream_id\":\"turn\"}","content_blocks":[{"type":"text","text":"answer"},{"type":"file","payload":{"url":"/files/report.pdf","name":"report.pdf","mime_type":"application/pdf","upload_key":"owned"}}]}""")
        val message = chatMessage(raw)
        assertEquals("answer", message.text)
        assertEquals("turn", message.streamId)
        assertEquals("application/pdf", message.attachments.single().mime)
        assertEquals("owned", JSONObject(message.attachments.single().raw).getString("upload_key"))
    }

    @Test fun runtimePlanPreservesRevisionAndSender() {
        val raw = JSONObject("""{"from":"usr9","type":"runtime_plan","content":{"payload":{"revision":6,"steps":[{"title":"Read files"}]}},"metadata":{"turn_id":"work"}}""")
        val message = chatMessage(raw)
        assertEquals("9", message.senderId)
        assertEquals("work", message.turnId)
        assertEquals(6L, message.revision)
    }
}
