package xyz.cncnlp.soundwave

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScriptStorageProfileTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()
    private val profileA = ProfileId.of("script-storage-profile-a")
    private val profileB = ProfileId.of("script-storage-profile-b")
    private val preferenceNames = listOf(
        "soundwave_userdata",
        profileA.preferencesName,
        profileB.preferencesName
    )
    private lateinit var originalPreferences: Map<String, Map<String, *>>

    @Before
    fun snapshotAndClearPreferences() {
        originalPreferences = preferenceNames.associateWith { prefs(it).all.toMap() }
        preferenceNames.forEach { prefs(it).edit().clear().commit() }
    }

    @After
    fun restorePreferences() {
        originalPreferences.forEach { (name, values) ->
            val editor = prefs(name).edit().clear()
            values.forEach { (key, value) -> putPreference(editor, key, value) }
            editor.commit()
        }
    }

    @Test
    fun scriptSetsCodeMetadataUpdatesDeletionAndEnablementStayProfileScoped() {
        val sharedId = "fixture-shared.js"
        val onlyAId = "fixture-a-only.js"
        val aScript = script(sharedId, "profile A", "metadata A v1", "fixture-a-v1", true)
        val bScript = script(sharedId, "profile B", "metadata B v1", "fixture-b-v1", true)
        val onlyA = script(onlyAId, "A-only", "metadata A-only", "fixture-a-only", true)

        ScriptStorage.saveScripts(context, profileA, listOf(aScript, onlyA))
        ScriptStorage.saveScripts(context, profileB, listOf(bScript))

        assertEquals(listOf(sharedId, onlyAId), ScriptStorage.loadScripts(context, profileA).map { it.id })
        assertEquals(listOf(sharedId), ScriptStorage.loadScripts(context, profileB).map { it.id })
        assertTrue(ScriptStorage.loadCode(context, profileA, sharedId) == "fixture-a-v1")
        assertTrue(ScriptStorage.loadCode(context, profileB, sharedId) == "fixture-b-v1")

        val updatedA = aScript.copy(
            name = "profile A updated",
            description = "metadata A v2",
            version = "2",
            author = "fixture author A2",
            code = "fixture-a-v2"
        )
        ScriptStorage.saveScripts(context, profileA, listOf(updatedA, onlyA))
        ScriptStorage.saveCode(context, profileA, sharedId, "fixture-a-code-v2")

        val loadedA = ScriptStorage.loadScripts(context, profileA).first { it.id == sharedId }
        val loadedB = ScriptStorage.loadScripts(context, profileB).single()
        assertEquals("profile A updated", loadedA.name)
        assertEquals("metadata A v2", loadedA.description)
        assertEquals("2", loadedA.version)
        assertEquals("fixture author A2", loadedA.author)
        assertEquals("profile B", loadedB.name)
        assertEquals("metadata B v1", loadedB.description)
        assertEquals("1", loadedB.version)
        assertEquals("fixture author", loadedB.author)
        assertTrue(ScriptStorage.loadCode(context, profileA, sharedId) == "fixture-a-code-v2")
        assertTrue(ScriptStorage.loadCode(context, profileB, sharedId) == "fixture-b-v1")

        assertTrue(ScriptStorage.setEnabledIfOwned(context, profileA, sharedId, false))
        assertFalse(ScriptStorage.loadScripts(context, profileA).first { it.id == sharedId }.enabled)
        assertTrue(ScriptStorage.loadScripts(context, profileB).single().enabled)
        assertFalse(ScriptStorage.setEnabledIfOwned(context, profileB, onlyAId, false))

        assertTrue(ScriptStorage.deleteScript(context, profileA, sharedId))
        assertFalse(ScriptStorage.deleteScript(context, profileA, sharedId))
        assertEquals(listOf(onlyAId), ScriptStorage.loadScripts(context, profileA).map { it.id })
        assertTrue(ScriptStorage.loadCode(context, profileA, sharedId) == null)
        assertEquals(listOf(sharedId), ScriptStorage.loadScripts(context, profileB).map { it.id })
        assertTrue(ScriptStorage.loadCode(context, profileB, sharedId) == "fixture-b-v1")
    }

    @Test
    fun localImportsStoreSourceAndMetadataOnlyInTheNamedProfile() {
        val syntheticSource = listOf(
            "/**",
            " * @name Profile Import Fixture",
            " * @description Synthetic profile-local import",
            " */",
            "globalThis.lx.send();"
        ).joinToString("\n")

        val importedA = ScriptStorage.importScript(context, profileA, syntheticSource)
        val importedB = ScriptStorage.importFromBytes(
            context,
            profileB,
            syntheticSource.toByteArray(Charsets.UTF_8),
            "fixture.js"
        ).single()

        assertEquals(importedA.id, importedB.id)
        assertEquals("Profile Import Fixture", ScriptStorage.loadScripts(context, profileA).single().name)
        assertEquals("Profile Import Fixture", ScriptStorage.loadScripts(context, profileB).single().name)
        assertTrue(ScriptStorage.loadCode(context, profileA, importedA.id) == syntheticSource)
        assertTrue(ScriptStorage.loadCode(context, profileB, importedB.id) == syntheticSource)
        assertTrue(prefs("soundwave_userdata").all.isEmpty())
    }

    @Test
    fun profileApisDoNotChangeLegacyPreferenceKeysOrDefaultApiBehavior() {
        val legacyPrefs = prefs("soundwave_userdata")
        val profileScript = script("fixture-profile.js", "profile", "profile metadata", "profile-code", true)
        val legacyScript = script("fixture-legacy.js", "legacy", "legacy metadata", "legacy-code", true)

        ScriptStorage.saveScripts(context, profileA, listOf(profileScript))
        assertTrue(ScriptStorage.loadScripts(legacyPrefs).isEmpty())
        assertEquals(setOf("script_meta", "script_code_fixture-profile.js"), prefs(profileA.preferencesName).all.keys)
        assertTrue(legacyPrefs.all.isEmpty())

        ScriptStorage.saveScripts(legacyPrefs, listOf(legacyScript))
        assertEquals(setOf("script_meta", "script_code_fixture-legacy.js"), legacyPrefs.all.keys)
        assertEquals(listOf("fixture-legacy.js"), ScriptStorage.loadScripts(legacyPrefs).map { it.id })
        assertTrue(ScriptStorage.loadCode(legacyPrefs, "fixture-legacy.js") == "legacy-code")

        val legacyList = ScriptStorage.loadScripts(legacyPrefs).toMutableList()
        ScriptStorage.deleteScript(legacyPrefs, legacyList, "fixture-legacy.js")

        assertTrue(ScriptStorage.loadScripts(legacyPrefs).isEmpty())
        assertEquals(setOf("script_meta"), legacyPrefs.all.keys)
        assertEquals(listOf("fixture-profile.js"), ScriptStorage.loadScripts(context, profileA).map { it.id })
        assertTrue(ScriptStorage.loadCode(context, profileA, "fixture-profile.js") == "profile-code")
    }

    @Test
    fun unsafeProfileIdsAreRejectedBeforeScriptPreferencesCanBeAddressed() {
        val invalidIds = listOf("", "../escape", "profile/name", "profile.name", "含中文", "x".repeat(65))
        invalidIds.forEach { rawId ->
            var rejected = false
            try {
                ProfileId.of(rawId)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("unsafe profile ID should be rejected", rejected)
        }
        assertEquals("valid_profile-01", ProfileId.of("valid_profile-01").value)
    }

    private fun script(id: String, name: String, description: String, code: String, enabled: Boolean) =
        Script(
            id = id,
            name = name,
            description = description,
            version = "1",
            author = if (name == "profile B") "fixture author" else "fixture author A",
            code = code,
            enabled = enabled
        )

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
