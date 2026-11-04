package xyz.cncnlp.soundwave.recommend
import org.junit.Assert.*
import org.junit.Test
class TodayDislikeQuotaTest {
    @Test fun sixthAllowedSeventhAndRepeatedRejectedAndNextDayResets() {
        var saved=""
        (1..6).forEach { saved=TodayDislikeQuota.reserve(saved,"2026-10-21","id:$it")!! }
        assertEquals(6,TodayDislikeQuota.rejected(saved,"2026-10-21").size)
        assertNull(TodayDislikeQuota.reserve(saved,"2026-10-21","id:7"))
        assertNull(TodayDislikeQuota.reserve(saved,"2026-10-21","id:1"))
        val next=TodayDislikeQuota.reserve(saved,"2026-10-22","id:7")!!
        assertEquals(setOf("id:7"),TodayDislikeQuota.rejected(next,"2026-10-22"))
        assertEquals(emptySet<String>(),TodayDislikeQuota.rejected(saved,"2026-10-22"))
    }
}
