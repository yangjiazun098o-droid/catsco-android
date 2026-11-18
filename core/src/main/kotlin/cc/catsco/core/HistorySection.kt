package cc.catsco.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class HistorySection(val title: String) {
    RECENT("最近"),
    WITHIN_WEEK("7天内"),
    WITHIN_MONTH("一个月内"),
    EARLIER("更早"),
}

fun historySection(
    timestamp: String,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): HistorySection {
    val date = runCatching { Instant.parse(timestamp).atZone(zone).toLocalDate() }.getOrNull()
        ?: return HistorySection.EARLIER
    val daysAgo = ChronoUnit.DAYS.between(date, today)
    return when {
        daysAgo <= 0 -> HistorySection.RECENT
        daysAgo <= 7 -> HistorySection.WITHIN_WEEK
        daysAgo <= 30 -> HistorySection.WITHIN_MONTH
        else -> HistorySection.EARLIER
    }
}
