package xyz.cncnlp.soundwave

import org.junit.Assert.*
import org.junit.Test

class SelectedDomainCopyTest {
    @Test fun optionalCopyDomainsHavePlainChineseNamesAndDescriptions() {
        assertEquals(
            listOf("播放与界面设置", "收听统计", "不喜欢的歌曲", "脚本数据", "音源偏好", "列表排序", "列表位置"),
            LocalCopyDomain.entries.map { it.displayName }
        )
        assertTrue(LocalCopyDomain.entries.all { it.description.isNotBlank() })
        assertTrue(LocalCopyDomain.SCRIPTS.description.contains("不会启用或执行"))
    }

    @Test fun sortingDoesNotCopyArbitrarySecretsAndScrollUsesRealNamespace() {
        assertEquals("list_scroll_v1", LocalCopyDomain.SCROLL.source)
        assertEquals(mapOf("mode_list" to "name"), SelectedDomainCopy.project(LocalCopyDomain.SORT,
            mapOf("password" to "secret", "mode_list" to "name", "mode_invalid" to "secret")))
        assertEquals(mapOf("o_list" to -4, "i_list" to 1), SelectedDomainCopy.project(LocalCopyDomain.SCROLL,
            mapOf("o_list" to -4, "i_list" to 1, "i_broken" to -1)))
    }
    @Test fun smartSourceExcludesRememberedUrlsAndRejectsWrongTypes() {
        assertFalse(SelectedDomainCopy.project(LocalCopyDomain.SMART_SOURCE,
            mapOf("remembered_map" to "https://secret.invalid", "smart_enabled" to true)).containsKey("remembered_map"))
        assertTrue(runCatching { SelectedDomainCopy.project(LocalCopyDomain.SMART_SOURCE,
            mapOf("smart_enabled" to "true")) }.isFailure)
    }
    @Test fun malformedScriptMetadataAndStatsFailClosed() {
        assertTrue(runCatching { SelectedDomainCopy.project(LocalCopyDomain.SCRIPTS,
            mapOf("script_meta" to "broken")) }.isFailure)
        assertTrue(runCatching { SelectedDomainCopy.project(LocalCopyDomain.STATS,
            mapOf("total_sec" to -1L)) }.isFailure)
    }
    @Test fun settingsProjectionExcludesDeviceAndCredentials() {
        val input = mapOf<String, Any>("play_br" to "flac", "lyric_font_sp" to 24,
            "download_tree_uri" to "content://secret", "pass" to "secret", "server_url" to "secret")
        val result = SelectedDomainCopy.project(LocalCopyDomain.SETTINGS, input)
        assertEquals(setOf("play_br", "lyric_font_sp"), result.keys)
    }
    @Test fun malformedSettingsRejectBeforeWrite() {
        assertTrue(runCatching { SelectedDomainCopy.project(LocalCopyDomain.SETTINGS,
            mapOf("play_speed" to Float.NaN)) }.isFailure)
        assertTrue(runCatching { SelectedDomainCopy.project(LocalCopyDomain.SETTINGS,
            mapOf("prefetch_count" to "bad")) }.isFailure)
    }
    @Test fun immutablePreviewDoesNotAliasMutableSourceSets() {
        val source = mutableSetOf("wy:same")
        val result = SelectedDomainCopy.project(LocalCopyDomain.DISLIKES, mapOf("dislike_keys_v1" to source))
        source.clear()
        assertEquals(setOf("wy:same"), result["dislike_keys_v1"])
    }
    @Test fun credentialLikeUnknownKeysAreNotCopiedWithStatsOrScripts() {
        assertTrue(SelectedDomainCopy.project(LocalCopyDomain.STATS, mapOf("password" to "secret")).isEmpty())
        assertTrue(SelectedDomainCopy.project(LocalCopyDomain.SCRIPTS, mapOf("token" to "secret")).isEmpty())
    }
}
