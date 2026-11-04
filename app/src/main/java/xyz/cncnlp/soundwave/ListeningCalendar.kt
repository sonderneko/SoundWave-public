package xyz.cncnlp.soundwave

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/** Date boundaries used by listening history and daily statistics. */
internal object ListeningCalendar {
    /** Dates follow the user's current device timezone, not a fixed region. */
    val zoneId: ZoneId get() = ZoneId.systemDefault()

    private val dayFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun localDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()

    fun dayKey(epochMillis: Long): String = dayKey(localDate(epochMillis))

    fun dayKey(date: LocalDate): String = date.format(dayFormatter)

    fun weekKey(epochMillis: Long): String {
        val date = localDate(epochMillis)
        val fields = WeekFields.ISO
        return String.format(Locale.ROOT, "%04d%02d", date.get(fields.weekBasedYear()), date.get(fields.weekOfWeekBasedYear()))
    }

    fun monthAt(epochMillis: Long): YearMonth = YearMonth.from(localDate(epochMillis))

    fun monthDayCount(epochMillis: Long): Int = monthAt(epochMillis).lengthOfMonth()

    /** Monday-first calendar offset, matching the weekday labels in the heatmap. */
    fun monthStartOffset(epochMillis: Long): Int = monthAt(epochMillis).atDay(1).dayOfWeek.value - 1

    fun historyDayLabel(timestampMillis: Long, nowMillis: Long): String {
        if (timestampMillis <= 0L) return "更早"
        val date = localDate(timestampMillis)
        val today = localDate(nowMillis)
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> String.format(Locale.ROOT, "%02d-%02d", date.monthValue, date.dayOfMonth)
        }
    }

    fun clock(timestampMillis: Long): String =
        if (timestampMillis <= 0L) "" else DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            .withZone(zoneId).format(Instant.ofEpochMilli(timestampMillis))
}
