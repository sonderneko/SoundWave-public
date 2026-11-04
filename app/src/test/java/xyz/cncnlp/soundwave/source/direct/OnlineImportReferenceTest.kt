package xyz.cncnlp.soundwave.source.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Authored regression cases; NOT EXECUTED locally (no SDK/compilation permitted).
 * IDs here are parser fixtures, not claims about live upstream availability. */
class OnlineImportReferenceTest {
    @Test fun albumsOfAllFivePlatformsKeepEntityKindAndId() {
        val cases = listOf(
            Triple("https://music.163.com/#/album?id=404060139", "wy", "404060139"),
            Triple("https://y.qq.com/n/ryqq/albumDetail/003DFRzD192KKD", "tx", "003DFRzD192KKD"),
            Triple("https://y.qq.com/n/yqq/album/003DFRzD192KKD.html", "tx", "003DFRzD192KKD"),
            Triple("https://i.y.qq.com/n2/m/share/details/album.html?albummid=003DFRzD192KKD", "tx", "003DFRzD192KKD"),
            Triple("https://www.kugou.com/album/210575933.html", "kg", "210575933"),
            Triple("http://www.kuwo.cn/album_detail/123456", "kw", "123456"),
            Triple("https://music.migu.cn/v3/music/album/123456", "mg", "123456"),
            Triple("https://music.migu.cn/v5/#/album?albumId=123456", "mg", "123456")
        )
        cases.forEach { (url, source, id) ->
            assertEquals(url, OnlineImportReference(source, id, OnlineImportKind.ALBUM),
                parseOnlineImportReference("分享专辑《夜》：$url (来自音乐平台)"))
            assertNull(parseOfficialPlaylistReference(url))
        }
    }

    @Test fun playlistFormsAndBareWyIdArePreserved() {
        val cases = listOf(
            Triple("https://music.163.com/#/playlist?id=3778678", "wy", "3778678"),
            Triple("https://y.qq.com/n/yqq/playlist/7217720898.html", "tx", "7217720898"),
            Triple("https://i.y.qq.com/n2/m/share/details/taoge.html?id=7217720898", "tx", "7217720898"),
            Triple("https://www.kugou.com/yy/special/single/1067062.html", "kg", "1067062"),
            Triple("https://m.kuwo.cn/h5app/playlist/2736267853", "kw", "2736267853"),
            Triple("https://music.migu.cn/v5/#/playlist?playlistId=221573417", "mg", "221573417"),
            Triple("https://h5.nf.migu.cn/app/v4/p/share/playlist/index.html?id=184187437", "mg", "184187437")
        )
        cases.forEach { (url, source, id) ->
            assertEquals(OnlineImportReference(source, id, OnlineImportKind.PLAYLIST), parseOnlineImportReference("歌单：$url。"))
            assertEquals(OfficialPlaylistReference(source, id), parseOfficialPlaylistReference(url))
        }
        assertEquals(OnlineImportReference("wy", "3778678", OnlineImportKind.PLAYLIST), parseOnlineImportReference("3778678"))
    }

    @Test fun malformedAmbiguousForeignAndShortLinksNeverBecomeGuessedIds() {
        listOf(
            "https://163cn.tv/Ab12cd", "https://c6.y.qq.com/base/fcgi-bin/u?__=AbCd12",
            "https://url.cn/Ab12cd", "https://t1.kugou.com/Ab12cd",
            "https://www.kugou.com/songlist/1067062/?uid=1", // user collection ID is not a specialid
            "https://www.kuwo.cn/s/Ab12cd", "https://music.migu.cn/s/Ab12cd",
            "https://music.163.com/album?id=1&id=2", "https://music.163.com/album?id=%31",
            "https://music.163.com/album?id=-1", "https://music.163.com/album?id=123abc",
            "https://music.163.com/album?id=123456789012345678901",
            "https://user:pw@music.163.com/album?id=1", "https://music.163.com:8443/album?id=1",
            "https://music.163.com.evil.invalid/album?id=1", "http://127.0.0.1/album?id=1",
            "https://y.qq.com/n/ryqq/albumDetail/123456", // albumid != albummid
            "https://music.163.com/song?id=186016", "歌单 7217720898", "晴天 周杰伦",
            "https://music.163.com/album?id=1 https://music.163.com/playlist?id=2"
        ).forEach { assertNull(it, parseOnlineImportReference(it)) }
    }
}

/** r658c D#3：移动端分享路径归一、短链白名单识别、来源别名规范化。 */
class R658cOnlineImportTest {
    @Test fun mobileSharePathsParse() {
        assertEquals(OnlineImportReference("wy", "3778678", OnlineImportKind.PLAYLIST),
            parseOnlineImportReference("https://y.music.163.com/m/playlist?id=3778678&userid=1"))
        assertEquals(OnlineImportReference("wy", "404060139", OnlineImportKind.ALBUM),
            parseOnlineImportReference("https://y.music.163.com/m/album?id=404060139"))
        assertEquals(OnlineImportReference("kw", "2736267853", OnlineImportKind.PLAYLIST),
            parseOnlineImportReference("https://m.kuwo.cn/newh5app/playlist_detail/2736267853?from=ar"))
    }

    @Test fun shortLinksAreRecognisedOnlyOnAllowlistedHosts() {
        assertEquals("https://163cn.tv/Ab12cd", OnlineImportShortLink.shortLinkOf("分享歌单：https://163cn.tv/Ab12cd (来自@网易云音乐)"))
        assertEquals("https://c6.y.qq.com/base/fcgi-bin/u?__=AbCd12", OnlineImportShortLink.shortLinkOf("https://c6.y.qq.com/base/fcgi-bin/u?__=AbCd12"))
        assertNull(OnlineImportShortLink.shortLinkOf("https://evil.example/Ab12cd"))
        assertEquals(false, OnlineImportShortLink.allowedHop("http://127.0.0.1/x"))
        assertEquals(true, OnlineImportShortLink.allowedHop("https://i.y.qq.com/n2/m/share/details/taoge.html?id=1"))
    }

    @Test fun providerAliasesAreCanonical() {
        assertEquals("tx", canonicalImportSource("qq", "tx"))
        assertEquals("kg", canonicalImportSource("kugou", "kg"))
        assertEquals("wy", canonicalImportSource("", "wy"))
        assertEquals("kw", canonicalImportSource("KW", "kw"))
    }
}
