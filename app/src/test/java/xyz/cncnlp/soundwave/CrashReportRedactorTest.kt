package xyz.cncnlp.soundwave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** r657 P0-1：崩溃报告 6 类敏感样本必须全部打码，异常类名与堆栈行号保留。 */
class CrashReportRedactorTest {
    private fun r(s: String) = CrashReportRedactor.redact(s)

    @Test
    fun quotedCredentialsWithWhitespaceAndEscapesAreFullyMasked() {
        val raw = """failure {"token":"SHORTSECRET", "password" : "two words \\\"hidden\\\" tail", 'api_key' : 'single \'quoted\' secret', "nested": {"refresh_token":"nested secret"}}"""
        val out = r(raw)
        listOf("SHORTSECRET", "two words", "hidden", "tail", "single", "quoted", "nested secret").forEach {
            assertFalse("leaked $it in: $out", out.contains(it))
        }
        assertTrue(out.contains("\"token\":\"<redacted>\""))
        assertTrue(out.contains("'api_key' : '<redacted>'"))
        assertEquals(out, r(out))
    }

    @Test
    fun urlUserInfoIsRemovedWithoutLosingHostPathOrPort() {
        listOf(
            "https://alice:SHORTSECRET@example.com:8443/a",
            "https://alice:SHORTSECRET@example.com:8443/a?token=querysecret#fragment",
            "https://alice%40mail:SHORTSECRET@example.com:8443/a",
            "https://alice:SHORT;SECRET@example.com:8443/a",
            "https://alice:eyJabcdefghi.abcdefgh.ijklmnop@example.com:8443/a"
        ).forEach { raw ->
            assertEquals("https://example.com:8443/a", r(raw))
            assertEquals(r(raw), r(r(raw)))
        }
    }

    @Test
    fun redactionIsIdempotentForMixedCredentialForms() {
        val raw = """token=one password="two words" {'cookie':'three four'}
Authorization: Bearer abcdefghijkl0123
content://some.provider/document/1 file:///sdcard/private
wy:123456 https://name:pass@example.com/path?token=five"""
        assertEquals(r(raw), r(r(raw)))
    }

    @Test
    fun urlQueryAndFragmentAreStripped() {
        val out = r("java.io.IOException: GET https://music.example.com/song/url?id=123&token=abc123secret#frag failed")
        assertTrue(out.contains("https://music.example.com/song/url"))
        assertFalse(out.contains("abc123secret"))
        assertFalse(out.contains("id=123"))
        assertFalse(out.contains("#frag"))
    }

    @Test
    fun credentialsHeadersAndParamsAreMasked() {
        val out = r(
            "Authorization: Bearer eyJhbGciOi.eyJzdWIiOiIx.c2lnbmF0dXJl\n" +
                "Cookie: MUSIC_U=deadbeefcafe; __csrf=xyz\n" +
                "params token=t0k3nvalue sign=s1gn&uin=12345 x-token: hdr-secret\n" +
                "auth Bearer abcdefghijkl0123"
        )
        listOf("eyJhbGciOi", "deadbeefcafe", "__csrf=xyz", "t0k3nvalue", "s1gn", "12345", "hdr-secret", "abcdefghijkl0123").forEach {
            assertFalse("leaked $it in: $out", out.contains(it))
        }
        assertTrue(out.contains("Authorization: <redacted>"))
    }

    @Test
    fun songKeysEmailsPhonesAndDeviceIdsAreMasked() {
        val out = r("resolve failed on wy:1974443814 / tx:003OUlho2HcRHC / script:abc12:9988776 mail me@example.com tel 13812345678 androidId=9774d56d682e549c raw 9774d56d682e549c")
        listOf("1974443814", "003OUlho2HcRHC", "9988776", "me@example.com", "13812345678", "9774d56d682e549c").forEach {
            assertFalse("leaked $it in: $out", out.contains(it))
        }
        assertTrue(out.contains("wy:<redacted>"))
        assertTrue(out.contains("<email>"))
        assertTrue(out.contains("<phone>"))
    }

    @Test
    fun userFilePathsAndContentUrisAreMasked() {
        val out = r(
            "open failed: /data/user/0/xyz.cncnlp.soundwave/files/a.txt\n" +
                "/data/data/xyz.cncnlp.soundwave/cache/x /storage/emulated/0/Music/张三的歌/晴天.mp3 /sdcard/Download/x.js\n" +
                "/home/alice/project /Users/bob/x content://com.android.providers.media.documents/document/audio%3A42 file:///storage/emulated/0/a.flac"
        )
        listOf("xyz.cncnlp.soundwave/files", "/storage/emulated/0", "张三", "晴天", "/sdcard", "x.js", "alice", "bob", "audio%3A42", "a.flac").forEach {
            assertFalse("leaked $it in: $out", out.contains(it))
        }
        assertTrue(out.contains("<app-data>/files/a.txt"))
        assertTrue(out.contains("content://<redacted>"))
    }

    @Test
    fun stackTraceStructureIsPreserved() {
        val trace = "时间: 1760000000000\n版本: 1.0.656\n机型: Xiaomi 23127PN0CC API34\n线程: main\n" +
            "java.lang.IllegalStateException: boom\n\tat xyz.cncnlp.soundwave.PlaybackController.resolve(PlaybackController.kt:469)\n" +
            "\tat android.os.Handler.dispatchMessage(Handler.java:106)\n"
        assertEquals(trace, r(trace))
        assertEquals("", r(""))
    }
}
