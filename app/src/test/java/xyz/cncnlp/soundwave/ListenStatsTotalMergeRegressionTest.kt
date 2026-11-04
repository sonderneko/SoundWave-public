package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ListenStatsTotalMergeRegressionTest {
    private val nowMs = LocalDate.of(2026, 6, 15).atTime(12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val deviceA = "00000000-0000-0000-0000-000000000001"
    private val deviceB = "00000000-0000-0000-0000-000000000002"
    private val song = Song("track", "Track", "Artist", "wy")

    @Test
    fun emptyCloudKeepsLocallyRecordedTotalsAndDetails() {
        val local = ListenStatsDailyStore.record("{}", song, deviceA, 37L, true, nowMs)
        val merged = ListenStatsDailyStore.mergeLocalAndCloud(local, "{}", nowMs)
        val summary = ListenStatsDailyStore.summary(merged, nowMs)
        val today = ListeningCalendar.dayKey(nowMs)

        assertEquals(37L, summary.totalSeconds)
        assertEquals(37L, summary.dailySeconds.getValue(today))
        assertEquals(37L, summary.songs.getValue("wy:track").seconds)
        assertEquals(1L, summary.songs.getValue("wy:track").count)
        assertEquals(37L, summary.artists.getValue("Artist"))
        assertEquals(37L, summary.platforms.getValue("wy"))
    }

    @Test
    fun sameDeviceUsesOlderRemoteMaximumAndRepeatedSyncIsIdempotent() {
        val local = ListenStatsDailyStore.record("{}", song, deviceA, 100L, true, nowMs)
        val cloud = ListenStatsDailyStore.record("{}", song, deviceA, 90L, true, nowMs)

        val merged = ListenStatsDailyStore.mergeLocalAndCloud(local, cloud, nowMs)
        val repeated = ListenStatsDailyStore.mergeLocalAndCloud(merged, cloud, nowMs)
        val summary = ListenStatsDailyStore.summary(merged, nowMs)

        assertEquals(merged, repeated)
        assertEquals(100L, summary.totalSeconds)
        assertEquals(100L, summary.dailySeconds.getValue(ListeningCalendar.dayKey(nowMs)))
        assertEquals(100L, summary.songs.getValue("wy:track").seconds)
        assertEquals(1L, summary.songs.getValue("wy:track").count)
        assertEquals(mapOf(deviceA to 1L), summary.songs.getValue("wy:track").deviceCounts)
    }

    @Test
    fun twoDevicesAddTotalsAndRepeatedSyncDoesNotDoubleThem() {
        val local = ListenStatsDailyStore.record("{}", song, deviceA, 60L, true, nowMs)
        val cloud = ListenStatsDailyStore.record("{}", song, deviceB, 40L, true, nowMs)

        val merged = ListenStatsDailyStore.mergeLocalAndCloud(local, cloud, nowMs)
        val repeated = ListenStatsDailyStore.mergeLocalAndCloud(merged, cloud, nowMs)
        val summary = ListenStatsDailyStore.summary(merged, nowMs)

        assertEquals(merged, repeated)
        assertEquals(100L, summary.totalSeconds)
        assertEquals(100L, summary.dailySeconds.getValue(ListeningCalendar.dayKey(nowMs)))
        assertEquals(100L, summary.songs.getValue("wy:track").seconds)
        assertEquals(2L, summary.songs.getValue("wy:track").count)
        assertEquals(mapOf(deviceA to 1L, deviceB to 1L), summary.songs.getValue("wy:track").deviceCounts)
    }

    @Test
    fun mergeKeepsLocal365DaysButOnlyCloud30Days() {
        var local = "{}"
        local = recordDaysAgo(local, deviceA, 1L, 29)
        local = recordDaysAgo(local, deviceA, 3L, 30)
        local = recordDaysAgo(local, deviceA, 4L, 364)
        local = recordDaysAgo(local, deviceA, 5L, 365)
        var cloud = "{}"
        cloud = recordDaysAgo(cloud, deviceB, 2L, 29)
        cloud = recordDaysAgo(cloud, deviceB, 4L, 30)

        val summary = ListenStatsDailyStore.summary(
            ListenStatsDailyStore.mergeLocalAndCloud(local, cloud, nowMs), nowMs
        )
        val day29 = dayKey(29)
        val day30 = dayKey(30)
        val day364 = dayKey(364)
        val day365 = dayKey(365)

        assertEquals(10L, summary.totalSeconds)
        assertEquals(mapOf(day29 to 3L, day30 to 3L, day364 to 4L), summary.dailySeconds)
        assertFalse(summary.dailySeconds.containsKey(day365))
    }

    @Test
    fun legacyAndHistoryBackfillTotalsRemainLocalAndNeverEnterCloudProjection() {
        val dayKey = ListeningCalendar.dayKey(nowMs)
        val local = JSONObject(ListenStatsDailyStore.record("{}", song, deviceA, 10L, true, nowMs)).apply {
            val day = getJSONObject(dayKey)
            day.getJSONObject("totalByDevice")
                .put(ListenStatsDailyStore.LEGACY_DEVICE_ID, 7L)
                .put(ListenStatsDailyStore.HISTORY_BACKFILL_DEVICE_ID, 8L)
            listOf("artists", "platforms").forEach { field ->
                val label = if (field == "artists") "Artist" else "wy"
                day.getJSONObject(field).getJSONObject(label)
                    .put(ListenStatsDailyStore.LEGACY_DEVICE_ID, 7L)
                    .put(ListenStatsDailyStore.HISTORY_BACKFILL_DEVICE_ID, 8L)
            }
            val songDevices = day.getJSONObject("songs").getJSONObject("wy:track").getJSONObject("devices")
            songDevices.put(ListenStatsDailyStore.LEGACY_DEVICE_ID, JSONObject().put("sec", 7L).put("cnt", 3L))
            songDevices.put(ListenStatsDailyStore.HISTORY_BACKFILL_DEVICE_ID, JSONObject().put("sec", 8L).put("cnt", 4L))
        }.toString()

        val merged = ListenStatsDailyStore.mergeLocalAndCloud(local, "{}", nowMs)
        val summary = ListenStatsDailyStore.summary(merged, nowMs)
        val mergedDay = JSONObject(merged).getJSONObject(dayKey)
        val cloudDay = JSONObject(ListenStatsDailyStore.cloudProjection(merged, nowMs)).getJSONObject(dayKey)
        val localOnlyIds = listOf(
            ListenStatsDailyStore.LEGACY_DEVICE_ID,
            ListenStatsDailyStore.HISTORY_BACKFILL_DEVICE_ID
        )

        assertEquals(25L, summary.totalSeconds)
        assertEquals(25L, summary.dailySeconds.getValue(dayKey))
        assertEquals(25L, summary.songs.getValue("wy:track").seconds)
        assertEquals(8L, summary.songs.getValue("wy:track").count)
        localOnlyIds.forEach { id ->
            assertTrue(mergedDay.getJSONObject("totalByDevice").has(id))
            assertFalse(cloudDay.getJSONObject("totalByDevice").has(id))
            assertTrue(mergedDay.getJSONObject("songs").getJSONObject("wy:track").getJSONObject("devices").has(id))
            assertFalse(cloudDay.getJSONObject("songs").getJSONObject("wy:track").getJSONObject("devices").has(id))
            listOf("artists", "platforms").forEach { field ->
                val label = if (field == "artists") "Artist" else "wy"
                assertTrue(mergedDay.getJSONObject(field).getJSONObject(label).has(id))
                assertFalse(cloudDay.getJSONObject(field).getJSONObject(label).has(id))
            }
        }
    }

    @Test
    fun sameSongAccumulatesAcrossCalendarDays() {
        val yesterdayMs = LocalDate.of(2026, 6, 15).minusDays(1).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val yesterday = ListenStatsDailyStore.record("{}", song, deviceA, 20L, true, yesterdayMs)
        val local = ListenStatsDailyStore.record(yesterday, song, deviceA, 10L, true, nowMs)
        val summary = ListenStatsDailyStore.summary(
            ListenStatsDailyStore.mergeLocalAndCloud(local, "{}", nowMs), nowMs
        )

        assertEquals(30L, summary.totalSeconds)
        assertEquals(mapOf(dayKey(1) to 20L, ListeningCalendar.dayKey(nowMs) to 10L), summary.dailySeconds)
        assertEquals(30L, summary.songs.getValue("wy:track").seconds)
        assertEquals(2L, summary.songs.getValue("wy:track").count)
    }

    @Test
    fun totalByDeviceOnlyDayIsNotDroppedWithoutSongFacts() {
        val dayKey = ListeningCalendar.dayKey(nowMs)
        val local = JSONObject().put(dayKey, JSONObject()
            .put("totalByDevice", JSONObject().put(deviceA, 23L))).toString()

        val merged = ListenStatsDailyStore.mergeLocalAndCloud(local, "{}", nowMs)
        val summary = ListenStatsDailyStore.summary(merged, nowMs)

        assertEquals(23L, summary.totalSeconds)
        assertEquals(mapOf(dayKey to 23L), summary.dailySeconds)
        assertTrue(summary.songs.isEmpty())
    }

    @Test
    fun malformedNegativeAndInvalidDeviceComponentsAreIgnored() {
        val dayKey = ListeningCalendar.dayKey(nowMs)
        val invalidDevice = "not a valid device id"
        val raw = JSONObject().put(dayKey, JSONObject()
            .put("totalByDevice", JSONObject()
                .put(deviceA, 9L)
                .put(invalidDevice, 100L)
                .put(deviceB, -5L)
                .put("broken", "not-a-number"))
            .put("artists", JSONObject().put("Artist", JSONObject()
                .put(deviceA, 9L).put(invalidDevice, 100L).put(deviceB, -3L)))
            .put("songs", JSONObject().put("wy:track", JSONObject()
                .put("name", "Track").put("singer", "Artist")
                .put("devices", JSONObject()
                    .put(deviceA, JSONObject().put("sec", 9L).put("cnt", 1L))
                    .put(invalidDevice, JSONObject().put("sec", 100L).put("cnt", 2L))
                    .put(deviceB, JSONObject().put("sec", -8L).put("cnt", -1L)))))).toString()

        val merged = ListenStatsDailyStore.mergeLocalAndCloud(raw, "{}", nowMs)
        val summary = ListenStatsDailyStore.summary(merged, nowMs)
        val day = JSONObject(merged).getJSONObject(dayKey)

        assertEquals(9L, summary.totalSeconds)
        assertEquals(9L, summary.dailySeconds.getValue(dayKey))
        assertEquals(9L, summary.artists.getValue("Artist"))
        assertEquals(9L, summary.songs.getValue("wy:track").seconds)
        assertEquals(1L, summary.songs.getValue("wy:track").count)
        assertFalse(day.getJSONObject("totalByDevice").has(invalidDevice))
        assertFalse(day.getJSONObject("songs").getJSONObject("wy:track").getJSONObject("devices").has(invalidDevice))
    }

    @Test
    fun summingDeviceComponentsSaturatesAtLongMaxValue() {
        val local = ListenStatsDailyStore.record("{}", song, deviceA, Long.MAX_VALUE, true, nowMs)
        val cloud = ListenStatsDailyStore.record("{}", song, deviceB, 1L, true, nowMs)
        val summary = ListenStatsDailyStore.summary(
            ListenStatsDailyStore.mergeLocalAndCloud(local, cloud, nowMs), nowMs
        )

        assertEquals(Long.MAX_VALUE, summary.totalSeconds)
        assertEquals(Long.MAX_VALUE, summary.dailySeconds.getValue(ListeningCalendar.dayKey(nowMs)))
        assertEquals(Long.MAX_VALUE, summary.songs.getValue("wy:track").seconds)
        assertEquals(2L, summary.songs.getValue("wy:track").count)
    }

    private fun recordDaysAgo(raw: String, deviceId: String, seconds: Long, daysAgo: Long): String {
        val timestamp = LocalDate.of(2026, 6, 15).minusDays(daysAgo).atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Build each day's real record independently: recording backwards in time would prune
        // previously generated newer days before the merge-under-test can inspect retention.
        val dayRecord = JSONObject(ListenStatsDailyStore.record("{}", song, deviceId, seconds, true, timestamp))
        return JSONObject(raw).apply {
            dayRecord.keys().forEach { key -> put(key, dayRecord.getJSONObject(key)) }
        }.toString()
    }

    private fun dayKey(daysAgo: Long): String = ListeningCalendar.dayKey(
        LocalDate.of(2026, 6, 15).minusDays(daysAgo)
    )
}
