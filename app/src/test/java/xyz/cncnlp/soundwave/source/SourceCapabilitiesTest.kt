package xyz.cncnlp.soundwave.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 批19 #5 + 批21：平台能力常量矩阵回归（MusicSourceRegistry.capabilitiesOf）。
 * 证据口径 = direct* 直连实现（详见 MusicSourceRegistry.platformCapabilities KDoc）：
 * search/playlist/rank/lyric 五平台 DIRECT、album wy/tx DIRECT（kg/kw/mg SERVER_FALLBACK）、
 * artist 全平台 SERVER_FALLBACK、resolve wy/kg/kw/mg DIRECT（tx 匿名不可用 → SERVER_FALLBACK）；
 * 未知 id 能力位全 UNSUPPORTED（未接线 ≠ 真实空结果）；四个产品兼容 flag 保持 Boolean。
 */
class SourceCapabilitiesTest {

    @Test
    fun wyDeclaresEvidenceBackedCapabilitySet() {
        val c = MusicSourceRegistry.capabilitiesOf("wy")
        assertEquals(CapabilityLevel.DIRECT, c.supportsSearch)
        assertEquals(CapabilityLevel.DIRECT, c.supportsPlaylist)
        assertEquals(CapabilityLevel.DIRECT, c.supportsAlbum)
        assertEquals(CapabilityLevel.SERVER_FALLBACK, c.supportsArtist)
        assertEquals(CapabilityLevel.DIRECT, c.supportsLyrics)
        assertEquals(CapabilityLevel.DIRECT, c.supportsResolve)
        assertEquals(CapabilityLevel.DIRECT, c.supportsRank)
    }

    @Test
    fun kgKwMgDeclareDirectCoreWithServerFallbackAlbumArtist() {
        for (id in listOf("kg", "kw", "mg")) {
            val c = MusicSourceRegistry.capabilitiesOf(id)
            assertEquals("$id search", CapabilityLevel.DIRECT, c.supportsSearch)
            assertEquals("$id playlist", CapabilityLevel.DIRECT, c.supportsPlaylist)
            assertEquals("$id album", CapabilityLevel.SERVER_FALLBACK, c.supportsAlbum)
            assertEquals("$id artist", CapabilityLevel.SERVER_FALLBACK, c.supportsArtist)
            assertEquals("$id lyric", CapabilityLevel.DIRECT, c.supportsLyrics)
            assertEquals("$id resolve", CapabilityLevel.DIRECT, c.supportsResolve)
            assertEquals("$id rank", CapabilityLevel.DIRECT, c.supportsRank)
        }
    }

    @Test
    fun txDeclaresDirectAlbumWithServerFallbackResolve() {
        val c = MusicSourceRegistry.capabilitiesOf("tx")
        assertEquals(CapabilityLevel.DIRECT, c.supportsSearch)
        assertEquals(CapabilityLevel.DIRECT, c.supportsPlaylist)
        assertEquals(CapabilityLevel.DIRECT, c.supportsAlbum)
        assertEquals(CapabilityLevel.SERVER_FALLBACK, c.supportsArtist)
        assertEquals(CapabilityLevel.DIRECT, c.supportsLyrics)
        assertEquals(CapabilityLevel.SERVER_FALLBACK, c.supportsResolve)
        assertEquals(CapabilityLevel.DIRECT, c.supportsRank)
    }

    @Test
    fun wyOnlyProductQuirkFlags() {
        for (id in MusicSourceRegistry.platformIds) {
            val c = MusicSourceRegistry.capabilitiesOf(id)
            assertEquals("$id legacy feed flag", id == "wy", c.legacySinglePlatformFeed)
            assertEquals("$id preview flag", id == "wy", c.previewClipRisk)
            assertFalse("$id isScript", c.isScript)
        }
    }

    @Test
    fun unknownIdsDeclareNoCapabilities() {
        for (id in listOf("local", "cloud", "script:demo", "", "nope")) {
            val c = MusicSourceRegistry.capabilitiesOf(id)
            assertEquals(id, id, c.platform)
            assertEquals("$id search", CapabilityLevel.UNSUPPORTED, c.supportsSearch)
            assertEquals("$id playlist", CapabilityLevel.UNSUPPORTED, c.supportsPlaylist)
            assertEquals("$id album", CapabilityLevel.UNSUPPORTED, c.supportsAlbum)
            assertEquals("$id artist", CapabilityLevel.UNSUPPORTED, c.supportsArtist)
            assertEquals("$id lyric", CapabilityLevel.UNSUPPORTED, c.supportsLyrics)
            assertEquals("$id resolve", CapabilityLevel.UNSUPPORTED, c.supportsResolve)
            assertEquals("$id rank", CapabilityLevel.UNSUPPORTED, c.supportsRank)
            assertFalse("$id legacy feed", c.legacySinglePlatformFeed)
            assertFalse("$id preview", c.previewClipRisk)
            assertFalse("$id isScript", c.isScript)
        }
    }
}
