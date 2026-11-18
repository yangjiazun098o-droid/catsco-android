package cc.catsco.android

import androidx.test.platform.app.InstrumentationRegistry
import cc.catsco.android.data.AccountStorage
import cc.catsco.android.data.employee
import cc.catsco.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmployeeMetadataTest {
    @Test fun cloudArtifactsDoNotIdentifyCloudHosting() {
        val friend = employee(JSONObject("""{"id":1,"display_name":"Friend","relation":"friend","cloud_artifacts_enabled":true}"""))
        assertNull(friend.cloud)
        assertEquals(0L, friend.addedAt)
        val cloud = employee(JSONObject("""{"id":1,"tenant_name":"cloud-1"}"""))
        assertTrue(cloud.cloud!!)
    }

    @Test fun ownedDetailsSupplyCreationTimeAndHosting() {
        val agent = employee(
            JSONObject("""{"id":2,"display_name":"Mine","relation":"owner"}"""),
            JSONObject("""{"created_at":"2026-11-13T00:00:00Z","added_at":100,"tenant_name":"tenant"}"""),
        )
        assertTrue(agent.owner)
        assertTrue(agent.cloud!!)
        assertEquals(java.time.Instant.parse("2026-11-13T00:00:00Z").toEpochMilli(), agent.createdAt)
        assertEquals(100_000L, agent.addedAt)
        assertFalse(employee(JSONObject("""{"id":2,"relation":"owner"}"""), JSONObject()).cloud!!)
    }

    @Test fun missingWorkspaceCacheDiffersFromAnEmptySavedRoster() {
        val storage = AccountStorage(InstrumentationRegistry.getInstrumentation().targetContext)
        val session = Session(ServerAddress.parse("https://example.com"), "", "empty-roster-cache-test", "Test")
        try {
            storage.clear(session)
            assertNull(storage.cachedWorkspace(session))
            assertEquals(Workspace(), storage.workspace(session))
            storage.saveWorkspace(session, Workspace())
            assertEquals(Workspace(), storage.cachedWorkspace(session))
        } finally {
            storage.clear(session)
        }
    }

    @Test fun encryptedCacheSupportsLegacyAndNewMetadata() {
        val storage = AccountStorage(InstrumentationRegistry.getInstrumentation().targetContext)
        val session = Session(ServerAddress.parse("https://example.com"), "", "agent-order-test", "Test")
        try {
            storage.write(session, "workspace", """{"employees":[{"id":"1","name":"Legacy","owner":true}]}""")
            val legacy = storage.workspace(session).employees.single()
            assertNull(legacy.cloud)
            assertEquals(0L, legacy.addedAt)
            val expected = Workspace(listOf(legacy.copy(cloud = true, createdAt = 10, addedAt = 20)))
            storage.saveWorkspace(session, expected)
            assertEquals(expected, storage.workspace(session))
        } finally {
            storage.clear(session)
        }
    }
}
