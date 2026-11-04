package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class TodayRecommendationSlotTest {
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, TodayRecommendationSlot.ZONE).toInstant().toEpochMilli()

    @Test fun beforeSixPmBelongsToYesterdaySlot() {
        assertEquals(LocalDate.of(2026, 10, 15), TodayRecommendationSlot.slotDate(at(2026, 10, 16, 17, 59)))
        assertEquals(LocalDate.of(2026, 10, 15), TodayRecommendationSlot.slotDate(at(2026, 10, 16, 0, 0)))
    }

    @Test fun atAndAfterSixPmBelongsToTodaySlot() {
        assertEquals(LocalDate.of(2026, 10, 16), TodayRecommendationSlot.slotDate(at(2026, 10, 16, 18, 0)))
        assertEquals(LocalDate.of(2026, 10, 16), TodayRecommendationSlot.slotDate(at(2026, 10, 16, 23, 59)))
    }

    @Test fun crossesMonthAndYearBoundaries() {
        assertEquals(LocalDate.of(2026, 12, 31), TodayRecommendationSlot.slotDate(at(2027, 1, 1, 9, 0)))
        assertEquals(LocalDate.of(2026, 2, 28), TodayRecommendationSlot.slotDate(at(2026, 3, 1, 17, 0)))
    }

    @Test fun usesBeijingTimeRegardlessOfUtcDate() {
        // 2026-10-16 10:00 UTC = 18:00 CST → today's slot.
        val utc = ZonedDateTime.of(2026, 10, 16, 10, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("2026-10-16", TodayRecommendationSlot.slotKey(utc))
        assertEquals("2026-10-15", TodayRecommendationSlot.slotKey(utc - 1))
    }

    @Test fun nextBoundaryIsNextSixPm() {
        assertEquals(at(2026, 10, 16, 18), TodayRecommendationSlot.nextBoundaryMillis(at(2026, 10, 16, 9)))
        assertEquals(at(2026, 10, 17, 18), TodayRecommendationSlot.nextBoundaryMillis(at(2026, 10, 16, 18)))
    }
}
