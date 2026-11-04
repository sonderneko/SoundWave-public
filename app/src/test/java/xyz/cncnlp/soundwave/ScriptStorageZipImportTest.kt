package xyz.cncnlp.soundwave

import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** In-memory preference contract; no Android runtime or network required. */
class ScriptStorageZipImportTest {
    private val source = "// @name New\nlx.on('request', () => {});\n".toByteArray()

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun prefs(values: MutableMap<String, String>): SharedPreferences {
        val pending = mutableMapOf<String, String>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String; proxy }
                "apply" -> { values.putAll(pending); pending.clear(); null }
                "commit" -> { values.putAll(pending); pending.clear(); true }
                else -> error("Unexpected editor call: ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test
    fun limitFailuresAfterValidScriptDoNotPartiallyWriteScriptsOrPreferences() {
        val block = ByteArray(2 * 1024 * 1024) { 65 }
        val valid = "valid.js" to source
        val archives = listOf(
            archive(listOf(valid, "huge.js" to ByteArray(block.size + 1))),
            archive(listOf(valid, "huge.txt" to ByteArray(block.size + 1))),
            archive(listOf(valid) + (0 until 128).map { "dir$it/" to byteArrayOf() }),
            archive(listOf(valid) + (0 until 4).map { "$it.txt" to block })
        )
        archives.forEach { bytes ->
            val values = mutableMapOf("sentinel" to "untouched")
            val preferences = prefs(values)
            val scripts = mutableListOf(Script("existing.js", "Existing", code = String(source)))
            val before = scripts.toList()
            assertThrows(IllegalArgumentException::class.java) {
                ScriptStorage.importFromBytes(preferences, scripts, bytes, "fixture.zip")
            }
            assertEquals(before, scripts)
            assertEquals(mapOf("sentinel" to "untouched"), values)
        }
    }

    @Test
    fun validZipStillSkipsIncompatibleFilesAndCommitsCompatibleScript() {
        val values = mutableMapOf<String, String>()
        val scripts = mutableListOf<Script>()
        val imported = ScriptStorage.importFromBytes(prefs(values), scripts, archive(listOf(
            "ignored.txt" to source, "bad.js" to "not compatible".toByteArray(), "valid.JS" to source
        )))
        assertEquals(1, imported.size)
        assertEquals(imported, scripts)
        assertEquals(String(source), values["script_code_${imported.single().id}"])
    }

    @Test
    fun truncatedZipDoesNotPartiallyWrite() {
        val bytes = archive(listOf("valid.js" to source)).dropLast(1).toByteArray()
        val values = mutableMapOf("sentinel" to "untouched")
        val scripts = mutableListOf<Script>()
        assertThrows(java.io.IOException::class.java) {
            ScriptStorage.importFromBytes(prefs(values), scripts, bytes)
        }
        assertEquals(emptyList<Script>(), scripts)
        assertEquals(mapOf("sentinel" to "untouched"), values)
    }
}
