package xyz.cncnlp.soundwave

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Simulated process death, NOT Android force-stop/device validation. No lifecycle flush runs.
 * Separate memory/disk images model SharedPreferences: failed commits still mutate memory,
 * and synchronous retries persist a still-pending memory generation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListenStatsDurabilityTest {
    private val song = Song("durable", "Durable", "Artist", "wy")

    private fun record(prefs: SharedPreferences, seconds: Long, count: Boolean = false, account: String? = "account-a") =
        ListenStats.updateStats(prefs, song, seconds, count, true, account)

    @Test fun acknowledgedSnapshotSurvivesRestartWithAllSurfacesAndAccountOwnership() {
        val prefs = DiskPreferences()
        assertTrue(record(prefs, 12L, true))
        val restarted = prefs.restart()
        assertEquals("authenticated event must leave guest aggregates untouched", 0L, restarted.getLong("total_sec", 0L))
        assertEquals("authenticated event must leave guest daily facts untouched", 0L,
            ListenStatsDailyStore.summary(restarted.getString("daily_listen_stats_v1", "{}")!!, System.currentTimeMillis()).totalSeconds)
        assertEquals(0L, restarted.getLong("today_sec", 0L))
        assertEquals(0L, restarted.getLong("week_sec", 0L))
        val summary = ListenStatsDailyStore.summary(accountFacts(restarted, "account-a"), System.currentTimeMillis())
        assertEquals(12L, summary.totalSeconds)
        assertEquals(1L, summary.songs.getValue(song.key()).count)
        assertEquals(1L, summary.songs.getValue(song.key()).deviceCounts.values.sum())
        assertEquals(12L, summary.songs.getValue(song.key()).seconds)
        assertEquals(12L, summary.artists.getValue("Artist"))
        assertEquals(12L, summary.platforms.getValue("wy"))
        assertEquals(prefs.getString("play_count_device_id_v1", null), restarted.getString("play_count_device_id_v1", null))
        val accounts = JSONObject(restarted.getString("account_daily_listen_stats_v2", "{}")!!)
        val accountA = AccountDataIdentity.key("account-a")
        assertEquals(setOf(accountA), accounts.keys().asSequence().toSet())
        assertEquals(12L, ListenStatsDailyStore.summary(accounts.getString(accountA), System.currentTimeMillis()).totalSeconds)
        assertTrue(record(restarted, 3L, account = "account-b"))
        val afterB = JSONObject(restarted.restart().getString("account_daily_listen_stats_v2", "{}")!!)
        assertEquals(2, afterB.length())
        assertEquals(accounts.getString(accountA), afterB.getString(accountA))
        assertEquals(3L, ListenStatsDailyStore.summary(afterB.getString(AccountDataIdentity.key("account-b")), System.currentTimeMillis()).totalSeconds)
        assertFalse(afterB.has("account-a"))
    }

    @Test fun failedDiskWriteMustNotBeAcknowledgedFromMemoryOrUnchangedRetry() {
        val prefs = DiskPreferences()
        assertTrue(record(prefs, 5L, true))
        prefs.failWrites = 2
        assertFalse("memory equality is not a disk acknowledgement", record(prefs, 12L, true))
        assertEquals(17L, accountTotal(prefs, "account-a"))
        assertEquals(5L, accountTotal(prefs.restart(), "account-a"))
        // A later write can persist the absolute in-memory snapshot without replaying the delta.
        prefs.failWrites = 0
        assertTrue(record(prefs, 3L))
        val recovered = prefs.restart()
        assertEquals(20L, accountTotal(recovered, "account-a"))
        assertEquals(2L, ListenStatsDailyStore.summary(accountFacts(recovered, "account-a"), System.currentTimeMillis()).songs.getValue(song.key()).count)
    }

    @Test fun retryForcesRealDiskWriteAndDoesNotApplyDurationOrCountTwice() {
        val prefs = DiskPreferences()
        assertTrue(record(prefs, 5L, true))
        prefs.failWrites = 1
        assertTrue(record(prefs, 12L, true))
        val restarted = prefs.restart()
        assertEquals("same-value retry must really reach disk", 17L, accountTotal(restarted, "account-a"))
        assertEquals(2L, ListenStatsDailyStore.summary(accountFacts(restarted, "account-a"), System.currentTimeMillis()).songs.getValue(song.key()).count)
    }

    @Test fun deathBetweenMemoryApplyAndDiskWriteRestoresLastAcknowledgedSnapshot() {
        val prefs = DiskPreferences()
        assertTrue(record(prefs, 5L, true))
        val diskBefore = prefs.disk.toMap()
        prefs.dieBeforeDisk = true
        try {
            record(prefs, 12L, true)
            fail("simulated process must die before acknowledgement")
        } catch (_: ProcessDeath) { /* discard all process memory; no onDestroy */ }
        assertEquals(diskBefore, prefs.restart().all)
        assertEquals(5L, accountTotal(prefs.restart(), "account-a"))
    }

    @Test fun unconfirmedInstallationIdentityIsNotAcknowledged() {
        val prefs = DiskPreferences()
        prefs.failDeviceId = true
        assertFalse(record(prefs, 12L, true))
        assertEquals(0L, prefs.restart().getLong("total_sec", 0L))
        assertEquals(0L, accountTotal(prefs.restart(), "account-a"))
        assertFalse(prefs.restart().contains("play_count_device_id_v1"))
    }

    /** Real Android SharedPreferencesImpl + real temporary XML files under Robolectric.
     * Reconstructing the implementation discards memory; this is not an OS force-stop.
     */
    @Test fun frameworkDiskFailureAndFreshInstanceReproduceFalseAcknowledgement() {
        val root = Files.createTempDirectory("stats-framework").toFile()
        val parent = File(root, "prefs").apply { mkdirs() }
        val file = File(parent, "stats.xml")
        val parked = File(root, "parked")
        try {
            val prefs = frameworkPreferences(file)
            assertTrue(record(prefs, 5L, true))
            var blocked = false
            val failing = object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    var isDelta = false
                    return object : SharedPreferences.Editor by editor {
                        override fun putLong(key: String, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
                        override fun putString(key: String, value: String?): SharedPreferences.Editor {
                            if (key == "account_daily_listen_stats_v2" && value?.contains("17") == true) isDelta = true
                            editor.putString(key, value)
                            return this
                        }
                        override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                        override fun commit(): Boolean {
                            if (isDelta && !blocked) {
                                assertTrue(parent.renameTo(parked))
                                parent.writeText("block parent directory creation")
                                blocked = true
                            }
                            return editor.commit()
                        }
                    }
                }
            }
            val acknowledged = record(failing, 12L, true)
            assertEquals(17L, accountTotal(prefs, "account-a"))
            assertTrue(parent.delete())
            assertTrue(parked.renameTo(parent))
            assertEquals("fresh instance must read the last disk snapshot", 5L, accountTotal(frameworkPreferences(file), "account-a"))
            assertFalse("failed framework disk writes must not be acknowledged", acknowledged)
            // Android retries a pending generation even when the values are unchanged.
            assertTrue(prefs.edit().putLong("total_sec", 17L).commit())
            assertEquals(17L, accountTotal(frameworkPreferences(file), "account-a"))
        } finally { root.deleteRecursively() }
    }

    @Test fun frameworkAcknowledgedStatsAreReloadedFromXmlWithoutShutdownFlush() {
        val root = Files.createTempDirectory("stats-restart").toFile()
        val file = File(root, "stats.xml")
        try {
            val prefs = frameworkPreferences(file)
            assertTrue(record(prefs, 12L, true))
            val restarted = frameworkPreferences(file)
            assertEquals(prefs.all, restarted.all)
            assertEquals(12L, accountTotal(restarted, "account-a"))
        } finally { root.deleteRecursively() }
    }

    @Test fun frameworkInterruptedReplacementRecoversLastAcknowledgedBackup() {
        val root = Files.createTempDirectory("stats-backup").toFile()
        val file = File(root, "stats.xml")
        try {
            val prefs = frameworkPreferences(file)
            assertTrue(record(prefs, 12L, true))
            val expected = prefs.all
            // Android renames the last confirmed XML to .bak before replacing it.
            assertTrue(file.renameTo(File(root, "stats.xml.bak")))
            file.writeText("<map>interrupted replacement")
            assertEquals(expected, frameworkPreferences(file).all)
        } finally { root.deleteRecursively() }
    }

    @Test fun snapshotsRecheckDiskEvenWithoutTheOriginalQueueFailureLatch() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        ListenStats.init(context)
        val field = ListenStats::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val previous = field.get(ListenStats)
        val prefs = DiskPreferences()
        try {
            assertTrue(record(prefs, 5L, true))
            prefs.failWrites = 100
            assertFalse(record(prefs, 12L, true))
            field.set(ListenStats, prefs) // Same storage, fresh snapshot caller/queue.
            RuntimeEnvironment.getApplication().getSharedPreferences("soundwave", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("logged_in", true).putString("user", "account-a").commit()
            listOf<() -> String>(
                { ListenStats.localDailyListenStats(context) },
                { ListenStats.accountDailyListenStats(context) },
                { ListenStats.accountDailyListenStatsForAccount(context, "account-a", "{}") }
            ).forEach { snapshot ->
                try {
                    snapshot()
                    fail("unconfirmed memory must never become a sync snapshot")
                } catch (failure: ListenStatsSnapshotException) {
                    assertEquals(ListenStatsWriteQueue.Failure.UNCONFIRMED, failure.reason)
                }
            }
            assertFalse(ListenStats.backfillFromHistory(context, listOf(song)))
            prefs.failWrites = 0
            val recovered = ListenStats.localDailyListenStats(context)
            assertEquals(17L, ListenStatsDailyStore.summary(recovered, System.currentTimeMillis()).totalSeconds)
            assertEquals(17L, accountTotal(prefs.restart(), "account-a"))
        } finally { field.set(ListenStats, previous) }
    }

    private fun frameworkPreferences(file: File): SharedPreferences {
        val constructor = Class.forName("android.app.SharedPreferencesImpl")
            .getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(file, 0) as SharedPreferences
    }

    private fun accountFacts(preferences: SharedPreferences, account: String): String =
        JSONObject(preferences.getString("account_daily_listen_stats_v2", "{}")!!)
            .optString(AccountDataIdentity.key(account), "{}")

    private fun accountTotal(preferences: SharedPreferences, account: String): Long =
        ListenStatsDailyStore.summary(accountFacts(preferences, account), System.currentTimeMillis()).totalSeconds

    private class ProcessDeath : Error()

    private class DiskPreferences(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
        private val memory = initial.toMutableMap()
        val disk = initial.toMutableMap()
        var failWrites = 0
        var failDeviceId = false
        var dieBeforeDisk = false
        fun restart() = DiskPreferences(disk.toMap())
        override fun getAll(): MutableMap<String, *> = memory.toMutableMap()
        override fun contains(key: String) = memory.containsKey(key)
        override fun getString(key: String, defValue: String?): String? = memory[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = (memory[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String, defValue: Int) = memory[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = memory[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = memory[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = memory[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false
            private fun put(key: String, value: Any?) = apply { changes[key] = value }
            override fun putString(key: String, value: String?) = put(key, value)
            override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String, value: Int) = put(key, value)
            override fun putLong(key: String, value: Long) = put(key, value)
            override fun putFloat(key: String, value: Float) = put(key, value)
            override fun putBoolean(key: String, value: Boolean) = put(key, value)
            override fun remove(key: String) = put(key, null)
            override fun clear() = apply { clear = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                val previous = memory.toMap()
                if (clear) memory.clear()
                changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                if (memory == previous && memory == disk) return true // No pending disk generation.
                if (dieBeforeDisk) throw ProcessDeath()
                if (failDeviceId && changes.containsKey("play_count_device_id_v1")) return false
                if (failWrites > 0) { failWrites--; return false }
                disk.clear()
                disk.putAll(memory)
                return true
            }
        }
    }
}
