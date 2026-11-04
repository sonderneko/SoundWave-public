package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class ListenStatsProfileTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val profileA = ProfileId.of("listen-stats-profile-a")
    private val profileB = ProfileId.of("listen-stats-profile-b")
    private val preferenceNames = listOf("listen_stats_v1", "soundwave", profileA.preferencesName, profileB.preferencesName)
    private lateinit var originalPreferences: Map<String, Map<String, *>>
    private val cachedPreferencesField = ListenStats::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    private var previousCachedPreferences: SharedPreferences? = null

    @Before
    fun snapshotAndClearPreferences() {
        previousCachedPreferences = cachedPreferencesField.get(ListenStats) as? SharedPreferences
        cachedPreferencesField.set(ListenStats, null)
        originalPreferences = preferenceNames.associateWith { prefs(it).all.toMap() }
        preferenceNames.forEach { prefs(it).edit().clear().commit() }
        prefs("soundwave").edit().putBoolean("logged_in", false).commit()
    }

    @After
    fun restorePreferences() {
        originalPreferences.forEach { (name, values) ->
            val editor = prefs(name).edit().clear()
            values.forEach { (key, value) -> putPreference(editor, key, value) }
            editor.commit()
        }
        cachedPreferencesField.set(ListenStats, previousCachedPreferences)
    }

    @Test
    fun favoritesUseAggregateArtistPlayCountsRatherThanDuration() {
        val a1 = song("a1", "Artist A", "qq")
        val a2 = song("a2", "Artist A", "qq")
        val b = song("b", "Artist B", "qq")
        repeat(2) { ListenStats.markPlay(context, profileA, a1); ListenStats.markPlay(context, profileA, a2) }
        repeat(3) { ListenStats.markPlay(context, profileA, b) }
        ListenStats.addDuration(context, profileA, b, 9000)
        assertEquals("Artist A", ListenStats.topArtists(context, profileA).first().singer)
        assertEquals(4, ListenStats.topArtists(context, profileA).first().cnt)
        assertEquals("qq:b", ListenStats.topSongs(context, profileA).first().key)
    }

    @Test
    fun twoProfilesIsolateReadWriteAndAccumulationForEveryStatsSurface() {
        val songA = song("song-a", "Artist A", "qq")
        val songB = song("song-b", "Artist B", "qq")

        ListenStats.addDuration(context, profileA, songA, 30L)
        ListenStats.markPlay(context, profileA, songA)
        ListenStats.addDuration(context, profileA, songA, 15L)
        ListenStats.markPlay(context, profileA, songA)
        ListenStats.addDuration(context, profileB, songB, 7L)
        ListenStats.markPlay(context, profileB, songB)
        ListenStats.recordPlatform(context, profileA, "qq", 5L)
        ListenStats.recordPlatform(context, profileB, "qq", 2L)

        assertEquals(45L, ListenStats.totalSec(context, profileA))
        assertEquals(7L, ListenStats.totalSec(context, profileB))
        assertEquals(45L, ListenStats.todaySec(context, profileA))
        assertEquals(7L, ListenStats.todaySec(context, profileB))
        assertEquals(45L, ListenStats.weekSec(context, profileA))
        assertEquals(7L, ListenStats.weekSec(context, profileB))
        assertEquals(listOf(ListenStats.SongStat("qq:song-a", "song-a", "Artist A", "", 45L, 2)), ListenStats.topSongs(context, profileA))
        assertEquals(listOf(ListenStats.SongStat("qq:song-b", "song-b", "Artist B", "", 7L, 1)), ListenStats.topSongs(context, profileB))
        assertEquals(listOf(ListenStats.ArtistStat("Artist A", 45L, 2)), ListenStats.topArtists(context, profileA))
        assertEquals(listOf(ListenStats.ArtistStat("Artist B", 7L, 1)), ListenStats.topArtists(context, profileB))
        assertEquals(listOf("qq" to 50L), ListenStats.topPlatforms(context, profileA))
        assertEquals(listOf("qq" to 9L), ListenStats.topPlatforms(context, profileB))
        assertEquals(1, ListenStats.activeDaysLast7(context, profileA))
        assertEquals(1, ListenStats.activeDaysLast7(context, profileB))
        assertEquals(45L, ListenStats.monthDailySeconds(context, profileA).sum())
        assertEquals(7L, ListenStats.monthDailySeconds(context, profileB).sum())

        val dayKey = ListeningCalendar.dayKey(System.currentTimeMillis())
        val expectedKeys = setOf(
            "total_sec", "today_date", "today_sec", "week_label", "week_sec",
            "day_sec_$dayKey", "platform_map_v1", "song_map_v1", "artist_map_v1", "play_count_device_id_v1",
            "daily_listen_stats_v1", "listen_stats_v1_archive_complete"
        )
        assertEquals(expectedKeys, prefs(profileA.preferencesName).all.keys)
        assertEquals(expectedKeys, prefs(profileB.preferencesName).all.keys)
        assertTrue("profile APIs must not initialize or write the legacy stats file", prefs("listen_stats_v1").all.isEmpty())
    }

    @Test
    fun legacyApisKeepTheirOriginalPreferenceFileKeysAndBehavior() {
        val legacySong = song("legacy-song", "Legacy Artist", "wy")

        ListenStats.addPlay(context, legacySong, 11L)

        val legacy = prefs("listen_stats_v1")
        val dayKey = ListeningCalendar.dayKey(System.currentTimeMillis())
        assertEquals(
            setOf(
                "total_sec", "today_date", "today_sec", "week_label", "week_sec",
                "day_sec_$dayKey", "platform_map_v1", "song_map_v1", "artist_map_v1", "play_count_device_id_v1",
                "daily_listen_stats_v1", "listen_stats_v1_archive_complete"
            ),
            legacy.all.keys
        )
        assertEquals(11L, ListenStats.totalSec(context))
        assertEquals(11L, ListenStats.todaySec(context))
        assertEquals(11L, ListenStats.weekSec(context))
        assertEquals(listOf(ListenStats.SongStat("wy:legacy-song", "legacy-song", "Legacy Artist", "", 11L, 1)), ListenStats.topSongs(context))
        assertEquals(listOf(ListenStats.ArtistStat("Legacy Artist", 11L, 1, "wy")), ListenStats.topArtists(context))
        assertEquals(listOf("wy" to 11L), ListenStats.topPlatforms(context))
        assertTrue(prefs(profileA.preferencesName).all.isEmpty())
        assertTrue(prefs(profileB.preferencesName).all.isEmpty())
    }

    @Test
    fun accountCloudProjectionsDoNotMixDeviceStatsBetweenAccounts() {
        val songA = song("account-a-song", "Artist A", "wy")
        val songB = song("account-b-song", "Artist B", "wy")
        prefs("soundwave").edit().putBoolean("logged_in", true).putString("user", "Account-A").commit()
        assertTrue(ListenStats.addPlay(context, songA, 90L))
        prefs("soundwave").edit().putString("user", "account-b").commit()
        assertTrue(ListenStats.addPlay(context, songB, 45L))

        prefs("soundwave").edit().putString("user", "Account-A").commit()
        val accountA = ListenStats.accountDailyListenStatsForAccount(context, "Account-A", "{}")
        prefs("soundwave").edit().putString("user", "account-b").commit()
        val accountB = ListenStats.accountDailyListenStatsForAccount(context, "account-b", "{}")
        val allLocal = ListenStats.localDailyListenStats(context)

        assertTrue(ListenStatsDailyStore.summary(accountA, System.currentTimeMillis()).songs.containsKey(songA.key()))
        assertFalse(ListenStatsDailyStore.summary(accountA, System.currentTimeMillis()).songs.containsKey(songB.key()))
        assertTrue(ListenStatsDailyStore.summary(accountB, System.currentTimeMillis()).songs.containsKey(songB.key()))
        assertFalse(ListenStatsDailyStore.summary(accountB, System.currentTimeMillis()).songs.containsKey(songA.key()))
        assertEquals(45L, ListenStatsDailyStore.summary(allLocal, System.currentTimeMillis()).totalSeconds)
    }

    @Test
    fun profileCoverPersistenceDropsCredentialQueriesButLegacyRemainsUnchanged() {
        val publicCoverUrl = "https://cdn.example.invalid/covers/public.jpg"
        val signedCoverUrl = "https://cdn.example.invalid/covers/signed.jpg?width=512&access_token=profile-cover-secret&expires=1999999999&signature=profile-signature-secret"

        ListenStats.addPlay(
            context,
            profileA,
            song("public-cover", "Public Artist", "wy").copy(cover = publicCoverUrl),
            5L,
        )
        ListenStats.addPlay(
            context,
            profileB,
            song("signed-cover", "Signed Artist", "wy").copy(cover = signedCoverUrl),
            7L,
        )

        val publicSongMap = prefs(profileA.preferencesName).getString("song_map_v1", null)!!
        val publicEntry = JSONObject(publicSongMap).getJSONObject("wy:public-cover")
        assertEquals("queryless public covers remain available in profile stats", publicCoverUrl, publicEntry.getString("cover"))

        val signedSongMap = prefs(profileB.preferencesName).getString("song_map_v1", null)!!
        val signedEntry = JSONObject(signedSongMap).getJSONObject("wy:signed-cover")
        assertFalse("profile stats omit the entire credential-bearing cover URL", signedEntry.has("cover"))
        assertFalse(signedEntry.has("access_token"))
        assertFalse(signedEntry.has("expires"))
        assertFalse(signedEntry.has("signature"))
        assertFalse(signedSongMap.contains("profile-cover-secret"))
        assertFalse(signedSongMap.contains("profile-signature-secret"))

        ListenStats.addPlay(
            context,
            song("legacy-signed-cover", "Legacy Artist", "wy").copy(cover = signedCoverUrl),
            7L,
        )
        val legacySongMap = prefs("listen_stats_v1").getString("song_map_v1", null)!!
        val legacyEntry = JSONObject(legacySongMap).getJSONObject("wy:legacy-signed-cover")
        assertEquals("legacy no-ID behavior keeps its original cover serialization", signedCoverUrl, legacyEntry.getString("cover"))
        assertTrue(legacySongMap.contains("profile-cover-secret"))
    }

    @Test
    fun malformedSongMapAbortsDurationAtomicallyAndContextDataReportsFailure() {
        val legacy = prefs("listen_stats_v1")
        legacy.edit().putString("song_map_v1", "{broken").commit()
        val played = song("broken-map-song", "Artist", "wy")

        assertFalse(ListenStats.addDuration(context, played, 12L))
        assertFalse(legacy.contains("total_sec"))
        assertFalse(legacy.contains("today_sec"))
        assertEquals("{broken", legacy.getString("song_map_v1", null))

        val data = ContextData(context, xyz.cncnlp.soundwave.migration.ProfileWorkIdentity.runtime())
        assertFalse("failed play-count write must not be reported as success", data.markPlay(played))
        assertFalse("failed duration write must not be reported as success", data.addDuration(played, 8L))
        assertFalse(legacy.contains("total_sec"))
        assertEquals("{broken", legacy.getString("song_map_v1", null))
    }

    @Test
    fun memoryAppliedCommitFailureIsUnconfirmedWithoutReapplyingTheDelta() {
        val song = song("commit-failure-song", "Commit Failure Artist", "wy")
        val preferences = AppliedButReportedFailurePreferences(prefs(profileA.preferencesName))

        assertFalse(ListenStats.updateStats(
            preferences,
            song,
            12L,
            incrementCount = false,
            dropSensitiveCoverQuery = true
        ))

        assertEquals(12L, ListenStats.totalSec(context, profileA))
        assertEquals(12L, ListenStats.topSongs(context, profileA).single().sec)
        assertEquals(listOf(ListenStats.ArtistStat("Commit Failure Artist", 12L, 0, "wy")), ListenStats.topArtists(context, profileA))
        assertEquals(listOf("wy" to 12L), ListenStats.topPlatforms(context, profileA))
    }

    @Test
    fun legacyUndatedPlayCountsMigrateLocallyAndStayOutOfCloudProjection() {
        prefs("listen_stats_v1").edit()
            .putString("song_map_v1", JSONObject().put("wy:legacy", JSONObject()
                .put("name", "Legacy")
                .put("singer", "Artist")
                .put("sec", 0L)
                .put("cnt", 4)).toString())
            .commit()

        val first = ListenStats.accountPlayCountCounters(context)
        val second = ListenStats.accountPlayCountCounters(context)
        val deviceId = first.getValue("wy:legacy").keys.single()

        assertEquals(4L, first.getValue("wy:legacy").getValue(deviceId))
        assertEquals(first, second)
        assertEquals(ListenStatsDailyStore.LEGACY_DEVICE_ID, deviceId)
        val cloud = JSONObject(ListenStats.accountDailyListenStats(context))
        val todaySongs = cloud.optJSONObject(ListeningCalendar.dayKey(System.currentTimeMillis()))
            ?.optJSONObject("songs")
        assertFalse("undated legacy plays cannot be assigned a cloud date", todaySongs?.has("wy:legacy") == true)
        assertTrue(ListenStats.markPlay(context, song("legacy", "Artist", "wy")))
        assertEquals(5, ListenStats.topSongs(context).single().cnt)
    }

    @Test
    fun concurrentPlayCallbacksDoNotLosePerDeviceIncrements() {
        val workerCount = 8
        val incrementsPerWorker = 20
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val song = song("concurrent", "Artist", "wy")
        val threads = (0 until workerCount).map {
            Thread {
                ready.countDown()
                try {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(incrementsPerWorker) {
                        check(ListenStats.markPlay(context, profileA, song))
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }.apply { start() }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        threads.forEach { it.join(10_000L) }
        failure.get()?.let { throw AssertionError("concurrent play count failed", it) }

        val expected = (workerCount * incrementsPerWorker).toLong()
        val legacyCounter = ListenStats.accountPlayCountCounters(context)
        val profileSong = JSONObject(prefs(profileA.preferencesName).getString("song_map_v1", "{}")!!)
            .getJSONObject("wy:concurrent")
        val profileDeviceCounts = profileSong.getJSONObject("playCountByDevice")

        assertEquals(expected.toInt(), ListenStats.topSongs(context, profileA).single().cnt)
        assertEquals(expected, profileDeviceCounts.getLong(profileDeviceCounts.keys().next()))
        assertTrue("profile counters must not leak into the legacy account namespace", legacyCounter["wy:concurrent"].isNullOrEmpty())
    }

    @Test
    fun accountPlayCountMergeTakesPerDeviceMaximumAndCanBeRepeated() {
        val song = song("merged", "Artist", "wy")
        repeat(3) { assertTrue(ListenStats.markPlay(context, song)) }
        val local = ListenStats.accountPlayCountCounters(context).getValue("wy:merged")
        val localDevice = local.keys.single()
        val remote = mapOf("wy:merged" to mapOf(localDevice to 2L, "other-device" to 4L))

        assertTrue(ListenStats.mergeAccountPlayCountCounters(context, remote))
        assertTrue(ListenStats.mergeAccountPlayCountCounters(context, remote))

        assertEquals(7, ListenStats.topSongs(context).single().cnt)
        assertEquals(mapOf(localDevice to 3L, "other-device" to 4L),
            ListenStats.accountPlayCountCounters(context).getValue("wy:merged"))
    }

    @Test
    fun profileIdsRejectUnsafeIdentifiers() {
        listOf("", "../escape", "profile/name", "profile.name", "含中文", "a".repeat(65)).forEach { rawId ->
            var rejected = false
            try {
                ProfileId.of(rawId)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("unsafe profile ID should be rejected: $rawId", rejected)
        }
    }

    private fun song(id: String, singer: String, source: String) =
        Song(id = id, name = id, singer = singer, source = source)

    private class AppliedButReportedFailurePreferences(
        private val delegate: SharedPreferences
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
                override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean {
                    val committed = editor.commit()
                    // Allow initialization, then make the delta's acknowledgement uncertain.
                    return committed && delegate.getLong("total_sec", 0L) < 12L
                }
            }
        }
    }

    private fun prefs(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
