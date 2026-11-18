package cc.catsco.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySectionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 13)

    @Test fun respectsTodaySevenAndThirtyDayBoundaries() {
        val cases = mapOf(
            0L to HistorySection.RECENT,
            1L to HistorySection.WITHIN_WEEK,
            7L to HistorySection.WITHIN_WEEK,
            8L to HistorySection.WITHIN_MONTH,
            30L to HistorySection.WITHIN_MONTH,
            31L to HistorySection.EARLIER,
            365L to HistorySection.EARLIER,
        )
        cases.forEach { (days, expected) ->
            val timestamp = today.minusDays(days).atTime(12, 0).atZone(zone).toInstant().toString()
            assertEquals("days=$days", expected, historySection(timestamp, zone, today))
        }
    }

    @Test fun usesThePhonesLocalCalendarDay() {
        assertEquals(HistorySection.RECENT, historySection("2026-11-12T16:30:00Z", zone, today))
        assertEquals(HistorySection.WITHIN_WEEK, historySection("2026-11-12T15:30:00Z", zone, today))
    }

    @Test fun unknownDatesGoToEarlierAndSmallFutureSkewsStayRecent() {
        assertEquals(HistorySection.EARLIER, historySection("", zone, today))
        assertEquals(HistorySection.EARLIER, historySection("invalid", zone, today))
        assertEquals(HistorySection.RECENT, historySection("2026-11-14T00:00:00Z", zone, today))
    }

    @Test fun sectionOrderStaysChronological() {
        val reversed = HistorySection.entries.reversed()
        assertEquals(listOf("最近", "7天内", "一个月内", "更早"), reversed.sorted().map { it.title })
    }
}
