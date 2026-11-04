package xyz.cncnlp.soundwave.migration

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RuntimeProvenanceInventoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferenceNames = listOf("soundwave", "soundwave_userdata", "soundwave_legacy_local", "soundwave_provenance_catalog_v1")
    private lateinit var before: Map<String, Map<String, *>>

    @Before fun isolatePreferences() {
        before = preferenceNames.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap() }
        preferenceNames.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @After fun restorePreferences() {
        before.forEach { (name, values) ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
    }

    @Test fun downloadsKeyIsPresentEvenWhenEmpty() {
        val preferences = context.getSharedPreferences("soundwave_userdata", Context.MODE_PRIVATE)
        preferences.edit().putString("downloads_v1", "").commit()
        val result = RuntimeProvenanceInventory.scan(context)
        val epoch = result.currentEpoch!!
        assertEquals(ProvenanceState.PRESENT_UNASSIGNED, result.catalog!!.state(ProvenanceDomain.DOWNLOAD_RECORDS, epoch))
        assertFalse(result.allRequiredDomainsVerifiedAbsent())
        assertEquals("", preferences.getString("downloads_v1", null))
    }

    @Test fun libraryOrderKeyIsInventoriedWithoutParsingOrMutation() {
        val preferences = context.getSharedPreferences("soundwave", Context.MODE_PRIVATE)
        preferences.edit().putString("lib_order_v1", "not JSON").commit()
        val result = RuntimeProvenanceInventory.scan(context)
        assertEquals(ProvenanceState.PRESENT_UNASSIGNED,
            result.catalog!!.state(ProvenanceDomain.LIBRARY_ORDER, result.currentEpoch!!))
        assertEquals("not JSON", preferences.getString("lib_order_v1", null))
    }

    @Test fun invalidEpochIsUnknownAndDoesNotOverwriteCatalog() {
        context.getSharedPreferences("soundwave_userdata", Context.MODE_PRIVATE).edit()
            .putLong(RuntimeProvenanceInventory.EPOCH_KEY, -1L).commit()
        val result = RuntimeProvenanceInventory.scan(context)
        assertNull(result.catalog)
        assertFalse(result.allRequiredDomainsVerifiedAbsent())
    }

    @Test fun twoKeyEmptySnapshotDoesNotProveDeviceEmpty() {
        val source = context.getSharedPreferences("soundwave_userdata", Context.MODE_PRIVATE)
        source.edit().remove("user_data_v1").remove("play_history_v1")
            .remove(RuntimeProvenanceInventory.EPOCH_KEY).commit()
        context.getSharedPreferences("soundwave", Context.MODE_PRIVATE).edit()
            .putString("lib_order_v1", "[]").commit()
        LegacyUnassignedSnapshot.capture(context)
        assertNotEquals(LegacyUnassignedSnapshot.AccountSyncGateState.VERIFIED_EMPTY,
            LegacyUnassignedSnapshot.accountSyncGateState(context))
        assertEquals("[]", context.getSharedPreferences("soundwave", Context.MODE_PRIVATE).getString("lib_order_v1", null))
    }
}
