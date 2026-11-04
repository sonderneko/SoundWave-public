package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot
import xyz.cncnlp.soundwave.migration.SnapshotStorage
import xyz.cncnlp.soundwave.migration.StoredRawValue

@RunWith(RobolectricTestRunner::class)
class LegacyUnassignedSnapshotTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private lateinit var sourceBefore: Map<String, *>
    private lateinit var destinationBefore: Map<String, *>

    @Before
    fun clearPreferencesForTest() {
        val source = context.getSharedPreferences(LegacyUnassignedSnapshot.SOURCE_PREFERENCES, Context.MODE_PRIVATE)
        val destination = context.getSharedPreferences(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
        sourceBefore = source.all.toMap()
        destinationBefore = destination.all.toMap()
        source.edit().clear().commit()
        destination.edit().clear().commit()
    }

    @After
    fun restorePreferences() {
        restore(context.getSharedPreferences(LegacyUnassignedSnapshot.SOURCE_PREFERENCES, Context.MODE_PRIVATE), sourceBefore)
        restore(context.getSharedPreferences(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE), destinationBefore)
    }

    @Test
    fun productionAdapterCopiesFromLegacyPreferencesIntoSeparateSnapshotPreference() {
        val raw = " { \"unknown\" : \"preserved\" } "
        val source = context.getSharedPreferences(LegacyUnassignedSnapshot.SOURCE_PREFERENCES, Context.MODE_PRIVATE)
        source.edit().putString(LegacyUnassignedSnapshot.USER_DATA_KEY, raw).commit()

        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(context))
        assertEquals(raw, source.getString(LegacyUnassignedSnapshot.USER_DATA_KEY, null))
        val destination = context.getSharedPreferences(LegacyUnassignedSnapshot.SNAPSHOT_PREFERENCES, Context.MODE_PRIVATE)
        val snapshot = JSONObject(destination.getString(LegacyUnassignedSnapshot.SNAPSHOT_KEY, null))
        assertEquals("legacy-unassigned", snapshot.getString("kind"))
        assertEquals(raw, snapshot.getJSONObject("source").getJSONObject("payloads")
            .getString(LegacyUnassignedSnapshot.USER_DATA_KEY))
        assertFalse(destination.contains(LegacyUnassignedSnapshot.USER_DATA_KEY))
    }

    @Test
    fun copiesRawJsonVerbatimAndDoesNotMutateSource() {
        val userData = " { \"futureField\" : [1,  2], \"favs\":[], \"pls\":[] }\n"
        val history = "[ { \"id\" : \"song\", \"future\" : { \"v\" :  3 } } ]"
        val storage = FakeStorage(mapOf(
            LegacyUnassignedSnapshot.USER_DATA_KEY to userData,
            LegacyUnassignedSnapshot.PLAY_HISTORY_KEY to history
        ))

        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(storage, 1234L))
        val saved = JSONObject(storage.snapshot!!)
        assertEquals("legacy-unassigned", saved.getString("kind"))
        assertEquals(1, saved.getInt("version"))
        assertEquals(1234L, saved.getLong("capturedAtEpochMs"))
        val source = saved.getJSONObject("source")
        val payloads = source.getJSONObject("payloads")
        assertEquals(userData, payloads.getString(LegacyUnassignedSnapshot.USER_DATA_KEY))
        assertEquals(history, payloads.getString(LegacyUnassignedSnapshot.PLAY_HISTORY_KEY))
        assertEquals(setOf(LegacyUnassignedSnapshot.USER_DATA_KEY, LegacyUnassignedSnapshot.PLAY_HISTORY_KEY),
            source.getJSONArray("presentKeys").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() })
        assertEquals(userData, storage.source[LegacyUnassignedSnapshot.USER_DATA_KEY])
        assertEquals(history, storage.source[LegacyUnassignedSnapshot.PLAY_HISTORY_KEY])
        assertEquals(1, storage.writeCount)
    }

    @Test
    fun preservesAbsentSourceKeysExplicitly() {
        val history = "[]"
        val storage = FakeStorage(mapOf(LegacyUnassignedSnapshot.PLAY_HISTORY_KEY to history))

        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(storage, 8L))
        val source = JSONObject(storage.snapshot!!).getJSONObject("source")
        val payloads = source.getJSONObject("payloads")
        assertFalse(payloads.has(LegacyUnassignedSnapshot.USER_DATA_KEY))
        assertEquals(history, payloads.getString(LegacyUnassignedSnapshot.PLAY_HISTORY_KEY))
        assertEquals(listOf(LegacyUnassignedSnapshot.PLAY_HISTORY_KEY),
            (0 until source.getJSONArray("presentKeys").length()).map { source.getJSONArray("presentKeys").getString(it) })
    }

    @Test
    fun malformedSourceNeverWritesOrClaimsCompletion() {
        val storage = FakeStorage(mapOf(LegacyUnassignedSnapshot.USER_DATA_KEY to "{broken"))

        assertEquals(LegacyUnassignedSnapshot.Result.INVALID_SOURCE, LegacyUnassignedSnapshot.capture(storage, 9L))
        assertNull(storage.snapshot)
        assertEquals(0, storage.writeCount)
        assertEquals("{broken", storage.source[LegacyUnassignedSnapshot.USER_DATA_KEY])
    }

    @Test
    fun failedCommitNeverClaimsCompletionOrTouchesSource() {
        val raw = "{\"unknown\":true}"
        val storage = FakeStorage(mapOf(LegacyUnassignedSnapshot.USER_DATA_KEY to raw)).apply {
            commitResult = false
            persistOnFailedCommit = false
        }

        assertEquals(LegacyUnassignedSnapshot.Result.WRITE_FAILED, LegacyUnassignedSnapshot.capture(storage, 10L))
        assertNull(storage.snapshot)
        assertEquals(1, storage.writeCount)
        assertEquals(raw, storage.source[LegacyUnassignedSnapshot.USER_DATA_KEY])
    }

    @Test
    fun failedReadbackDoesNotClaimCompletionAndNeverOverwritesFirstSnapshot() {
        val original = "{ \"v\" : 1 }"
        val storage = FakeStorage(mapOf(LegacyUnassignedSnapshot.USER_DATA_KEY to original)).apply {
            readbackOverride = { null }
        }

        assertEquals(LegacyUnassignedSnapshot.Result.READBACK_FAILED, LegacyUnassignedSnapshot.capture(storage, 11L))
        val firstSnapshot = storage.snapshot
        assertTrue(firstSnapshot != null)
        storage.readbackOverride = null
        storage.source[LegacyUnassignedSnapshot.USER_DATA_KEY] = "{\"v\":2}"

        assertEquals(LegacyUnassignedSnapshot.Result.ALREADY_COMPLETE, LegacyUnassignedSnapshot.capture(storage, 12L))
        assertEquals(firstSnapshot, storage.snapshot)
        assertEquals(1, storage.writeCount)
        assertEquals("{\"v\":2}", storage.source[LegacyUnassignedSnapshot.USER_DATA_KEY])
    }

    @Test
    fun repeatedCaptureIsIdempotentAfterSourceChanges() {
        val first = "{\"favs\":[],\"future\":\"first\"}"
        val storage = FakeStorage(mapOf(LegacyUnassignedSnapshot.USER_DATA_KEY to first))

        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(storage, 20L))
        val originalSnapshot = storage.snapshot
        storage.source[LegacyUnassignedSnapshot.USER_DATA_KEY] = "{\"favs\":[],\"future\":\"changed\"}"
        storage.source[LegacyUnassignedSnapshot.PLAY_HISTORY_KEY] = "[]"

        assertEquals(LegacyUnassignedSnapshot.Result.ALREADY_COMPLETE, LegacyUnassignedSnapshot.capture(storage, 21L))
        assertEquals(originalSnapshot, storage.snapshot)
        assertEquals(1, storage.writeCount)
    }

    @Test
    fun emptySourceStillCreatesVersionedSnapshotWithBothKeysAbsent() {
        val storage = FakeStorage(emptyMap())

        assertEquals(LegacyUnassignedSnapshot.Result.COPIED, LegacyUnassignedSnapshot.capture(storage, 30L))
        val source = JSONObject(storage.snapshot!!).getJSONObject("source")
        assertEquals(0, source.getJSONArray("presentKeys").length())
        assertEquals(0, source.getJSONObject("payloads").length())
    }

    private class FakeStorage(initialSource: Map<String, String>) : SnapshotStorage {
        val source = initialSource.toMutableMap()
        var snapshot: String? = null
        var writeCount = 0
        var commitResult = true
        var persistOnFailedCommit = true
        var readbackOverride: ((String?) -> String?)? = null

        override fun readSource(key: String): StoredRawValue = if (source.containsKey(key)) {
            StoredRawValue(present = true, raw = source[key])
        } else {
            StoredRawValue(present = false, raw = null)
        }

        override fun readSnapshot(): String? {
            val override = readbackOverride ?: return snapshot
            return override(snapshot)
        }

        override fun commitSnapshot(encoded: String): Boolean {
            writeCount += 1
            if (commitResult || persistOnFailedCommit) snapshot = encoded
            return commitResult
        }
    }

    private fun restore(preferences: SharedPreferences, values: Map<String, *>) {
        val editor = preferences.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }
}
