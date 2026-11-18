package cc.catsco.android.data

import cc.catsco.core.ServerAddress
import org.junit.Assert.*
import org.junit.Test

class MediaResourcesTest {
    @Test fun productionUploadsUseTheMatchingMediaDomain() {
        assertEquals("https://i.catsco.cc/uploads/images/photo.jpg", mediaUrl(ServerAddress.parse("https://app.catsco.cc"), "/uploads/images/photo.jpg").toString())
        assertEquals("https://i.catsco.cn/uploads/files/report.pdf", mediaUrl(ServerAddress.parse("https://catsco.cn"), "/uploads/files/report.pdf").toString())
    }

    @Test fun privateApisCustomServersAndAbsoluteUrlsRetainTheirOrigins() {
        val server = ServerAddress.parse("https://app.catsco.cc")
        assertEquals("https://app.catsco.cc/api/files/1", mediaUrl(server, "/api/files/1").toString())
        assertEquals("https://custom.example/uploads/photo.jpg", mediaUrl(ServerAddress.parse("https://custom.example"), "/uploads/photo.jpg").toString())
        assertEquals("https://app.catsco.cc:8443/uploads/photo.jpg", mediaUrl(ServerAddress.parse("https://app.catsco.cc:8443"), "/uploads/photo.jpg").toString())
        assertEquals("https://files.example/photo.jpg?signature=test", mediaUrl(server, "https://files.example/photo.jpg?signature=test").toString())
    }

    @Test fun invalidMediaAddressesAreRejected() {
        val server = ServerAddress.parse("https://app.catsco.cc")
        assertNull(mediaUrl(server, ""))
        assertNull(mediaUrl(server, "http://files.example/photo.jpg"))
        assertNull(mediaUrl(server, "https://user:password@files.example/photo.jpg"))
    }
}
