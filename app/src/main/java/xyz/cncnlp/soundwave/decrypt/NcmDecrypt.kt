package xyz.cncnlp.soundwave.decrypt

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 .ncm 文件解密（纯算法移植，无业务接线）。
 *
 * 逐行移植自 music-lib 的 netease/crypto.go：
 * - DecryptNCM（第 179-245 行）：文件头校验、key/meta 读取、音频体异或
 * - buildNCMKeyBox（第 254-275 行）：256 字节盒交换
 * - parseNCMFormat（第 278-301 行）：meta 解密取 format
 * - aesECBDecrypt / pkcs7Unpad / readU32LE（第 304-336 行）
 * - detectAudioExt（第 345-359 行）
 *
 * AES-128-ECB 采用 Android 标准 javax.crypto（AES/ECB/NoPadding），
 * PKCS7 填充/去填充手动处理，仅用于本地 NCM 音频解密。
 */
object NcmDecrypt {
    private val NCM_CORE_KEY = "hzHRAmso5kInbaxW".toByteArray(Charsets.UTF_8)
    private val NCM_META_KEY = "#14ljk_!\\]&0U<('".toByteArray(Charsets.UTF_8)
    private const val AES_BLOCK_SIZE = 16

    /**
     * 解密 .ncm 字节数据。
     * @return (音频数据, 格式如 "flac"/"mp3"/"ogg")；头部不是 "CTENFDAM" 等异常时返回 null
     */
    fun decrypt(encrypted: ByteArray): Pair<ByteArray, String>? {
        if (encrypted.size < 16 ||
            String(encrypted.copyOfRange(0, 8), Charsets.US_ASCII) != "CTENFDAM"
        ) {
            return null
        }

        var offset = 8
        if (offset + 2 > encrypted.size) return null
        offset += 2

        val (keyLen, keyNext) = readU32LE(encrypted, offset) ?: return null
        offset = keyNext
        if (offset.toLong() + keyLen > encrypted.size) return null
        val keyData = encrypted.copyOfRange(offset, (offset + keyLen.toInt()))
        for (i in keyData.indices) {
            keyData[i] = (keyData[i].toInt() and 0xff xor 0x64).toByte()
        }
        offset += keyLen.toInt()

        var decryptedKey = aesECBDecrypt(NCM_CORE_KEY, keyData) ?: return null
        decryptedKey = pkcs7Unpad(decryptedKey)
        if (decryptedKey.size > 17) {
            decryptedKey = decryptedKey.copyOfRange(17, decryptedKey.size)
        }
        if (decryptedKey.isEmpty()) return null

        val keyBox = buildNCMKeyBox(decryptedKey)

        val (metaLen, metaNext) = readU32LE(encrypted, offset) ?: return null
        offset = metaNext
        if (offset.toLong() + metaLen > encrypted.size) return null
        val metaData = encrypted.copyOfRange(offset, offset + metaLen.toInt())
        for (i in metaData.indices) {
            metaData[i] = (metaData[i].toInt() and 0xff xor 0x63).toByte()
        }
        offset += metaLen.toInt()

        var outExt = parseNCMFormat(metaData)

        if (offset + 9 > encrypted.size) return null
        offset += 9

        val (imageSize, imageNext) = readU32LE(encrypted, offset) ?: return null
        offset = imageNext
        val imageEnd = offset.toLong() + imageSize
        if (imageEnd > encrypted.size) return null
        offset = imageEnd.toInt()

        val audio = encrypted.copyOfRange(offset, encrypted.size)
        for (i in audio.indices) {
            val j = (i + 1) and 0xff
            val boxJ = keyBox[j].toInt() and 0xff
            val idx = (boxJ + (keyBox[(boxJ + j) and 0xff].toInt() and 0xff)) and 0xff
            audio[i] = (audio[i].toInt() and 0xff xor (keyBox[idx].toInt() and 0xff)).toByte()
        }

        if (outExt.isEmpty()) {
            outExt = detectAudioExt(audio)
        }

        return audio to outExt
    }

