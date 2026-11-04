package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.cncnlp.soundwave.ui.common.designSheetCappedHeightPx

/**
 * M 波（ISSUE-SONGMENU §4）：高度封顶几何口径 + 播放来源身份携带 + 歌单跳转接线。
 * ACCEPT4（用户 2026-11-04 裁定）：「出处」区条目组装改为单行「播放来源」（trackPlaybackSourceEntry），
 * 旧多行组装（歌单/专辑/歌手）测试按新契约重钉——歌手/专辑改为动作行普通选项，不再经条目组装。
 */
class TrackSourceEntriesTest {

    private fun song(
        singer: String = "周杰伦",
        album: String = "叶惠美",
        albumId: String = "123",
        source: String = "wy"
    ) = Song(id = "s1", name = "以父之名", singer = singer, album = album, albumId = albumId, source = source, cover = "http://x/c.jpg")

    // ---------- 高度封顶 ----------

    @Test
    fun `sheet height fraction is pinned at 0_70`() {
        assertEquals(0.70f, TRACK_ACTIONS_SHEET_MAX_HEIGHT_FRACTION, 0f)
    }

    @Test
    fun `capped height geometry`() {
        assertEquals(700, designSheetCappedHeightPx(1000, 0.70f))
        // 典型视口 781px × 0.70 → 546（floor），与设计 §4.6 高度账一致
        assertEquals(546, designSheetCappedHeightPx(781, 0.70f))
        assertEquals(1000, designSheetCappedHeightPx(1000, 1.0f))
        assertEquals(0, designSheetCappedHeightPx(1000, 0f))
        // 不封顶口径：fraction 为空或父高无界时原样返回
        assertEquals(1000, designSheetCappedHeightPx(1000, null))
        assertEquals(Int.MAX_VALUE, designSheetCappedHeightPx(Int.MAX_VALUE, 0.70f))
    }

    // ---------- 出处条目组装（ACCEPT4 用户裁定：只一行「播放来源」，无身份不出，旧多行组装已废止） ----------

    @Test
    fun `origin playlist becomes the single source entry`() {
        val origin = Playlist(id = "pl1", name = "我的歌单", cover = "http://x/p.jpg", source = "wy", count = 30)
        val entry = trackPlaybackSourceEntry(origin)
        assertEquals(TrackSourceKind.PLAYLIST, entry?.kind)
        assertEquals("我的歌单", entry?.title)
        assertEquals("播放来源 · 网易云 · 30 首", entry?.subtitle)
        assertEquals(origin, entry?.playlist)
    }

    @Test
    fun `no identity yields no entry so the row stays hidden`() {
        assertNull(trackPlaybackSourceEntry(null))
        assertNull(trackPlaybackSourceEntry(null, playbackLabel = "", playbackPlaylist = null))
    }

    @Test
    fun `playback label with identity becomes playback entry`() {
        val origin = Playlist(id = "pl9", name = "今日推荐")
        val entry = trackPlaybackSourceEntry(null, playbackLabel = "今日推荐", playbackPlaylist = origin)
        assertEquals(TrackSourceKind.PLAYBACK, entry?.kind)
        assertEquals("今日推荐", entry?.title)
        assertEquals("播放来源", entry?.subtitle)
        assertEquals(origin, entry?.playlist)
    }

    @Test
    fun `playback label without identity yields no entry per case i`() {
        // 案 i：只有名字没有可直达身份 → 整行不出，不回退任何候选列表。
        assertNull(trackPlaybackSourceEntry(null, playbackLabel = "搜索", playbackPlaylist = null))
    }

    @Test
    fun `playback identity without label uses playlist name as title`() {
        val origin = Playlist(id = "pl2", name = "歌单B", source = "tx", count = 12)
        val entry = trackPlaybackSourceEntry(null, playbackLabel = "", playbackPlaylist = origin)
        assertEquals(TrackSourceKind.PLAYBACK, entry?.kind)
        assertEquals("歌单B", entry?.title)
        assertEquals("播放来源 · QQ音乐 · 12 首", entry?.subtitle)
    }

    @Test
    fun `blank playback label falls back to origin playlist`() {
        val origin = Playlist(id = "pl1", name = "歌单A")
        val entry = trackPlaybackSourceEntry(origin, playbackLabel = "", playbackPlaylist = null)
        assertEquals(TrackSourceKind.PLAYLIST, entry?.kind)
        assertEquals("歌单A", entry?.title)
    }

    // ---------- 首页分区出处（ACCEPT6-HOMEORIGIN）：无歌单身份但有分区落点时组装分区行 ----------

    @Test
    fun `home section without playlist identity becomes section entry`() {
        val entry = trackPlaybackSourceEntry(null, playbackLabel = "首页-热门歌曲", playbackPlaylist = null, playbackSection = HomePlaySections.HOT_SONGS)
        assertEquals(TrackSourceKind.HOME_SECTION, entry?.kind)
        assertEquals("首页-热门歌曲", entry?.title)
        assertEquals("播放来源", entry?.subtitle)
        assertNull(entry?.playlist)
        assertEquals(HomePlaySections.HOT_SONGS, entry?.homeSection)
    }

    @Test
    fun `home section entry falls back to section label when playback label blank`() {
        val entry = trackPlaybackSourceEntry(null, playbackLabel = "", playbackPlaylist = null, playbackSection = HomePlaySections.NEW_SONGS)
        assertEquals("首页-新歌速递", entry?.title)
        assertEquals(HomePlaySections.NEW_SONGS, entry?.homeSection)
    }

    @Test
    fun `unknown home section still yields no entry per case i`() {
        assertNull(trackPlaybackSourceEntry(null, playbackLabel = "首页-不存在的分区", playbackPlaylist = null, playbackSection = "nope"))
        assertNull(trackPlaybackSourceEntry(null, playbackLabel = "", playbackPlaylist = null, playbackSection = "nope"))
    }

