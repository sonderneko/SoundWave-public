package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ListenStatsDailyStoreTest {
    private val nowMs = LocalDate.of(2026, 6, 15).atTime(12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val deviceA = "00000000-0000-0000-0000-000000000001"
    private val deviceB = "00000000-0000-0000-0000-000000000002"

    @Test
    fun localProjectionKeeps365CalendarDaysCloudKeeps30AndStripsUndatedLegacy() {
        val day29 = dayKey(29)
        val day30 = dayKey(30)
        val day364 = dayKey(364)
        val day365 = dayKey(365)
        val root = JSONObject()
            .put(day29, songDay(deviceA, sec = 1L, count = 1L))
            .put(day30, songDay(deviceA, sec = 2L, count = 1L))
            .put(day364, songDay(deviceA, sec = 3L, count = 1L))
            .put(day365, songDay(deviceA, sec = 4L, count = 1L))
        root.getJSONObject(day29).getJSONObject("songs").getJSONObject("wy:legacy")
            .getJSONObject("devices").put(ListenStatsDailyStore.LEGACY_DEVICE_ID,
                JSONObject().put("sec", 50L).put("cnt", 9L))

        val local = JSONObject(ListenStatsDailyStore.localProjection(root.toString(), nowMs))
        val cloud = JSONObject(ListenStatsDailyStore.cloudProjection(root.toString(), nowMs))

        assertTrue(local.has(day29))
        assertTrue(local.has(day30))
        assertTrue(local.has(day364))
        assertFalse(local.has(day365))
        assertTrue(cloud.has(day29))
        assertFalse(cloud.has(day30))
        assertFalse(cloud.has(day364))
        assertFalse(cloud.has(day365))
        val cloudLegacySong = cloud.getJSONObject(day29).getJSONObject("songs").getJSONObject("wy:legacy")
        assertFalse(cloudLegacySong.getJSONObject("devices").has(ListenStatsDailyStore.LEGACY_DEVICE_ID))
        assertTrue(cloudLegacySong.getJSONObject("devices").has(deviceA))
    }

    @Test
    fun mergeUsesPerDeviceMaximumIsIdempotentAndKeepsLegacyOnlyLocally() {
        val song = Song("track", "Track", "Artist", "wy")
        val local = ListenStatsDailyStore.record("{}", song, deviceA, 100L, true, nowMs)
        val cloud = ListenStatsDailyStore.record("{}", song, deviceA, 90L, true, nowMs)
        val cloudWithOtherDevice = ListenStatsDailyStore.record(cloud, song, deviceB, 40L, true, nowMs)
        val localWithLegacy = JSONObject(local).apply {
            val day = getJSONObject(ListeningCalendar.dayKey(nowMs))
            day.getJSONObject("songs").getJSONObject("wy:track").getJSONObject("devices")
                .put(ListenStatsDailyStore.LEGACY_DEVICE_ID, JSONObject().put("sec", 0L).put("cnt", 3L))
        }.toString()

        val merged = ListenStatsDailyStore.mergeLocalAndCloud(localWithLegacy, cloudWithOtherDevice, nowMs)
        val repeated = ListenStatsDailyStore.mergeLocalAndCloud(merged, cloudWithOtherDevice, nowMs)
        val summary = ListenStatsDailyStore.summary(merged, nowMs)

        assertEquals(merged, repeated)
        assertEquals(140L, summary.totalSeconds)
        assertEquals(mapOf(ListeningCalendar.dayKey(nowMs) to 140L), summary.dailySeconds)
        assertEquals(140L, summary.songs.getValue("wy:track").seconds)
        assertEquals(5L, summary.songs.getValue("wy:track").count)
        assertEquals(mapOf(deviceA to 1L, deviceB to 1L, ListenStatsDailyStore.LEGACY_DEVICE_ID to 3L),
            summary.songs.getValue("wy:track").deviceCounts)
        val cloudProjection = JSONObject(ListenStatsDailyStore.cloudProjection(merged, nowMs))
        assertFalse(cloudProjection.getJSONObject(ListeningCalendar.dayKey(nowMs))
            .getJSONObject("songs").getJSONObject("wy:track").getJSONObject("devices")
            .has(ListenStatsDailyStore.LEGACY_DEVICE_ID))
    }

    @Test
    fun summaryForMonthOnlyAggregatesDaysInsideTheTargetMonth() {
        // now = 2026-06-15. Days: 2026-04-30 (outside), 2026-05-01 / 2026-05-31 (May), 2026-06-01 (June).
        val root = JSONObject()
            .put("20260430", fullDay(deviceA, sec = 900L, count = 9L))
            .put("20260501", fullDay(deviceA, sec = 100L, count = 2L))
            .put("20260531", fullDay(deviceB, sec = 50L, count = 1L))
            .put("20260601", fullDay(deviceA, sec = 700L, count = 7L))

        val may = ListenStatsDailyStore.summaryForMonth(root.toString(), java.time.YearMonth.of(2026, 5), nowMs)
        assertEquals(150L, may.totalSeconds)
        assertEquals(mapOf("20260501" to 100L, "20260531" to 50L), may.dailySeconds)
        assertEquals(150L, may.songs.getValue("wy:track").seconds)
        assertEquals(3L, may.songs.getValue("wy:track").count)
        assertEquals(mapOf("Artist" to 150L), may.artists)
        assertEquals(mapOf("wy" to 150L), may.platforms)

        val april = ListenStatsDailyStore.summaryForMonth(root.toString(), java.time.YearMonth.of(2026, 4), nowMs)
        assertEquals(900L, april.totalSeconds)
        assertEquals(9L, april.songs.getValue("wy:track").count)

        val empty = ListenStatsDailyStore.summaryForMonth(root.toString(), java.time.YearMonth.of(2026, 3), nowMs)
        assertEquals(0L, empty.totalSeconds)
        assertTrue(empty.songs.isEmpty())
        assertTrue(empty.dailySeconds.isEmpty())

        // Whole-range summary still sees every retained day: month filtering must not leak into it.
        val all = ListenStatsDailyStore.summary(root.toString(), nowMs)
        assertEquals(1750L, all.totalSeconds)
    }

    private fun fullDay(deviceId: String, sec: Long, count: Long): JSONObject = JSONObject()
        .put("totalByDevice", JSONObject().put(deviceId, sec))
        .put("artists", JSONObject().put("Artist", JSONObject().put(deviceId, sec)))
        .put("platforms", JSONObject().put("wy", JSONObject().put(deviceId, sec)))
        .put("songs", JSONObject().put("wy:track", JSONObject()
            .put("name", "Track")
            .put("singer", "Artist")
            .put("devices", JSONObject().put(deviceId, JSONObject().put("sec", sec).put("cnt", count)))))

    private fun dayKey(daysAgo: Long): String = DateTimeFormatter.BASIC_ISO_DATE.format(
        LocalDate.of(2026, 6, 15).minusDays(daysAgo)
    )

    private fun songDay(deviceId: String, sec: Long, count: Long): JSONObject = JSONObject()
        .put("songs", JSONObject().put("wy:legacy", JSONObject()
            .put("name", "Legacy")
            .put("singer", "Artist")
            .put("devices", JSONObject().put(deviceId, JSONObject().put("sec", sec).put("cnt", count)))))
}