    // 流式头部安全上限：合法 .ncm 的 key/meta/图片远小于这些值，超限视为损坏文件避免 OOM。
    private const val MAX_KEY_LEN = 1 shl 20
    private const val MAX_META_LEN = 2 shl 20
    private const val MAX_IMAGE_SIZE = 50L shl 20
    private const val STREAM_BUFFER_BYTES = 64 * 1024

    /**
     * 流式解密 .ncm：逐段读头部（key/meta/图片全部落在内存的只有元数据，音频体按块 XOR 直写输出流），
     * 峰值内存与 [decrypt] 的“整文件 + 音频体副本”相比降至 O(64KB + 头部)。
     * @param input 加密数据输入流（调用方负责关闭）。
     * @param output 明文音频输出流（调用方负责关闭）。
     * @return 格式如 "flac"/"mp3"/"ogg"；头部不是 "CTENFDAM" 或结构损坏时返回 null（与 [decrypt] 语义一致）。
     */
    fun decryptStream(input: InputStream, output: OutputStream): String? {
        val magic = ByteArray(8)
        if (!readFully(input, magic)) return null
        if (String(magic, Charsets.US_ASCII) != "CTENFDAM") return null

        val pad2 = ByteArray(2)
        if (!readFully(input, pad2)) return null

        val keyLen = readLen(input) ?: return null
        if (keyLen < 0 || keyLen > MAX_KEY_LEN) return null
        val keyData = ByteArray(keyLen.toInt())
        if (!readFully(input, keyData)) return null
        for (i in keyData.indices) {
            keyData[i] = (keyData[i].toInt() and 0xff xor 0x64).toByte()
        }

        var decryptedKey = aesECBDecrypt(NCM_CORE_KEY, keyData) ?: return null
        decryptedKey = pkcs7Unpad(decryptedKey)
        if (decryptedKey.size > 17) {
            decryptedKey = decryptedKey.copyOfRange(17, decryptedKey.size)
        }
        if (decryptedKey.isEmpty()) return null
        val keyBox = buildNCMKeyBox(decryptedKey)

        val metaLen = readLen(input) ?: return null
        if (metaLen < 0 || metaLen > MAX_META_LEN) return null
        val metaData = ByteArray(metaLen.toInt())
        if (!readFully(input, metaData)) return null
        for (i in metaData.indices) {
            metaData[i] = (metaData[i].toInt() and 0xff xor 0x63).toByte()
        }
        var outExt = parseNCMFormat(metaData)

        val pad9 = ByteArray(9)
        if (!readFully(input, pad9)) return null

        val imageSize = readLen(input) ?: return null
        if (imageSize < 0 || imageSize > MAX_IMAGE_SIZE) return null
        var skipped = 0L
        while (skipped < imageSize) {
            val s = input.skip(imageSize - skipped)
            if (s <= 0) {
                if (input.read() < 0) return null
                skipped++
            } else {
                skipped += s
            }
        }

        // 音频体 XOR：i 从 0 起逐字节，与 decrypt 的 audio.indices 完全一致。
        val plainHead = ByteArray(8)
        var i = -1
        var writtenHead = 0
        val buf = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val cnt = input.read(buf)
            if (cnt <= 0) break
            for (k in 0 until cnt) {
                i++
                val j = (i + 1) and 0xff
                val boxJ = keyBox[j].toInt() and 0xff
                val idx = (boxJ + (keyBox[(boxJ + j) and 0xff].toInt() and 0xff)) and 0xff
                val p = (buf[k].toInt() and 0xff xor (keyBox[idx].toInt() and 0xff)).toByte()
                if (writtenHead < plainHead.size) {
                    plainHead[writtenHead] = p
                    writtenHead++
                }
                buf[k] = p
            }
            output.write(buf, 0, cnt)
        }

