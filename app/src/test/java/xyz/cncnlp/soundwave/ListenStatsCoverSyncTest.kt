package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListenStatsCoverSyncTest {
    private val deviceId = "00000000-0000-0000-0000-000000000001"
    private val nowMs = System.currentTimeMillis()
    private val songKey = "wy:track"
    private val localCovers = listOf(
        "file:///storage/emulated/0/private-cover.jpg",
        "  FiLe:///data/user/0/private-cover.jpg  ",
        "content://media/external/images/123",
        "\tCONTENT://private/cover\n",
        "/storage/emulated/0/private-cover.jpg",
        "  /data/user/0/private-cover.jpg  ",
        "C:\\Users\\private\\cover.jpg",
        "D:/private/cover.jpg",
        "\\\\device\\private\\cover.jpg"
    )

    @Test
    fun cloudAndLocalDeviceProjectionsStripLocalCoversButKeepListeningFacts() {
        localCovers.forEach { cover ->
            val raw = rawStats(cover)
            listOf(
                ListenStatsDailyStore.cloudProjection(raw, nowMs),
                ListenStatsDailyStore.localDeviceProjection(raw, deviceId, nowMs)
            ).forEach { projected ->
                assertEquals("Local cover must not leave device: $cover", "", coverOf(projected))
                assertFactsUnchanged(projected)
            }
        }
    }

    @Test
    fun localProjectionAndRecordingKeepLocalCoversWhileAccountProjectionStripsThem() {
        localCovers.forEach { cover ->
            val song = Song("track", "Track", "Artist", "wy", cover = cover)
            val recorded = ListenStatsDailyStore.record("{}", song, deviceId, 37L, true, nowMs)
            assertEquals(cover, coverOf(recorded))
            assertEquals(cover, coverOf(ListenStatsDailyStore.localProjection(recorded, nowMs)))
            val merged = ListenStatsDailyStore.mergeLocalAndCloud(recorded, "{}", nowMs)
            assertEquals(cover, coverOf(merged))
            val accountProjection = ListenStatsDailyStore.cloudProjection(merged, nowMs)
            assertEquals("", coverOf(accountProjection))
            assertFactsUnchanged(accountProjection)
        }
    }

    @Test
    fun remoteAndDataImageCoversArePreservedWithinExistingSizeLimit() {
        val covers = listOf(
            "",
            "https://cdn.example.com/cover.jpg?size=500",
            "http://cdn.example.com/cover.jpg",
            "//cdn.example.com/cover.jpg",
            "data:image/png;base64,aGVsbG8=",
            "https://cdn.example.com/" + "a".repeat(4096),
            "data:image/png;base64," + "A".repeat(4096)
        )
        covers.forEach { cover ->
            val raw = rawStats(cover)
            listOf(
                ListenStatsDailyStore.cloudProjection(raw, nowMs),
                ListenStatsDailyStore.localDeviceProjection(raw, deviceId, nowMs),
                ListenStatsDailyStore.localProjection(raw, nowMs)
            ).forEach { projected ->
                assertEquals(cover.take(4096), coverOf(projected))
                assertFactsUnchanged(projected)
            }
        }
    }

    @Test
    fun localReferenceIsCheckedBeforeSizeTruncation() {
        // Truncating first would hide a local reference behind leading whitespace.
        val cover = " ".repeat(4096) + "file:///private/cover.jpg"
        assertEquals("", coverOf(ListenStatsDailyStore.cloudProjection(rawStats(cover), nowMs)))
    }

    @Test
    fun accountAndWebDavBundleSerializationCannotLeakStatisticsCoverPaths() {
        localCovers.forEach { cover ->
            val bundle = LocalStore.UserBundle(dailyListenStatsJson = rawStats(cover))
            listOf(false, true).forEach { compact ->
                val payload = bundle.toCloudJson(
                    includeDailyListenStats = true, includeLocalUris = false, compact = compact)
                val stats = payload.getJSONObject("dailyListenStats").toString()
                assertEquals("", coverOf(stats))
                assertFalse("Serialized stats contain local cover: $cover", stats.contains(cover))
                assertFactsUnchanged(stats)
            }
        }
    }

    private fun rawStats(cover: String): String = JSONObject()
        .put(ListeningCalendar.dayKey(nowMs), JSONObject()
            .put("totalByDevice", JSONObject().put(deviceId, 37L))
            .put("artists", JSONObject().put("Artist", JSONObject().put(deviceId, 37L)))
            .put("platforms", JSONObject().put("wy", JSONObject().put(deviceId, 37L)))
            .put("songs", JSONObject().put(songKey, JSONObject()
                .put("name", "Track")
                .put("singer", "Artist")
                .put("cover", cover)
                .put("devices", JSONObject().put(deviceId, JSONObject().put("sec", 37L).put("cnt", 1L))))))
        .toString()

    private fun coverOf(raw: String): String = JSONObject(raw)
        .getJSONObject(ListeningCalendar.dayKey(nowMs))
        .getJSONObject("songs").getJSONObject(songKey).getString("cover")

    private fun assertFactsUnchanged(raw: String) {
        val summary = ListenStatsDailyStore.summary(raw, nowMs)
        assertEquals(37L, summary.totalSeconds)
        assertEquals(mapOf("Artist" to 37L), summary.artists)
        assertEquals(mapOf("wy" to 37L), summary.platforms)
        val song = summary.songs.getValue(songKey)
        assertEquals("Track", song.name)
        assertEquals("Artist", song.singer)
        assertEquals(37L, song.seconds)
        assertEquals(1L, song.count)
        assertEquals(mapOf(deviceId to 1L), song.deviceCounts)
    }
}
