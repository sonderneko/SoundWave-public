package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.cncnlp.soundwave.SchemeActionHolder.Action

/** r657：系统分享文本（五个平台）→ 导入歌单 / 打开单曲 / 搜索 / 明确提示；外部文件类型判定。 */
@RunWith(RobolectricTestRunner::class)
class ShareImportParsingTest {
    private fun p(text: String?) = ShareLinkParser.parse(text)

    @Test
    fun explicitPlaylistLinksOfAllFivePlatformsImport() {
        assertEquals(Action.ImportPlaylist("https://music.163.com/playlist?id=3778678&userid=1", "wy"),
            p("分享Alice创建的歌单「热歌」: https://music.163.com/playlist?id=3778678&userid=1 (来自@网易云音乐)"))
        assertEquals(Action.ImportPlaylist("https://y.qq.com/n/ryqq/playlist/7256912512", "tx"),
            p("QQ音乐歌单《夜》https://y.qq.com/n/ryqq/playlist/7256912512"))
        assertEquals(Action.ImportPlaylist("https://www.kugou.com/yy/special/single/546903.html", "kg"),
            p("酷狗歌单 https://www.kugou.com/yy/special/single/546903.html"))
        assertEquals(Action.ImportPlaylist("http://www.kuwo.cn/playlist_detail/2886046289", "kw"),
            p("我在酷我音乐分享了歌单 http://www.kuwo.cn/playlist_detail/2886046289。"))
        assertEquals(Action.ImportPlaylist("https://music.migu.cn/v3/music/playlist/169206251", "mg"),
            p("咪咕歌单 https://music.migu.cn/v3/music/playlist/169206251"))
    }

    @Test
    fun shortLinksWithoutSongHintExplainThatLongLinksAreRequired() {
        for (url in listOf("https://163cn.tv/Ab12cd", "https://c6.y.qq.com/base/fcgi-bin/u?__=AbCd12")) {
            val action = p("分享歌单《通勤》: $url")
            assertTrue(action is Action.Notice && action.message.contains("完整长链接"))
        }
    }

    @Test
    fun albumSharesOfFivePlatformsOpenExistingImportDialog() {
        listOf(
            "https://music.163.com/#/album?id=404060139" to "wy",
            "https://y.qq.com/n/ryqq/albumDetail/003DFRzD192KKD" to "tx",
            "https://www.kugou.com/album/210575933.html" to "kg",
            "https://www.kuwo.cn/album_detail/123456" to "kw",
            "https://music.migu.cn/v5/#/album?albumId=123456" to "mg"
        ).forEach { (url, source) -> assertEquals(Action.ImportPlaylist(url, source), p("分享专辑《夜》：$url (来自音乐平台)")) }
    }

    @Test
    fun explicitSongLinksPlayWithIdPreserved() {
        assertEquals(Action.Play("186016", "wy", "晴天", "周杰伦"),
            p("分享周杰伦的单曲《晴天》: https://music.163.com/song?id=186016&userid=1 (来自@网易云音乐)"))
        assertEquals(Action.Play("186016", "wy", "", ""), p("https://music.163.com/#/song?id=186016"))
        assertEquals(Action.Play("0039MnYb0qxYhV", "tx", "晴天", ""), p("《晴天》https://y.qq.com/n/ryqq/songDetail/0039MnYb0qxYhV"))
        assertEquals(Action.Play("228908", "kw", "", ""), p("https://www.kuwo.cn/play_detail/228908"))
    }

    @Test
    fun songShortLinksSearchByTitleOrExplainClearly() {
        assertEquals(Action.Search("晴天 周杰伦"), p("分享周杰伦的单曲《晴天》: https://163cn.tv/xyz (来自@网易云音乐)"))
        assertEquals(Action.Search("晴天"), p("《晴天》 https://t1.kugou.com/song.html?id=abc123"))
        val notice = p("https://t1.kugou.com/song.html?id=abc123")
        assertTrue(notice is Action.Notice && notice.message.contains("酷狗"))
    }

    @Test
    fun unsupportedInputsGiveClearNotice() {
        val foreign = p("看看这个 https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertTrue(foreign is Action.Notice && foreign.message.contains("open.spotify.com"))
        assertTrue(p("只是一段没有链接的文字") is Action.Notice)
        assertTrue(p("https://music.163.com/album?id=bad") is Action.Notice)
        assertTrue(p("https://user:pw@music.163.com/playlist?id=1") is Action.Notice)
        assertTrue(p("https://music.163.com:8443/playlist?id=1") is Action.Notice)
        assertTrue(p("x".repeat(ShareLinkParser.MAX_SHARE_TEXT + 1)) is Action.Notice)
        assertEquals(Action.None, p(null))
        assertEquals(Action.None, p("   "))
    }

    @Test
    fun externalFileKindUsesNameThenMime() {
        assertEquals(ExternalFileImport.Kind.SCRIPT, ExternalFileImport.kindOf("lx-music-source.JS", "application/octet-stream"))
        assertEquals(ExternalFileImport.Kind.BACKUP, ExternalFileImport.kindOf("soundwave-20261017-1200.json", null))
        assertEquals(ExternalFileImport.Kind.SCRIPT, ExternalFileImport.kindOf(null, "text/javascript; charset=utf-8"))
        assertEquals(ExternalFileImport.Kind.BACKUP, ExternalFileImport.kindOf("document:42", "application/json"))
        assertEquals(ExternalFileImport.Kind.UNSUPPORTED, ExternalFileImport.kindOf("a.png", "image/png"))
        assertEquals(ExternalFileImport.Kind.UNSUPPORTED, ExternalFileImport.kindOf(null, null))
    }
}
