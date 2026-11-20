package cc.catsco.android

import cc.catsco.android.data.findPreviewUpdate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewUpdateTest {
    private fun release(version: Int, draft: Boolean = false, trusted: Boolean = true): JSONObject {
        val host = if (trusted) "github.com" else "example.com"
        val tag = "v0.1.0-preview.$version"
        return JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", true)
            .put("assets", JSONArray().put(JSONObject()
                .put("name", "CatsCo-Android-Preview.apk")
                .put("state", "uploaded")
                .put("size", 123456)
                .put("digest", "sha256:" + "a".repeat(64))
                .put("browser_download_url",
                    "https://$host/yangjiazun098o-droid/catsco-android/releases/download/$tag/CatsCo-Android-Preview.apk")))
    }

    @Test fun selectsHighestInstallablePreviewRegardlessOfGitHubOrder() {
        val releases = JSONArray().put(release(3)).put(release(8, draft = true))
            .put(release(5)).put(release(7, trusted = false))
        val found = findPreviewUpdate(releases, 3)
        assertEquals(5, found?.number)
        assertEquals("a".repeat(64), found?.digest)
        assertNull(findPreviewUpdate(releases, 5))
    }

    @Test fun doesNotOfferAnUnverifiedPackageOrADifferentChannel() {
        val invalid = release(8).apply { getJSONArray("assets").getJSONObject(0).remove("digest") }
        val stable = release(9).put("prerelease", false)
        assertNull(findPreviewUpdate(JSONArray().put(invalid).put(stable), 3))
    }
}