    @Test
    fun `playlist identity wins over home section`() {
        val origin = Playlist(id = "pl9", name = "今日推荐")
        val entry = trackPlaybackSourceEntry(null, playbackLabel = "今日推荐", playbackPlaylist = origin, playbackSection = HomePlaySections.TODAY)
        assertEquals(TrackSourceKind.PLAYBACK, entry?.kind)
        assertEquals(origin, entry?.playlist)
        assertEquals("", entry?.homeSection)
    }

    // ---------- 播放来源身份携带 ----------

    @Before fun resetTracker() = PlaySourceTracker.reset()

    @Test
    fun `noteQueue captures origin playlist identity with the label`() {
        val pl = Playlist(id = "pl1", name = "我的歌单")
        PlaySourceTracker.push(Any(), "我的歌单", pl)
        val queue = listOf(song(), song().copy(id = "s2"))
        assertEquals("我的歌单", PlaySourceTracker.noteQueue(queue))
        assertEquals(pl, PlaySourceTracker.originPlaylist())
        // 同一队列自动下一首：身份不变
        assertEquals(pl, PlaySourceTracker.originPlaylist())
    }

    @Test
    fun `queue from label only page has no playlist identity`() {
        PlaySourceTracker.push(Any(), "搜索")
        PlaySourceTracker.noteQueue(listOf(song()))
        assertEquals("搜索", PlaySourceTracker.label())
        assertNull(PlaySourceTracker.originPlaylist())
    }

    @Test
    fun `force clears playlist identity`() {
        val pl = Playlist(id = "pl1", name = "我的歌单")
        PlaySourceTracker.push(Any(), "我的歌单", pl)
        PlaySourceTracker.noteQueue(listOf(song()))
        PlaySourceTracker.force("心动模式", listOf(song().copy(id = "s9")))
        assertEquals("心动模式", PlaySourceTracker.label())
        assertNull(PlaySourceTracker.originPlaylist())
    }

    // ---------- 首页分区绑定（ACCEPT6-HOMEORIGIN） ----------

    @Test
    fun `force with section binds label and section without playlist identity`() {
        val queue = listOf(song(), song().copy(id = "s2"))
        PlaySourceTracker.force("首页-热门歌曲", queue, HomePlaySections.HOT_SONGS)
        assertEquals("首页-热门歌曲", PlaySourceTracker.label())
        assertEquals(HomePlaySections.HOT_SONGS, PlaySourceTracker.originSection())
        assertNull(PlaySourceTracker.originPlaylist())
        // resolveAndPlay 的 noteQueue 同签名不换绑：分区归因在队列存续期内稳定。
        PlaySourceTracker.noteQueue(queue)
        assertEquals("首页-热门歌曲", PlaySourceTracker.label())
        assertEquals(HomePlaySections.HOT_SONGS, PlaySourceTracker.originSection())
    }

    @Test
    fun `noteQueue rebind to a scoped page clears the home section`() {
        val queue = listOf(song(), song().copy(id = "s2"))
        PlaySourceTracker.force("首页-新歌速递", queue, HomePlaySections.NEW_SONGS)
        val pl = Playlist(id = "pl1", name = "我的歌单")
        PlaySourceTracker.push(Any(), "我的歌单", pl)
        PlaySourceTracker.noteQueue(listOf(song().copy(id = "s7"), song().copy(id = "s8")))
        assertEquals("我的歌单", PlaySourceTracker.label())
        assertEquals(pl, PlaySourceTracker.originPlaylist())
        assertEquals("", PlaySourceTracker.originSection())
    }

    @Test
    fun `force without section or with unknown section leaves no section binding`() {
        val queue = listOf(song())
        PlaySourceTracker.force("首页-热门歌曲", queue, HomePlaySections.HOT_SONGS)
        PlaySourceTracker.force("心动模式", queue)
        assertEquals("", PlaySourceTracker.originSection())
        PlaySourceTracker.force("首页-热门歌曲", queue, "nope")
        assertEquals("", PlaySourceTracker.originSection())
    }

    @Test
    fun `home section labels follow the user format`() {
        assertEquals("首页-热门歌曲", HomePlaySections.labelFor(HomePlaySections.HOT_SONGS))
        assertEquals("首页-新歌速递", HomePlaySections.labelFor(HomePlaySections.NEW_SONGS))
        assertEquals("今日推荐", HomePlaySections.labelFor(HomePlaySections.TODAY))
        assertEquals("", HomePlaySections.labelFor(""))
        assertTrue(HomePlaySections.isKnown(HomePlaySections.HOT_SONGS))
    }

    // ---------- 歌单跳转接线 ----------

    @Test
    fun `openPlaylist routes bound playlist with current tab`() {
        var captured: Pair<Playlist, Tab>? = null
        TrackNavigation.bind(artist = { _, _ -> }, album = { _, _ -> }, currentTab = { Tab.PLAYLISTS }, playlist = { pl, tab -> captured = pl to tab })
        val pl = Playlist(id = "pl1", name = "我的歌单")
        TrackNavigation.openPlaylist(pl)
        assertEquals(pl to Tab.PLAYLISTS, captured)
    }

    @Test
    fun `openHomeSection routes bound section key`() {
        var captured: String? = null
        TrackNavigation.bind(artist = { _, _ -> }, album = { _, _ -> }, currentTab = { Tab.PLAYER }, homeSection = { captured = it })
        TrackNavigation.openHomeSection(HomePlaySections.NEW_SONGS)
        assertEquals(HomePlaySections.NEW_SONGS, captured)
    }
}
