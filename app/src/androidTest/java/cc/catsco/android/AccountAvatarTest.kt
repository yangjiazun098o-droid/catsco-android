package cc.catsco.android

import androidx.test.platform.app.InstrumentationRegistry
import cc.catsco.android.data.withProfile
import cc.catsco.android.ui.accountAvatarRequest
import cc.catsco.core.ServerAddress
import cc.catsco.core.Session
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountAvatarTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val session = Session(ServerAddress.parse("https://app.catsco.cc"), "test-only-token", "23", "User", "/uploads/avatar.png")

    @Test fun authenticatesOnlyTheAccountOrigin() {
        val sameOrigin = accountAvatarRequest(context, session)!!
        assertEquals("https://i.catsco.cc/uploads/avatar.png", sameOrigin.data)
        assertNull(sameOrigin.headers["Authorization"])
        val privateResource = accountAvatarRequest(context, session.copy(avatarUrl = "/api/files/avatar.png"))!!
        assertEquals("Bearer test-only-token", privateResource.headers["Authorization"])
        val external = accountAvatarRequest(context, session.copy(avatarUrl = "https://images.example.com/avatar.png"))!!
        assertNull(external.headers["Authorization"])
        val differentPort = accountAvatarRequest(context, session.copy(avatarUrl = "https://app.catsco.cc:8443/avatar.png"))!!
        assertNull(differentPort.headers["Authorization"])
        assertNull(accountAvatarRequest(context, session.copy(avatarUrl = "http://images.example.com/avatar.png")))
        assertNull(accountAvatarRequest(context, session.copy(avatarUrl = "https://user:password@images.example.com/avatar.png")))
    }

    @Test fun separatesCachedAvatarsByAccount() {
        val first = accountAvatarRequest(context, session)!!
        val second = accountAvatarRequest(context, session.copy(userId = "24"))!!
        assertNotEquals(first.diskCacheKey, second.diskCacheKey)
        assertNotEquals(first.memoryCacheKey, second.memoryCacheKey)
    }

    @Test fun retainsLegacyProfileAndHandlesAvatarRemoval() {
        assertEquals(session.avatarUrl, session.withProfile(JSONObject("""{"display_name":"New name"}""")).avatarUrl)
        assertEquals("", session.withProfile(JSONObject("""{"avatar_url":null}""")).avatarUrl)
        assertEquals("/uploads/new.png", session.withProfile(JSONObject("""{"avatar_url":"/uploads/new.png"}""")).avatarUrl)
        assertEquals("", Session(session.server, session.token, "old-user", "Legacy").avatarUrl)
    }
}
