package xyz.cncnlp.soundwave

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScriptStorageImportTest {
    @Test
    fun failedFileImportLeavesExistingScriptsAndPreferencesUntouched() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("script-import-regression", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val scripts = mutableListOf<Script>()
        ScriptStorage.importScript(prefs, scripts, "// @name Existing\nlx.on('request', () => {});\n")
        val scriptsBefore = scripts.toList()
        val preferencesBefore = prefs.all.toMap()

        try {
            ScriptStorage.importFromBytes(prefs, scripts, "not a compatible source script".toByteArray())
            fail("Expected an incompatible script to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(scriptsBefore, scripts)
            assertEquals(preferencesBefore, prefs.all)
            assertEquals(scriptsBefore, ScriptStorage.loadScripts(prefs))
        }
    }
}
