package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0/P1 真实性收口回归：未知来源禁止隐式标为网易云；
 * 平台能力默认声明保持保守，只有网易云声明已验证。
 */
class SourceProvenanceTest {

    @Test
    fun songDefaultSourceIsUnverifiedEmpty() {
        val s = Song(id = "1", name = "x", singer = "y")
        assertEquals("", s.source)
    }

    @Test
    fun playlistDefaultSourceIsUnverifiedEmpty() {
        assertEquals("", Playlist(id = "p", name = "n").source)
    }

    @Test
    fun discoverCardDefaultSourceIsUnverifiedEmpty() {
        assertEquals("", DiscoverCard(id = "d", name = "n").source)
    }

    @Test
    fun rankBoardDefaultSourceIsUnverifiedEmpty() {
        assertEquals("", RankBoard(name = "r").source)
    }

    @Test
    fun songKeyFallsBackToWyOnlyForPlaybackIdentityOfUnknownSource() {
        assertEquals("wy:123", Song(id = "123", name = "x", singer = "y").key())
    }

    @Test
    fun songKeyUsesRealSourceWhenPresent() {
        assertEquals("kg:456", Song(id = "456", name = "x", singer = "y", source = "kg").key())
    }

    @Test
    fun registryDefaultsDeclareOnlyWyAsVerified() {
        val d = ProviderRegistry.defaults
        assertEquals(5, d.size)
        assertTrue(d["wy"]!!.verified)
        assertTrue(d["wy"]!!.discover)
        assertTrue(d["wy"]!!.hot)
        assertTrue(d["wy"]!!.rank)
    }

    @Test
    fun registryKeepsUnverifiedPlatformsConservativeAndAllowsVerifiedMiguFeeds() {
        // kg/kw/tx 不把发现页或“已验证”能力夸大；搜索和歌单只走真实服务响应。
        for (src in listOf("kg", "kw", "tx")) {
            val c = ProviderRegistry.defaults[src]!!
            assertFalse("$src must not claim verified", c.verified)
            assertTrue("$src search passthrough", c.search)
            assertTrue("$src playlist passthrough", c.playlistSearch)
            assertFalse("$src must not claim discover", c.discover)
            assertTrue("$src real hot endpoint", c.hot)
            assertTrue("$src real rank endpoint", c.rank)
        }
        // 咪咕：本机服务已接入官方 rank columnId / 热门 / discover，允许当前平台真实内容进入 UI；
        // 其余账号相关功能不属于公开数据能力模型。
        val mg = ProviderRegistry.defaults["mg"]!!
        assertTrue(mg.verified)
        assertTrue(mg.hot)
        assertTrue(mg.rank)
        assertTrue(mg.discover)
    }

    @Test
    fun capabilityDefaultsToUnverified() {
        val c = PlatformCapability(source = "xx")
        assertFalse(c.verified)
        assertFalse(c.discover)
        assertTrue(c.search)
    }
}
