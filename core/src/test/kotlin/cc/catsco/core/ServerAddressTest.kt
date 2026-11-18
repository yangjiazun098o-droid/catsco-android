package cc.catsco.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun normalizesOriginForCredentialScope() {
        assertEquals("https://app.catsco.cc", ServerAddress.parse(" https://APP.CATSCO.CC/ ").value)
    }
    @Test fun rejectsUnsafeOrAmbiguousServers() {
        listOf("http://app.catsco.cc", "https://user:secret@app.catsco.cc", "https://app.catsco.cc/path",
            "https://app.catsco.cc?token=x", "https://app.catsco.cc#fragment", "file:///tmp/test", "https:///test").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.parse(it) }
        }
    }
}
