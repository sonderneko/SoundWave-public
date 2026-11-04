package xyz.cncnlp.soundwave

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SmartSourceStoreProfileTest {
    private val context = IsolatedPreferencesContext(RuntimeEnvironment.getApplication())
    private val profileA = ProfileId.of("smart-source-profile-a")
    private val profileB = ProfileId.of("smart-source-profile-b")

    @Test
    fun profileSettingsReadWriteAndUpdateAreIsolatedAcrossProfiles() {
        SmartSourceStore.savePlatformOrder(context, profileA, listOf(
            SmartSourceStore.PlatItem("wy", true),
            SmartSourceStore.PlatItem("tx", false)
        ))
        SmartSourceStore.savePlatformOrder(context, profileB, listOf(
            SmartSourceStore.PlatItem("kg", true),
            SmartSourceStore.PlatItem("mg", false)
        ))

        SmartSourceStore.setPlatformScript(context, profileA, "wy", "a-primary")
        SmartSourceStore.savePlatformScriptOrder(context, profileA, "wy", listOf("a-primary", "a-secondary"))
        SmartSourceStore.setPlatformScript(context, profileA, "tx", "a-tx")
        SmartSourceStore.setPlatformScript(context, profileB, "wy", "b-primary")
        SmartSourceStore.savePlatformScriptOrder(context, profileB, "kg", listOf("b-kg-primary", "b-kg-backup"))

        SmartSourceStore.setActiveScriptId(context, profileA, "a-active")
        SmartSourceStore.saveBackupOrder(context, profileA, listOf(
            SmartSourceStore.BackupItem("a-backup"),
            SmartSourceStore.BackupItem("a-active"),
            SmartSourceStore.BackupItem("a-disabled", false)
        ))
        SmartSourceStore.setActiveScriptId(context, profileB, "b-active")
        SmartSourceStore.saveBackupOrder(context, profileB, listOf(SmartSourceStore.BackupItem("b-backup")))

        SmartSourceStore.saveStageOrder(context, profileA, listOf(
            SmartSourceStore.STAGE_QUALITY,
            SmartSourceStore.STAGE_ORIGIN
        ))
        SmartSourceStore.saveStageOrder(context, profileB, listOf(
            SmartSourceStore.STAGE_CROSS_PLAT,
            SmartSourceStore.STAGE_ORIGIN
        ))
        SmartSourceStore.setSmartEnabled(context, profileA, false)
        SmartSourceStore.setSmartEnabled(context, profileB, true)
        SmartSourceStore.setFallback128k(context, profileA, false)
        SmartSourceStore.setFallback128k(context, profileB, true)
        SmartSourceStore.setRememberCrossPlat(context, profileA, false)
        SmartSourceStore.setRememberCrossPlat(context, profileB, true)
        SmartSourceStore.setPlatformPlayQuality(context, profileA, "wy", "flac")
        SmartSourceStore.setPlatformPlayQuality(context, profileB, "kg", "320k")
        SmartSourceStore.setPlatformDownloadQuality(context, profileA, "wy", "128k")
        SmartSourceStore.setPlatformDownloadQuality(context, profileB, "kg", "flac")

        assertEquals(listOf("wy" to true, "tx" to false, "kw" to true, "kg" to true, "mg" to true), SmartSourceStore.platformOrder(context, profileA).map { it.code to it.enabled })
        assertEquals(listOf("kg" to true, "mg" to false, "wy" to true, "kw" to true, "tx" to true), SmartSourceStore.platformOrder(context, profileB).map { it.code to it.enabled })
        assertEquals(listOf("wy", "kw", "kg", "mg"), SmartSourceStore.enabledPlatformsInOrder(context, profileA))
        assertEquals(listOf("kg", "wy", "kw", "tx"), SmartSourceStore.enabledPlatformsInOrder(context, profileB))
        assertEquals(mapOf("wy" to "a-primary", "tx" to "a-tx"), SmartSourceStore.platformScripts(context, profileA))
        assertEquals("a-primary", SmartSourceStore.platformScript(context, profileA, "wy"))
        assertEquals("b-primary", SmartSourceStore.platformScript(context, profileB, "wy"))
        assertEquals(listOf("a-primary", "a-secondary"), SmartSourceStore.platformScriptOrder(context, profileA, "wy"))
        assertEquals(listOf("b-kg-primary", "b-kg-backup"), SmartSourceStore.platformScriptOrder(context, profileB, "kg"))
        assertEquals("a-active", SmartSourceStore.activeScriptId(context, profileA))
        assertEquals("b-active", SmartSourceStore.activeScriptId(context, profileB))
        assertEquals(listOf("a-backup", "a-disabled"), SmartSourceStore.backupOrder(context, profileA).map { it.id })
        assertEquals(listOf("b-backup"), SmartSourceStore.backupOrder(context, profileB).map { it.id })
        assertEquals(listOf(
            SmartSourceStore.STAGE_QUALITY,
            SmartSourceStore.STAGE_ORIGIN,
            SmartSourceStore.STAGE_CROSS_SCRIPT,
            SmartSourceStore.STAGE_CROSS_PLAT
        ), SmartSourceStore.stageOrder(context, profileA))
        assertEquals(listOf(
            SmartSourceStore.STAGE_CROSS_PLAT,
            SmartSourceStore.STAGE_ORIGIN,
            SmartSourceStore.STAGE_QUALITY,
            SmartSourceStore.STAGE_CROSS_SCRIPT
        ), SmartSourceStore.stageOrder(context, profileB))
        assertFalse(SmartSourceStore.smartEnabled(context, profileA))
        assertTrue(SmartSourceStore.smartEnabled(context, profileB))
        assertFalse(SmartSourceStore.fallback128k(context, profileA))
        assertTrue(SmartSourceStore.fallback128k(context, profileB))
        assertFalse(SmartSourceStore.rememberCrossPlat(context, profileA))
        assertTrue(SmartSourceStore.rememberCrossPlat(context, profileB))
        assertEquals("flac", SmartSourceStore.platformPlayQuality(context, profileA, "wy"))
        assertEquals("320k", SmartSourceStore.platformPlayQuality(context, profileB, "kg"))
        assertEquals("128k", SmartSourceStore.platformDownloadQuality(context, profileA, "wy"))
        assertEquals("flac", SmartSourceStore.platformDownloadQuality(context, profileB, "kg"))
        assertEquals("custom", SmartSourceStore.platformPlayQuality(context, profileA, "mg", "custom"))

        SmartSourceStore.setActiveScriptId(context, profileA, "a-backup")
        SmartSourceStore.setPlatformPlayQuality(context, profileA, "wy", "")
        SmartSourceStore.setPlatformDownloadQuality(context, profileA, "wy", "lossless")
        SmartSourceStore.moveStage(context, profileA, SmartSourceStore.STAGE_CROSS_PLAT, -2)
        SmartSourceStore.setPlatformScript(context, profileA, "tx", "")
        SmartSourceStore.resetPlatformOrder(context, profileA)
        SmartSourceStore.syncScripts(context, profileA, listOf(
            Script(id = "a-active", name = "primary", code = "PROFILE_SCRIPT_SOURCE_MUST_NOT_PERSIST"),
            Script(id = "a-backup", name = "backup", code = "ANOTHER_PROFILE_SCRIPT_SOURCE")
        ))

        assertEquals(listOf("wy", "kw", "kg", "tx", "mg"), SmartSourceStore.platformOrder(context, profileA).map { it.code })
        assertEquals(listOf("kg", "wy", "kw", "tx"), SmartSourceStore.platformOrder(context, profileB).filter { it.enabled }.map { it.code })
        assertEquals("", SmartSourceStore.platformScript(context, profileA, "tx"))
        assertEquals("b-primary", SmartSourceStore.platformScript(context, profileB, "wy"))
        assertEquals("lossless", SmartSourceStore.platformDownloadQuality(context, profileA, "wy"))
        assertEquals("flac", SmartSourceStore.platformDownloadQuality(context, profileB, "kg"))
        assertEquals("320k", SmartSourceStore.platformPlayQuality(context, profileA, "wy"))
        assertEquals("320k", SmartSourceStore.platformPlayQuality(context, profileB, "kg"))
        assertEquals("a-backup", SmartSourceStore.activeScriptId(context, profileA))
        assertEquals("b-active", SmartSourceStore.activeScriptId(context, profileB))
        assertEquals(listOf("a-active"), SmartSourceStore.backupOrder(context, profileA).map { it.id })
        assertEquals(listOf("b-backup"), SmartSourceStore.backupOrder(context, profileB).map { it.id })
        assertEquals(listOf(
            SmartSourceStore.STAGE_QUALITY,
            SmartSourceStore.STAGE_CROSS_PLAT,
            SmartSourceStore.STAGE_ORIGIN,
            SmartSourceStore.STAGE_CROSS_SCRIPT
        ), SmartSourceStore.stageOrder(context, profileA))
        assertEquals(listOf(
            SmartSourceStore.STAGE_CROSS_PLAT,
            SmartSourceStore.STAGE_ORIGIN,
            SmartSourceStore.STAGE_QUALITY,
            SmartSourceStore.STAGE_CROSS_SCRIPT
        ), SmartSourceStore.stageOrder(context, profileB))

        val expectedPreferenceNames = setOf(preferenceName(profileA), preferenceName(profileB))
        assertEquals(expectedPreferenceNames, context.requestedPreferenceNames.toSet())
        assertFalse("profile APIs must never consult the legacy singleton prefs", LEGACY_PREFERENCE in context.requestedPreferenceNames)
        listOf(profileA, profileB).forEach { profileId ->
            val values = preferences(preferenceName(profileId)).all
            assertFalse("ephemeral URL cache must not be stored in profile prefs", values.containsKey(REMEMBERED_KEY))
            assertFalse("script source must not be stored in SmartSourceStore", values.values.any { it.toString().contains("PROFILE_SCRIPT_SOURCE") })
        }
    }

    @Test
    fun legacyNoIdSignaturesContinueReadingAndWritingOriginalKeysAndGlobalUrlCache() {
        val app = RuntimeEnvironment.getApplication()
        SmartSourceStore.init(app)
        val preferences = app.getSharedPreferences(LEGACY_PREFERENCE, Context.MODE_PRIVATE)
        val cachedPrefsField = SmartSourceStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val previousCachedPrefs = cachedPrefsField.get(SmartSourceStore)
        try {
            cachedPrefsField.set(SmartSourceStore, preferences)
            preferences.edit().clear().commit()
            val oldStages = listOf(
                SmartSourceStore.STAGE_ORIGIN,
                SmartSourceStore.STAGE_QUALITY,
                SmartSourceStore.STAGE_CROSS_PLAT,
                SmartSourceStore.STAGE_CROSS_SCRIPT
            )
            preferences.edit()
                .putString("plat_order", JSONArray().put(JSONObject().put("c", "tx").put("e", false)).toString())
                .putString("plat_script", JSONObject().put("tx", "legacy-platform-script").toString())
                .putString("platform_script_order_v2", JSONObject().put("tx", JSONArray().put("legacy-platform-script").put("legacy-platform-backup")).toString())
                .putString("active_script", "legacy-active")
                .putString("backup_order", JSONArray().put(JSONObject().put("id", "legacy-backup").put("e", true)).toString())
                .putString("stage_order", JSONArray(oldStages).toString())
                .putInt("stage_order_version", 2)
                .putBoolean("smart_enabled", false)
                .putBoolean("fallback_128k", false)
                .putBoolean("remember_cross", false)
                .putString("plat_play_quality", JSONObject().put("tx", "flac").toString())
                .putString("plat_down_quality", JSONObject().put("tx", "128k").toString())
                .commit()

            assertEquals(listOf("tx" to false, "wy" to true, "kw" to true, "kg" to true, "mg" to true), SmartSourceStore.platformOrder().map { it.code to it.enabled })
            assertEquals("legacy-platform-script", SmartSourceStore.platformScript("tx"))
            assertEquals(listOf("legacy-platform-script", "legacy-platform-backup"), SmartSourceStore.platformScriptOrder("tx"))
            assertEquals("legacy-active", SmartSourceStore.activeScriptId())
            assertEquals(listOf("legacy-backup"), SmartSourceStore.backupOrder().map { it.id })
            assertEquals(oldStages, SmartSourceStore.stageOrder())
            assertFalse(SmartSourceStore.smartEnabled())
            assertFalse(SmartSourceStore.fallback128k())
            assertFalse(SmartSourceStore.rememberCrossPlat())
            assertEquals("flac", SmartSourceStore.platformPlayQuality("tx"))
            assertEquals("128k", SmartSourceStore.platformDownloadQuality("tx"))
            assertEquals(listOf("wy", "kw", "kg", "mg"), SmartSourceStore.enabledPlatformsInOrder())

            SmartSourceStore.setPlatformPlayQuality("tx", "atmos")
            SmartSourceStore.setPlatformDownloadQuality("tx", "atmos")
            assertEquals("hires", SmartSourceStore.platformPlayQuality("tx"))
            assertEquals("hires", SmartSourceStore.platformDownloadQuality("tx"))
            assertEquals("hires", JSONObject(preferences.getString("plat_play_quality", null)!!).optString("tx"))
            assertEquals("hires", JSONObject(preferences.getString("plat_down_quality", null)!!).optString("tx"))

            SmartSourceStore.setFallback128k(true)
            SmartSourceStore.setPlatformPlayQuality("tx", "320k")
            assertTrue(preferences.getBoolean("fallback_128k", false))
            assertEquals("320k", JSONObject(preferences.getString("plat_play_quality", null)!!).optString("tx"))

            val freshUrl = "https://media.example.invalid/audio?token=temporary-signature"
            SmartSourceStore.remember(
                "legacy-song-key",
                SmartSourceStore.Remembered("legacy-id", "tx", freshUrl, "320k")
            )
            assertTrue("URL cache remains in the original global key", preferences.contains(REMEMBERED_KEY))
            assertEquals(freshUrl, SmartSourceStore.getRemembered("legacy-song-key")?.url)

            val remembered = JSONObject(preferences.getString(REMEMBERED_KEY, null)!!)
            remembered.put(
                "expired-song-key",
                JSONObject()
                    .put("id", "expired-id")
                    .put("source", "tx")
                    .put("url", freshUrl)
                    .put("quality", "320k")
                    .put("ts", System.currentTimeMillis() - REMEMBER_TTL_MS - 1L)
            )
            preferences.edit().putString(REMEMBERED_KEY, remembered.toString()).commit()
            assertNull("expired direct URLs remain misses", SmartSourceStore.getRemembered("expired-song-key"))

            SmartSourceStore.remember(
                "new-song-key",
                SmartSourceStore.Remembered("new-id", "tx", freshUrl, "320k")
            )
            val afterPrune = JSONObject(preferences.getString(REMEMBERED_KEY, null)!!)
            assertFalse("expired URLs are pruned on write as before", afterPrune.has("expired-song-key"))
            assertTrue(afterPrune.has("new-song-key"))
            SmartSourceStore.clearRemembered()
            assertFalse(preferences.contains(REMEMBERED_KEY))
        } finally {
            preferences.edit().clear().commit()
            cachedPrefsField.set(SmartSourceStore, previousCachedPrefs)
        }
    }

    @Test
    fun platformScriptChoicesExcludeUnavailableScriptsAtReadTimeButNeverEraseTheUserChain() {
        // r658c D：用户在智能换源里选好的链路曾被 syncScripts 以不完整脚本列表（启动空列表、只含已启用脚本、
        // 临时停用）过滤后覆盖写回而永久丢失。现在持久链路只由用户显式修改；不可用脚本在读取/取链时跳过。
        val first = Script(id = "platform-first", name = "First", code = "first()")
        val second = Script(id = "platform-second", name = "Second", code = "second()")
        val third = Script(id = "platform-third", name = "Third", code = "third()")
        val importedDisabled = Script(id = "platform-off", name = "Disabled import", code = "off()", enabled = false)
        val blankCode = Script(id = "platform-blank", name = "Blank", code = "  \n")
        val initialScripts = listOf(first, second, third, importedDisabled, blankCode)
        val chain = listOf(first.id, second.id, third.id, importedDisabled.id)

        assertEquals(listOf(first, second, third), SmartSourceStore.platformEligibleScripts(initialScripts))
        SmartSourceStore.savePlatformScriptOrder(context, profileA, "wy", chain)
        SmartSourceStore.syncScripts(context, profileA, initialScripts)
        assertEquals(chain, SmartSourceStore.platformScriptOrder(context, profileA, "wy"))
        assertEquals(listOf(first.id, second.id, third.id),
            SmartSourceStore.filterPlatformScriptOrder(SmartSourceStore.platformScriptOrder(context, profileA, "wy"), initialScripts))

        val afterDisable = listOf(first.copy(enabled = false), second, third, importedDisabled)
        SmartSourceStore.syncScripts(context, profileA, afterDisable)
        SmartSourceStore.syncScripts(context, profileA, emptyList())
        SmartSourceStore.syncScripts(context, profileA, listOf(third))
        assertEquals("partial/empty script lists must not erase the saved chain", chain,
            SmartSourceStore.platformScriptOrder(context, profileA, "wy"))
        assertEquals(first.id, SmartSourceStore.platformScript(context, profileA, "wy"))
        assertEquals(listOf(second.id, third.id),
            SmartSourceStore.filterPlatformScriptOrder(SmartSourceStore.platformScriptOrder(context, profileA, "wy"), afterDisable))
        assertEquals(listOf(second.id, third.id),
            SmartSourceStore.orderScriptsByPlatformChains(chain, emptyList(), afterDisable).map { it.id })
    }

    @Test
    fun unsafeProfileIdsAreRejectedWithoutOpeningAnyPreferenceNamespace() {
        listOf("", "../escape", "profile/name", "profile.name", "含中文", "a".repeat(65)).forEach { rawId ->
            assertThrows("unsafe profile ID: $rawId", IllegalArgumentException::class.java) {
                ProfileId.of(rawId)
            }
        }
        assertTrue(context.requestedPreferenceNames.isEmpty())
    }

    private fun preferenceName(profileId: ProfileId) = "$PROFILE_PREFERENCE_PREFIX${profileId.value}"

    private fun preferences(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** All app preferences are remapped to a unique Robolectric-only namespace. */
    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val namespace = "smart-source-profile-test-${System.nanoTime()}"
        val requestedPreferenceNames = mutableListOf<String>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences(name.also { requestedPreferenceNames += it }.let { "$namespace-$it" }, mode)
    }

    private companion object {
        const val LEGACY_PREFERENCE = "smart_source_v1"
        const val PROFILE_PREFERENCE_PREFIX = "smart_source_profile_"
        const val REMEMBERED_KEY = "remembered_map"
        const val REMEMBER_TTL_MS = 20 * 60 * 1000L
    }
}
