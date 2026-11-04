package xyz.cncnlp.soundwave

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupPreferenceProjectionTest {
    @Test fun tenSpSurvivesBackupProjection() {
        assertEquals(10, BackupPreferenceProjection.settings(JSONObject().put("lyric_font_sp",10), strict=true).getInt("lyric_font_sp"))
    }

    @Test fun consentAndUnknownFieldsAreNeverPortable() {
        val input = JSONObject().put("crash_auto_upload", true).put("user", "account")
            .put("future_secret", "token").put("download_tree_uri", "content://grant")
            .put("theme_mode", "dark")
        assertEquals("{\"theme_mode\":\"dark\"}", BackupPreferenceProjection.settings(input, strict = true).toString())
    }

    @Test fun safeSettingsKeepExpectedTypesAndCurrentChoices() {
        val result = BackupPreferenceProjection.settings(JSONObject()
            .put("play_speed", 1.5).put("player_layout", "apple")
            .put("detail_cover_style", "standard").put("down_br", "master")
            .put("download_name_tpl", "album_title").put("prefetch_count", 3), strict = true)
        assertEquals(1.5f, result.opt("play_speed"))
        assertEquals(3, result.opt("prefetch_count"))
        assertEquals("album_title", result.getString("download_name_tpl"))
        assertEquals(6, result.length())
    }

    @Test fun crossfadeBackupAllowsOnlySupportedDurations() {
        val key = xyz.cncnlp.soundwave.playback.PlaybackTransitionSettingsStore.KEY_DURATION
        listOf(0, 3, 5, 8).forEach { seconds ->
            assertEquals(seconds, BackupPreferenceProjection.settings(JSONObject().put(key, seconds), strict = true).getInt(key))
        }
        listOf(1, 2, 4, 6, 7, 9).forEach { seconds ->
            assertFalse(BackupPreferenceProjection.settings(JSONObject().put(key, seconds), strict = false).has(key))
        }
    }

    @Test fun scriptConfigurationCannotCarryCodeAndKeepsInstalledMetadata() {
        val projection = BackupPreferenceProjection.scriptConfiguration(JSONObject()
            .put("script_code_a", "secret source")
            .put("script_meta", "[{\"id\":\"a\",\"name\":\"secret\",\"enabled\":false}]"), strict = true)
        assertFalse(projection.toString().contains("secret"))
        val merged = BackupPreferenceProjection.mergeScriptConfiguration(
            "[{\"id\":\"a\",\"name\":\"local name\",\"enabled\":true},{\"id\":\"b\"}]", projection
        )!!
        val items = org.json.JSONArray(merged)
        assertEquals(2, items.length())
        assertEquals("local name", items.getJSONObject(0).getString("name"))
        assertFalse(items.getJSONObject(0).getBoolean("enabled"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSafeSettingIsRejectedRatherThanCoerced() {
        BackupPreferenceProjection.settings(JSONObject().put("prefetch_count", 1.5), strict = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedCrossfadeDurationIsRejectedInStrictBackup() {
        BackupPreferenceProjection.settings(JSONObject().put(
            xyz.cncnlp.soundwave.playback.PlaybackTransitionSettingsStore.KEY_DURATION, 1
        ), strict = true)
    }
}
