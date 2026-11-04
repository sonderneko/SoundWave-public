package xyz.cncnlp.soundwave

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.cncnlp.soundwave.SchemeActionHolder.Action

/** r657 P0-4：Scheme 白名单/长度/畸形输入加固，以及“正在播放时插入下一首”的决策。 */
@RunWith(RobolectricTestRunner::class)
class SchemeHardeningTest {
    private fun parse(uri: String) = SchemeActionHolder.parse(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))

    @Test
    fun validPlayAndSearchStillParse() {
        assertEquals(Action.Play("1974443814", "wy", "晴天", "周杰伦"), parse("soundwave://play?id=1974443814&source=wy&name=%E6%99%B4%E5%A4%A9&singer=%E5%91%A8%E6%9D%B0%E4%BC%A6"))
        assertEquals(Action.Play("003OUlho2HcRHC", "tx", "", ""), parse("soundwave://play?id=003OUlho2HcRHC&source=TX"))
        assertEquals(Action.Play("123", "wy", "", ""), parse("soundwave://play?id=123"))
        assertEquals(Action.Search("晴天"), parse("soundwave://search?q=%E6%99%B4%E5%A4%A9"))
    }

    @Test
    fun unknownSourcesAreRejected() {
        listOf("local", "cloud", "script:abc", "evil", "wy;rm", "../wy").forEach {
            assertEquals("source=$it", Action.None, parse("soundwave://play?id=1&source=${Uri.encode(it)}"))
        }
    }

    @Test
    fun overLongAndMalformedInputIsRejected() {
        assertEquals(Action.None, parse("soundwave://play?id=" + "1".repeat(129) + "&source=wy"))
        assertTrue(parse("soundwave://play?id=" + "1".repeat(128) + "&source=wy") is Action.Play)
        assertEquals(Action.None, parse("soundwave://play?id=1&source=wy&name=" + "a".repeat(201)))
        assertEquals(Action.None, parse("soundwave://play?id=1&source=wy&singer=" + "a".repeat(201)))
        assertEquals(Action.None, parse("soundwave://search?q=" + "a".repeat(201)))
        assertEquals(Action.None, parse("soundwave://play?id=1%0A2&source=wy"))
        assertEquals(Action.None, parse("soundwave://play?id=a%2Fb&source=wy"))
        assertEquals(Action.None, parse("soundwave://play?id=1&source=wy&name=a%00b"))
        assertEquals(Action.None, parse("soundwave://play?source=wy"))
        assertEquals(Action.None, parse("soundwave://search?q=%20%20"))
        assertEquals(Action.None, parse("soundwave://unknown?id=1"))
        assertEquals(Action.None, parse("https://music.163.com/song?id=1"))
        assertEquals(Action.None, SchemeActionHolder.parse(null))
    }

    @Test
    fun externalPlayQueuesNextOnlyWhilePlaying() {
        assertTrue(SchemeActionHolder.shouldQueueNext(isPlaying = true, hasCurrentSong = true))
        assertFalse(SchemeActionHolder.shouldQueueNext(isPlaying = false, hasCurrentSong = true))
        assertFalse(SchemeActionHolder.shouldQueueNext(isPlaying = true, hasCurrentSong = false))
        assertFalse(SchemeActionHolder.shouldQueueNext(isPlaying = false, hasCurrentSong = false))
    }

    @Test
    fun sendTextIntentRoutesThroughShareParser() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "分享歌单: https://music.163.com/playlist?id=3778678&userid=1 (来自@网易云音乐)")
        }
        assertEquals(Action.ImportPlaylist("https://music.163.com/playlist?id=3778678&userid=1", "wy"), SchemeActionHolder.parse(send))
        assertEquals(Action.None, SchemeActionHolder.parse(Intent(Intent.ACTION_SEND).apply { type = "text/plain" }))
    }

    @Test
    fun contentViewIntentRoutesScriptsBackupsAndRejectsOthers() {
        val js = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse("content://downloads/x/lx-source.js"), "application/javascript") }
        assertTrue(SchemeActionHolder.parse(js) is Action.ImportScript)
        val backup = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse("content://downloads/x/soundwave-20261017.json"), "application/json") }
        assertTrue(SchemeActionHolder.parse(backup) is Action.ImportBackup)
        val other = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse("content://downloads/x/photo.png"), "image/png") }
        assertEquals(Action.Notice(ExternalFileImport.UNSUPPORTED_MESSAGE), SchemeActionHolder.parse(other))
        val file = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse("file:///sdcard/a.js"), "application/javascript") }
        assertEquals(Action.None, SchemeActionHolder.parse(file))
    }
}
