package xyz.cncnlp.soundwave.decrypt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * QmcDecrypt 流式 vs 整块 对照测试（P1-1 关键验证）：
 * 同一密文，decrypt()（ByteArray 整块）与 decryptStream()（64KB 分块）输出必须逐字节一致。
 * 覆盖 32768 跳步边界（32768/32769/65536/65537）与各 ext 分支。
 * 纯 JVM，零 Android 依赖。
 */
class QmcDecryptStreamTest {

    private fun randomBytes(size: Int, seed: Int = 42): ByteArray {
        val r = Random(seed)
        return ByteArray(size) { r.nextBytes(1)[0] }
    }

    private fun streamDecrypt(cipher: ByteArray, ext: String): Pair<ByteArray, String> {
        val out = ByteArrayOutputStream()
        val realExt = QmcDecrypt.decryptStream(cipher.inputStream(), out, ext)
        return out.toByteArray() to realExt
    }

    @Test
    fun `stream output byte-identical to whole-buffer decrypt across sizes`() {
        // 覆盖 0/1 字节、恰 32768（跳步触发点）、32769、65536、65537、100KB 与常规 5MB
        // 注：5MB 用于压力但保持用例快速；主要边界在 32K 附近。
        val sizes = listOf(0, 1, 100, 32767, 32768, 32769, 65536, 65537, 131072, 5 * 1024 * 1024)
        val exts = listOf("mflac", "mgg", "tkm", "qmcflac", "qmcogg", "bkcflac")
        for (ext in exts) {
            for (size in sizes) {
                val cipher = randomBytes(size, seed = size * 31 + ext.hashCode())
                if (size == 0) {
                    // 空输入：整块版抛 IllegalArgumentException，流式版语义保持一致。
                    assertThrows(IllegalArgumentException::class.java) {
                        streamDecrypt(cipher, ext)
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        QmcDecrypt.decrypt(cipher, ext)
                    }
                    continue
                }
                val expected = QmcDecrypt.decrypt(cipher, ext)
                val actual = streamDecrypt(cipher, ext)
                assertArrayEquals("bytes differ ext=$ext size=$size", expected.first, actual.first)
                assertEquals("ext differ ext=$ext size=$size", expected.second, actual.second)
            }
        }
    }

    @Test
    fun `real extension mapping for known qq formats`() {
        val cipher = randomBytes(4096)
        assertEquals("flac", streamDecrypt(cipher, "mflac").second)
        assertEquals("flac", streamDecrypt(cipher, "qmcflac").second)
        assertEquals("flac", streamDecrypt(cipher, "bkcflac").second)
        assertEquals("ogg", streamDecrypt(cipher, "mgg").second)
        assertEquals("ogg", streamDecrypt(cipher, "qmcogg").second)
        assertEquals("m4a", streamDecrypt(cipher, "tkm").second)
    }

    @Test
    fun `unknown ext falls back to plaintext header detection`() {
        // 未知 ext 时按明文头探测：无签名 → mp3；构造以 fLaC 开头的随机数据 → flac。
        val noSig = randomBytes(300)
        assertEquals("mp3", streamDecrypt(noSig, "zzz").second)

        // 密文 = XOR(mask) 再包一层：用解密器自身两次异或还原，构造明文头为 fLaC 的密文。
        // 先取任意密文 C，decrypt 得 P；再以 P' (fLaC 头) 无法直接构造密文——此处直接验证
        // 缺省 ext 探测与整块版一致即可（同密文对照已覆盖逐字节，这里只验分支语义）。
        val cipher = randomBytes(512)
        val expected = QmcDecrypt.decrypt(cipher, "wrong")
        val actual = streamDecrypt(cipher, "wrong")
        assertArrayEquals(expected.first, actual.first)
        assertEquals(expected.second, actual.second)
    }
}