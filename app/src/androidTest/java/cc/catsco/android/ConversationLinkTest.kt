package cc.catsco.android

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class ConversationLinkTest {
    @Test fun acceptsConversationIdsWithoutRequiringCachedRoster() {
        assertEquals("grp_7220", conversationTopic(Uri.parse("catsco://conversation/grp_7220")))
        assertEquals("p2p_1_9", conversationTopic(Uri.parse("catsco://conversation/p2p_1_9")))
    }

    @Test fun rejectsOtherRoutesAndMalformedIds() {
        listOf("https://conversation/grp_1", "catsco://other/grp_1", "catsco://conversation/abc",
            "catsco://conversation/extra/grp_1", "catsco://conversation/grp_1%2Fother").forEach {
            assertNull(it, conversationTopic(Uri.parse(it)))
        }
    }
}
