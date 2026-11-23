package cc.catsco.core

import org.junit.Assert.*
import org.junit.Test

class EmployeeOrderTest {
    @Test fun lastSuccessfulEmployeeTakesPriorityRegardlessOfHosting() {
        val agents = listOf(
            Employee("default", "Owned cloud", owner = true, cloud = true),
            Employee("last-cloud", "Last cloud", cloud = true),
            Employee("local", "Local", cloud = false),
            Employee("unknown", "Unspecified hosting"),
        )
        assertEquals("last-cloud", agents.defaultTaskEmployeeId("last-cloud"))
        assertEquals("local", agents.defaultTaskEmployeeId("local"))
        assertEquals("unknown", agents.defaultTaskEmployeeId("unknown"))
        assertEquals("default", agents.defaultTaskEmployeeId("missing"))
        assertEquals("default", agents.defaultTaskEmployeeId(""))
        assertEquals("", emptyList<Employee>().defaultTaskEmployeeId("missing"))
    }

    @Test fun cloudThenOwnershipThenRecentTime() {
        val agents = listOf(
            Employee("local-owner", "Mine", owner = true, cloud = false, createdAt = 500),
            Employee("cloud-friend", "Friend", cloud = true, addedAt = 300),
            Employee("cloud-owner", "Cloud mine", owner = true, cloud = true, createdAt = 100),
            Employee("unknown", "Unknown", addedAt = 900),
        )
        assertEquals(listOf("cloud-owner", "cloud-friend", "local-owner", "unknown"), agents.prioritizedForTasks().map { it.id })
    }

    @Test fun newestAdditionWinsWithoutOwnedAgents() {
        val agents = listOf(Employee("old", "Old", addedAt = 10), Employee("new", "New", addedAt = 20))
        assertEquals("new", agents.prioritizedForTasks().first().id)
    }

    @Test fun newestOwnedAgentWinsWithinTheSameHostingTier() {
        val agents = listOf(Employee("old", "Old", owner = true, createdAt = 10), Employee("new", "New", owner = true, createdAt = 20))
        assertEquals("new", agents.prioritizedForTasks().first().id)
    }

    @Test fun unknownHistoryKeepsItsOrderWithoutGuessingFromIds() {
        val agents = listOf(Employee("9", "Z"), Employee("200", "A"))
        assertEquals(agents, agents.prioritizedForTasks())
        assertTrue(agents.withObservedAdditions(null, 1000).all { it.addedAt == 0L })
    }

    @Test fun subsequentAdditionsAndReadditionsGetAnObservedTime() {
        val old = Employee("old", "Old", addedAt = 10)
        val next = listOf(old.copy(addedAt = 0), Employee("new", "New")).withObservedAdditions(listOf(old), 100)
        assertEquals(listOf("new", "old"), next.map { it.id })
        assertEquals(10L, next.last().addedAt)
        assertEquals(100L, listOf(old.copy(addedAt = 0)).withObservedAdditions(emptyList(), 100).single().addedAt)
    }

    @Test fun metadataSurvivesSupplementalEndpointFailure() {
        val old = Employee("one", "Agent", cloud = true, createdAt = 10, addedAt = 20)
        val observed = listOf(Employee("one", "New name")).withObservedAdditions(listOf(old), 100).single()
        assertEquals(old.copy(name = "New name"), observed)
        assertFalse(listOf(old.copy(cloud = false)).withObservedAdditions(listOf(old), 100).single().cloud!!)
    }
}