        if (outExt.isEmpty()) {
            outExt = detectAudioExt(plainHead)
        }
        return outExt
    }

    /** 读 4 字节小端 u32；读到 EOF 返回 null（损坏头部）。 */
    private fun readLen(input: InputStream): Long? {
        val b = ByteArray(4)
        if (!readFully(input, b)) return null
        return (b[0].toLong() and 0xff) or
            ((b[1].toLong() and 0xff) shl 8) or
            ((b[2].toLong() and 0xff) shl 16) or
            ((b[3].toLong() and 0xff) shl 24)
    }

    /** 读满 buf 才算成功；任何位置 EOF 返回 false。 */
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    /** 照抄 crypto.go buildNCMKeyBox：标准 256 字节盒交换。 */
    private fun buildNCMKeyBox(key: ByteArray): ByteArray {
        val box = ByteArray(256) { it.toByte() }

        var c = 0
        var last = 0
        var keyPos = 0
        for (i in 0 until 256) {
            val swap = box[i]
            c = ((swap.toInt() and 0xff) + last + (key[keyPos].toInt() and 0xff)) and 0xff
            box[i] = box[c]
            box[c] = swap
            last = c
            keyPos++
            if (keyPos >= key.size) {
                keyPos = 0
            }
        }

        return box
    }

    /** 照抄 crypto.go parseNCMFormat：跳 22 字节后 base64 解码再 AES 解密取 format。 */
    private fun parseNCMFormat(metaData: ByteArray): String {
        if (metaData.size <= 22) return ""

        val decoded = try {
            // Android Base64.DEFAULT 宽容换行/空白；java.util.Base64 用 MIME 解码器对齐该行为（minSdk 26 起可用，且可跑 JVM 单测）。
            Base64.getMimeDecoder().decode(metaData.copyOfRange(22, metaData.size))
        } catch (e: Exception) {
            return ""
        }

        val decrypted = aesECBDecrypt(NCM_META_KEY, decoded) ?: return ""
        var payload = pkcs7Unpad(decrypted)

        var text = String(payload, Charsets.UTF_8)
        if (text.startsWith("music:")) {
            text = text.removePrefix("music:")
        }

        return try {
            JSONObject(text).optString("format")
        } catch (e: Exception) {
            ""
        }
    }

    /** AES-128-ECB 解密（手动分块，无填充），对应 crypto.go aesECBDecrypt。 */
    private fun aesECBDecrypt(key: ByteArray, data: ByteArray): ByteArray? {
        if (data.size % AES_BLOCK_SIZE != 0) return null
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(data)
        } catch (e: Exception) {
            null
        }
    }

    /** PKCS7 去填充，对应 crypto.go pkcs7Unpad。 */
    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val pad = data[data.size - 1].toInt() and 0xff
        if (pad <= 0 || pad > data.size) return data
        for (i in 0 until pad) {
            if (data[data.size - 1 - i].toInt() and 0xff != pad) return data
        }
        return data.copyOfRange(0, data.size - pad)
    }

    /** 小端序读 u32，对应 crypto.go readU32LE。 */
    private fun readU32LE(data: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset + 4 > data.size) return null
        val v = (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)
        return v to (offset + 4)
    }

    /** 音频格式探测，对应 crypto.go detectAudioExt。 */
    private fun detectAudioExt(data: ByteArray): String {
        if (data.size >= 4 && String(data.copyOfRange(0, 4), Charsets.US_ASCII) == "fLaC") {
            return "flac"
        }
        if (data.size >= 3 && String(data.copyOfRange(0, 3), Charsets.US_ASCII) == "ID3") {
            return "mp3"
        }
        if (data.size >= 4 && String(data.copyOfRange(0, 4), Charsets.US_ASCII) == "OggS") {
            return "ogg"
        }
        if (data.size >= 8 && String(data.copyOfRange(4, 8), Charsets.US_ASCII) == "ftyp") {
            return "m4a"
        }
        return "mp3"
    }
}
