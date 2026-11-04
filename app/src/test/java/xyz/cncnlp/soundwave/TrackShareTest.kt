package xyz.cncnlp.soundwave

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TrackShareTest {
    private fun song(source: String, id: String = "123456") = Song(
        id = id, name = "测试歌曲", singer = "测试歌手", source = source, album = "测试专辑"
    )

    private fun checkShare(song: Song, platform: String, identity: String, url: String) {
        val text = TrackNavigation.shareText(song)
        assertTrue(text, text.startsWith("测试歌曲 - 测试歌手 · 《测试专辑》"))
        assertTrue(text, text.contains("平台：$platform"))
        assertTrue(text, text.contains(identity))
        assertTrue(text, text.lines().contains(url))
        assertEquals(text, 1, Regex("https://").findAll(text).count())
    }

    @Test fun neteaseUsesItsOwnNumericId() {
        checkShare(song("wy"), "网易云音乐", "歌曲 ID：123456", "https://music.163.com/song?id=123456")
    }

    @Test fun qqMidPreservesCaseAndNeverUsesMediaMid() {
        checkShare(song("tx", "003AbC1dEfGhIJ").copy(mediaMid = "WRONG_FILE_MID"),
            "QQ 音乐", "歌曲 ID：003AbC1dEfGhIJ", "https://y.qq.com/n/ryqq/songDetail/003AbC1dEfGhIJ")
        assertFalse(TrackNavigation.shareText(song("tx", "003AbC1dEfGhIJ").copy(mediaMid = "WRONG_FILE_MID")).contains("WRONG_FILE_MID"))
    }

    @Test fun qqAliasAndNumericSongIdUseExplicitSongidQuery() {
        checkShare(song(" QQ "), "QQ 音乐", "歌曲 ID：123456", "https://i.y.qq.com/v8/playsong.html?songid=123456")
    }

    @Test fun kuwoUsesRid() {
        checkShare(song("kw"), "酷我音乐", "歌曲 ID：123456", "https://www.kuwo.cn/play_detail/123456")
    }

    @Test fun kugouUsesHashNotNumericCatalogId() {
        val hash = "1571941D82D63AD614E35EAD9DB6A6A2"
        checkShare(song("kg", "98765").copy(hash = hash), "酷狗音乐", "歌曲 hash：$hash", "https://www.kugou.com/song/#hash=$hash")
        checkShare(song("kg", hash), "酷狗音乐", "歌曲 hash：$hash", "https://www.kugou.com/song/#hash=$hash")
        assertFalse(TrackNavigation.shareText(song("kg", "98765")).contains("https://"))
    }

    @Test fun miguUsesCopyrightIdNotContentOrSongId() {
        val s = song("mg", "600908000000123456").copy(copyrightId = "63273402938", miguSongId = "1000123456")
        checkShare(s, "咪咕音乐", "版权 ID：63273402938", "https://music.migu.cn/v3/music/song/63273402938")
        val text = TrackNavigation.shareText(s)
        assertTrue(text, text.contains("歌曲 ID：600908000000123456"))
        assertFalse(text, text.contains("/song/600908000000123456"))
        assertFalse(text, text.contains("/song/1000123456"))
        assertFalse(TrackNavigation.shareText(s.copy(copyrightId = "")).contains("https://"))
    }

    @Test fun qqLegacyMediaMidIsNotSharedAsSongIdentity() {
        val text = TrackNavigation.shareText(song("tx", "001FileMidABCD").copy(mediaMid = "001FileMidABCD"))
        assertFalse(text, text.contains("https://"))
        assertFalse(text, text.contains("001FileMidABCD"))
    }

    @Test fun playbackResolutionNeverChangesSharedCatalogIdentity() {
        val original = song("wy")
        val resolved = original.copy(resolvedUrl = "https://another-provider.example/audio?token=secret", actualQuality = "flac")
        assertEquals(TrackNavigation.shareText(original), TrackNavigation.shareText(resolved))
        assertEquals("wy", resolved.source)
        assertEquals("123456", resolved.id)
    }

    @Test fun persistedPlatformIdentitiesProduceTheSameShare() {
        for (s in listOf(song("wy"), song("qq", "003AbC1dEfGhIJ"), song("kw"),
            song("kg").copy(hash = "1571941D82D63AD614E35EAD9DB6A6A2"),
            song("mg").copy(copyrightId = "63273402938", miguSongId = "1000123456"))) {
            assertEquals(TrackNavigation.shareText(s), TrackNavigation.shareText(Song.fromJson(s.toJson())))
        }
    }

    @Test fun unknownLocalAndInvalidIdentitiesNeverInventLinksOrLeakPlaybackUrls() {
        for (s in listOf(song(""), song("mystery"), song("local", "/storage/private.mp3"),
            song("wy", ""), song("wy", "1&x=2"), song("wy", "-1"), song("kw", "../1"),
            song("tx", "bad/mid"), song("kg", "123"), song("mg").copy(copyrightId = "123?token=secret"))) {
            val text = TrackNavigation.shareText(s.copy(resolvedUrl = "https://secret.example/audio?token=private"))
            assertFalse(text, text.contains("https://"))
            assertFalse(text, text.contains("/storage/"))
            assertFalse(text, text.contains("token="))
            assertTrue(text, text.contains("暂无可用的原平台歌曲链接"))
        }
    }

    @Test fun placeholderIdentitiesAreNeitherDisplayedNorLinked() {
        for (source in listOf("wy", "tx", "kg", "kw", "mg")) {
            for (id in listOf("0", "0".repeat(32), "null", "NULL", "undefined", "unknown", "none")) {
                val text = TrackNavigation.shareText(song(source, id).copy(hash = id, copyrightId = id))
                assertFalse(text, text.contains("https://"))
                assertFalse(text, text.contains("歌曲 ID："))
                assertFalse(text, text.contains("歌曲 hash："))
                assertFalse(text, text.contains("版权 ID："))
            }
        }
    }

    @Test fun missingMetadataKeepsExistingReadableFallback() {
        val text = TrackNavigation.shareText(song("wy").copy(singer = "", album = ""))
        assertTrue(text, text.startsWith("测试歌曲 - 未知歌手"))
        assertFalse(text, text.contains("《》"))
    }

    @Test fun shareIntentUsesSamePlatformPayload() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val s = song("wy")
        TrackNavigation.share(app, s)
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(s.name, send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(TrackNavigation.shareText(s), send.getStringExtra(Intent.EXTRA_TEXT))
    }
}
