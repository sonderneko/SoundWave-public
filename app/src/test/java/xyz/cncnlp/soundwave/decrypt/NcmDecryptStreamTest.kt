package xyz.cncnlp.soundwave.decrypt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * NcmDecrypt 流式 vs 整块 对照测试（P1-1 关键验证）：
 * 用合法结构构造 .ncm 文件（真实 AES-128-ECB 加密 key/meta + 随机音频体），
 * decrypt() 与 decryptStream() 输出必须逐字节一致；同时覆盖头部损坏返回 null。
 * 纯 JVM（NcmDecrypt 已改用 java.util.Base64，零 Android 依赖）。
 */
class NcmDecryptStreamTest {

    private val coreKey = "hzHRAmso5kInbaxW".toByteArray(Charsets.UTF_8)
    private val metaKey = "#14ljk_!\\]&0U<('".toByteArray(Charsets.UTF_8)

    private fun aesEcb(key: ByteArray, data: ByteArray, mode: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun padPkcs7(data: ByteArray): ByteArray {
        val pad = (16 - data.size % 16).toByte()
        val out = data.copyOf(data.size + (16 - data.size % 16))
        for (i in data.size until out.size) out[i] = pad
        return out
    }

    private fun u32le(v: Long): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )

    /** 构造合法 .ncm 文件字节（与 decrypt/decryptStream 的头部解析一一对应）。 */
    private fun buildNcm(audio: ByteArray, formatJson: String, image: ByteArray = ByteArray(0), kpSize: Int = 23): ByteArray {
        // ---- key：17 字节前缀（解密后忽略）+ kp，PKCS7 pad 后 AES 加密，文件存储再 XOR 0x64 ----
        val kp = ByteArray(kpSize) { (it * 7 + 3).toByte() }
        val keyPlain = padPkcs7(ByteArray(17) { 0x55 } + kp)
        val keyEnc = aesEcb(coreKey, keyPlain, Cipher.ENCRYPT_MODE)
        val keyData = keyEnc.map { (it.toInt() and 0xff xor 0x64).toByte() }.toByteArray()

        // ---- meta：22 字节前缀 + base64(AES(metaKey, music:JSON))，文件存储再 XOR 0x63 ----
        val metaPlain = padPkcs7(("music:$formatJson").toByteArray(Charsets.UTF_8))
        val metaEnc = Base64.getEncoder().encodeToString(aesEcb(metaKey, metaPlain, Cipher.ENCRYPT_MODE)).toByteArray(Charsets.UTF_8)
        val metaBody = ByteArray(22) { 0x11 } + metaEnc
        val metaData = metaBody.map { (it.toInt() and 0xff xor 0x63).toByte() }.toByteArray()

        // ---- 组装 ----
        val out = ByteArrayOutputStream()
        out.write("CTENFDAM".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0))
        out.write(u32le(keyData.size.toLong()))
        out.write(keyData)
        out.write(u32le(metaData.size.toLong()))
        out.write(metaData)
        out.write(ByteArray(9))
        out.write(u32le(image.size.toLong()))
        out.write(image)
        out.write(audio)
        return out.toByteArray()
    }

    @Test
    fun `stream output byte-identical to whole-buffer decrypt with meta-provided format`() {
        val sizes = listOf(1, 100, 32768, 32769, 65536, 131073, 1_000_013)
        for (size in sizes) {
            val audio = Random(7).nextBytes(size)
            val ncm = buildNcm(audio, """{"format":"flac","artist":["x"],"album":"a","bitrate":320001}""", kpSize = 23)
            val expected = NcmDecrypt.decrypt(ncm)
            assertEquals("flac", expected!!.second)
            val out = ByteArrayOutputStream()
            val realExt = NcmDecrypt.decryptStream(ncm.inputStream(), out)
            assertEquals("flac", realExt)
            assertArrayEquals("audio bytes differ size=$size", expected.first, out.toByteArray())
        }
    }

    @Test
    fun `stream decrypt with empty image and degenerate key length`() {
        val audio = Random(11).nextBytes(5000)
        val ncm = buildNcm(audio, """{"format":"mp3"}""", image = ByteArray(0), kpSize = 1)
        val out = ByteArrayOutputStream()
        val ext = NcmDecrypt.decryptStream(ncm.inputStream(), out)
        assertEquals("mp3", ext)
        assertArrayEquals(NcmDecrypt.decrypt(ncm)!!.first, out.toByteArray())
    }

    @Test
    fun `unparseable meta falls back to plaintext header detection`() {
        // meta 无法解析出 format：解密头探测明文（随机数据 → mp3）
        val audio = Random(13).nextBytes(300)
        val ncm = buildNcm(audio, "garbage-not-json")
        val out = ByteArrayOutputStream()
        val ext = NcmDecrypt.decryptStream(ncm.inputStream(), out)
        assertEquals("mp3", ext)
        assertArrayEquals(NcmDecrypt.decrypt(ncm)!!.first, out.toByteArray())
    }

    @Test
    fun `invalid magic or truncated header returns null`() {
        assertNull(NcmDecrypt.decryptStream("NOTNCMFILE".toByteArray().inputStream(), ByteArrayOutputStream()))
        assertNull(NcmDecrypt.decryptStream(ByteArray(0).inputStream(), ByteArrayOutputStream()))
        // 截断头部：magic 正确但 keyLen 后无数据
        val ncm = buildNcm(Random(3).nextBytes(64), """{"format":"flac"}""")
        val truncated = ncm.copyOf(8 + 2 + 4 + 2) // key 只给了 2 字节
        assertNull(NcmDecrypt.decryptStream(truncated.inputStream(), ByteArrayOutputStream()))
    }

    @Test
    fun `oversized image header is rejected without allocating`() {
        // imageSize 声明 100MB（> 50MB 上限）→ 直接返回 null，不尝试读体
        val out = ByteArrayOutputStream()
        out.write("CTENFDAM".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0))
        out.write(u32le(32))           // keyLen
        out.write(ByteArray(32))
        out.write(u32le(32))           // metaLen
        out.write(ByteArray(32))
        out.write(ByteArray(9))
        out.write(u32le(100L * 1024 * 1024)) // imageSize 恶意超大
        assertNull(NcmDecrypt.decryptStream(out.toByteArray().inputStream(), ByteArrayOutputStream()))
    }
}