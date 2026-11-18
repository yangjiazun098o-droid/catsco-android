package cc.catsco.core

import org.junit.Assert.*
import org.junit.Test

class MessageTimelineTest {
    @Test fun runtimePlanKeepsItsHumanBoundary() {
        val timeline = MessageTimeline()
        timeline.receive(ChatMessage(10, "1", "Me", "first task"))
        timeline.receive(ChatMessage(0, "9", "Agent", "plan", kind = "runtime_plan"))
        timeline.receive(ChatMessage(20, "1", "Me", "second task"))
        assertEquals(10L, timeline.messages.last().afterSequence)
    }

    @Test fun anOlderTurnCannotReplaceANewerPlan() {
        val timeline = MessageTimeline()
        timeline.receive(ChatMessage(0, "9", "Agent", "new", kind = "runtime_plan", turnId = "new", afterSequence = 20))
        timeline.receive(ChatMessage(0, "9", "Agent", "old", kind = "runtime_plan", turnId = "old", afterSequence = 10))
        assertEquals("new", timeline.messages.single().text)
    }

    @Test fun olderPlanRevisionDoesNotReplaceNewerPlan() {
        val timeline = MessageTimeline()
        val plan = ChatMessage(0, "9", "Agent", "current plan", kind = "runtime_plan", turnId = "turn", revision = 5)
        timeline.receive(plan)
        timeline.receive(plan.copy(text = "stale plan", revision = 2))
        assertEquals("current plan", timeline.messages.single().text)
        assertEquals("plan:9", timeline.messages.single().key)
    }
    @Test fun socketEchoAndHistoryHaveOneCopyAndKeepOlderMessages() {
        val timeline = MessageTimeline()
        val latest = ChatMessage(20, "9", "Agent", "answer")
        timeline.receive(latest)
        timeline.merge(listOf(latest, ChatMessage(3, "1", "Me", "question")))
        assertEquals(listOf(3L, 20L), timeline.messages.map { it.sequence })
    }

    @Test fun lateDeltaCannotReopenFinalizedStream() {
        val timeline = MessageTimeline()
        val delta = ChatMessage(0, "9", "Agent", "hel", "stream_delta", "turn-1", "delta")
        timeline.receive(delta)
        timeline.receive(delta.copy(text = "lo"))
        assertEquals("hello", timeline.messages.single().text)
        timeline.receive(ChatMessage(8, "9", "Agent", "hello", streamId = "turn-1"))
        timeline.receive(delta)
        assertEquals(1, timeline.messages.size)
        assertFalse(timeline.messages.single().streaming)
    }

    @Test fun cancelledStreamDoesNotEraseFinalMessages() {
        val timeline = MessageTimeline()
        timeline.receive(ChatMessage(2, "1", "Me", "question"))
        timeline.receive(ChatMessage(0, "9", "Agent", "partial", "stream_delta", "turn", "delta"))
        timeline.receive(ChatMessage(0, "9", "Agent", "", "stream_cancel", "turn", "cancel"))
        assertEquals(listOf(2L), timeline.messages.map { it.sequence })
    }
}
