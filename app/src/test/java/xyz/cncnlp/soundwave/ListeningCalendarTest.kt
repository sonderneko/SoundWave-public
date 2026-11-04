package xyz.cncnlp.soundwave

import java.time.Instant
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ListeningCalendarTest {
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun useChinaDeviceTimezone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun restoreDeviceTimezone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `month day count follows the actual Gregorian month including leap years`() {
        assertEquals(29, ListeningCalendar.monthDayCount(at("2024-02-15T12:00:00+08:00")))
        assertEquals(28, ListeningCalendar.monthDayCount(at("2025-02-15T12:00:00+08:00")))
        assertEquals(30, ListeningCalendar.monthDayCount(at("2026-04-15T12:00:00+08:00")))
        assertEquals(31, ListeningCalendar.monthDayCount(at("2026-10-15T12:00:00+08:00")))
    }

    @Test
    fun `day and month boundaries follow the device timezone`() {
        val lastSecondOfFeb29InChina = at("2024-02-29T23:59:59+08:00")
        val firstSecondOfMar1InChina = at("2024-03-01T00:00:00+08:00")

        assertEquals("20240229", ListeningCalendar.dayKey(lastSecondOfFeb29InChina))
        assertEquals("20240301", ListeningCalendar.dayKey(firstSecondOfMar1InChina))
        assertEquals(29, ListeningCalendar.monthDayCount(lastSecondOfFeb29InChina))
        assertEquals(31, ListeningCalendar.monthDayCount(firstSecondOfMar1InChina))
    }

    @Test
    fun `history labels and clock follow device midnight`() {
        val now = at("2026-10-12T00:05:00+08:00")
        val yesterday = at("2026-10-11T23:59:00+08:00")
        val sameInstantInUtc = at("2026-10-11T16:05:00Z")

        assertEquals("今天", ListeningCalendar.historyDayLabel(now, now))
        assertEquals("昨天", ListeningCalendar.historyDayLabel(yesterday, now))
        assertEquals("00:05", ListeningCalendar.clock(sameInstantInUtc))
    }

    @Test
    fun `changing the device timezone changes the same instant day key`() {
        val instant = at("2024-02-29T23:30:00Z")
        assertEquals("20240301", ListeningCalendar.dayKey(instant))
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertEquals("20240229", ListeningCalendar.dayKey(instant))
    }

    @Test
    fun `month grid starts Monday and weekday offset follows device calendar`() {
        assertEquals(3, ListeningCalendar.monthStartOffset(at("2026-10-01T12:00:00+08:00")))
        assertEquals(3, ListeningCalendar.monthStartOffset(at("2024-02-01T12:00:00+08:00")))
    }

    private fun at(isoDateTime: String): Long = Instant.parse(isoDateTime).toEpochMilli()
}
