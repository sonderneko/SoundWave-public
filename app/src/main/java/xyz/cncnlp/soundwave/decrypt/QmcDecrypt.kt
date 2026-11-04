package xyz.cncnlp.soundwave.decrypt

import java.io.InputStream
import java.io.OutputStream

/**
 * QQ 音乐 mflac / mgg / tkm 加密格式解密。
 *
 * 算法来源：music-lib 项目的 qq 包 crypto.go（DecryptQQ / qqMask 系列），
 * 本文件为其 Kotlin 逐行移植：mask58 常量、superA/superB、mask128 的生成与逆推、
 * 32768 跳步 XOR 循环均与 Go 版逐字节一致。
 *
 * 纯 Kotlin 实现：零 Android 依赖，仅使用 ByteArray 与标准库。
 */
object QmcDecrypt {

    // 掩码探测只需文件头（与 decryptXor 的 32768 跳步边界一致），流式解密先读满该长度再按块处理。
    private const val HEAD_PROBE_BYTES = 32768
    private const val STREAM_BUFFER_BYTES = 64 * 1024

    // 对应 Go 版 defaultQQSuper58A / defaultQQSuper58B（crypto.go 第 20-21 行）
    private const val SUPER_A = 195
    private const val SUPER_B = 216

    // 对应 Go 版 defaultQQMask58（crypto.go 第 8-17 行）。
    // 历史命名 "mask58"，实际为 8 x 7 = 56 字节，此处逐值照抄。
    private val DEFAULT_MASK_58 = intArrayOf(
        74, 214, 202, 144, 103, 247, 82,
        94, 149, 35, 159, 19, 17, 126,
        71, 116, 61, 144, 170, 63, 81,
        198, 9, 213, 159, 250, 102, 249,
        243, 214, 161, 144, 160, 247, 240,
        29, 149, 222, 159, 132, 17, 244,
        14, 116, 187, 144, 188, 63, 146,
        0, 9, 91, 159, 98, 102, 161
    ).map { it.toByte() }.toByteArray()

    private val FLAC_4 = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
    private val ID3_3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())
    private val OGGS_4 = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())
    private val FTYP_4 = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    /**
     * 解密 QQ 音乐加密数据。
     *
     * @param encrypted 加密的音频数据。
     * @param ext 源文件扩展名（如 "mflac"、"qmcflac"、"bkcflac"、"mgg"、"qmcogg"、"tkm"）。
     * @return (解密后的音频数据, 真实扩展名)，空输入抛出 IllegalArgumentException。
     */
    fun decrypt(encrypted: ByteArray, ext: String): Pair<ByteArray, String> {
        require(encrypted.isNotEmpty()) { "empty input" }

        var matrix128 = generateMask128From58(DEFAULT_MASK_58, SUPER_A.toByte(), SUPER_B.toByte())
        // 与 Go 版一致：仅 "mflac" 尝试从密文头探测真实掩码
        if (ext == "mflac") {
            detectQQMaskFromEncrypted(encrypted)?.let { matrix128 = it }
        }

        val plain = decryptXor(encrypted, matrix128)

        val realExt = when (ext) {
            "mflac", "qmcflac", "bkcflac" -> "flac"
            "mgg", "qmcogg" -> "ogg"
            "tkm" -> "m4a"
            else -> detectAudioExt(plain).ifEmpty { "mp3" }
        }
        return plain to realExt
    }

    /**
     * 流式解密：边读边写，峰值内存 O(64KB)，与 [decrypt] 输出逐字节一致。
     *
     * 解决整文件入堆的双份全文常驻（200MB 加密文件原实现峰值 ~400MB 堆）。
     * 掩码探测仍读文件头（mflac 最多 32768 字节），之后按 64KB 块处理；
     * r/n 双指针游标在块间延续，与 [decryptXor] 的逐字节结果完全一致。
     *
     * @param input 加密数据输入流（调用方负责关闭）。
     * @param output 明文输出流（调用方负责关闭）。
     * @param ext 源文件扩展名（如 "mflac"、"mgg"、"tkm"），空输入抛出 IllegalArgumentException。
     * @return 真实音频扩展名（与 [decrypt] 同规则）。
     */
    fun decryptStream(input: InputStream, output: OutputStream, ext: String): String {
        // 探测掩码需要前 32768 字节（mflac 专用）；其余格式直接读满头部保持游标一致起点。
        val head = ByteArray(HEAD_PROBE_BYTES)
        var headLen = 0
        while (headLen < head.size) {
            val n = input.read(head, headLen, head.size - headLen)
            if (n <= 0) break
            headLen += n
        }
        if (headLen <= 0) throw IllegalArgumentException("empty input")
        val headBytes = if (headLen == head.size) head else head.copyOf(headLen)

        var matrix128 = generateMask128From58(DEFAULT_MASK_58, SUPER_A.toByte(), SUPER_B.toByte())
        if (ext == "mflac") {
            detectQQMaskFromEncrypted(headBytes)?.let { matrix128 = it }
        }

        // r/n 双指针游标跨块延续：与 decryptXor 逐字节一致（跳步检查在每字节游标自增后）。
        var r = -1
        var n = -1
        // 明文头 8 字节用于 else 分支的扩展名探测（复制 decrypt 的 when 之外语义）。
        val plainHead = ByteArray(8)
        var writtenHead = 0
        fun transform(b: Byte): Byte {
            r++
            n++
            if (r == 32768 || (r > 32768 && (r + 1) % 32768 == 0)) {
                r++
                n++
            }
            if (n >= 128) {
                n -= 128
            }
            return (b.toInt() xor matrix128[n].toInt()).toByte()
        }
        fun emit(block: ByteArray, len: Int) {
            for (i in 0 until len) {
                val p = transform(block[i])
                if (writtenHead < plainHead.size) {
                    plainHead[writtenHead] = p
                }
                writtenHead++
                block[i] = p
            }
            if (len > 0) output.write(block, 0, len)
        }

        emit(headBytes, headLen)
        val buf = ByteArray(STREAM_BUFFER_BYTES)
        while (true) {
            val cnt = input.read(buf)
            if (cnt <= 0) break
            emit(buf, cnt)
        }

        return when (ext) {
            "mflac", "qmcflac", "bkcflac" -> "flac"
            "mgg", "qmcogg" -> "ogg"
            "tkm" -> "m4a"
            else -> detectAudioExt(plainHead).ifEmpty { "mp3" }
        }
    }

    /**
     * 按文件头探测真实音频扩展名。
     */
    fun detectAudioExt(data: ByteArray): String {
        if (data.size >= 4 && data.copyOfRange(0, 4).contentEquals(FLAC_4)) return "flac"
        if (data.size >= 3 && data.copyOfRange(0, 3).contentEquals(ID3_3)) return "mp3"
        if (data.size >= 4 && data.copyOfRange(0, 4).contentEquals(OGGS_4)) return "ogg"
        if (data.size >= 8 && data.copyOfRange(4, 8).contentEquals(FTYP_4)) return "m4a"
        return "mp3"
    }

    // qqMask.generateMask128From58：8 轮，每轮 [superA, 正序 7 字节, superB, 逆序 7 字节] -> 128 字节
    private fun generateMask128From58(matrix58: ByteArray, superA: Byte, superB: Byte): ByteArray {
        val out = ByteArray(128)
        var idx = 0
        for (i in 0 until 8) {
            out[idx] = superA
            idx++
            for (j in 0 until 7) {
                out[idx] = matrix58[7 * i + j]
                idx++
            }
            out[idx] = superB
            idx++
            // revChunk = matrix58[49-7i .. 56-7i)，逆序写入
            for (j in 6 downTo 0) {
                out[idx] = matrix58[49 - 7 * i + j]
                idx++
            }
        }
        return out
    }

    // qqMask.generateMask58From128：校验失败返回 null（Go 版返回 error）
    private fun mask58From128(matrix128: ByteArray): ByteArray? {
        if (matrix128.size != 128) return null
        val e = matrix128[0]
        val b = matrix128[8]
        val out = ByteArray(56)
        var p = 0
        for (n in 0 until 8) {
            val i = 16 * n
            val o = 120 - i
            if (matrix128[i] != e || matrix128[i + 8] != b) return null
            for (j in 0 until 7) {
                if (matrix128[i + 1 + j] != matrix128[o + 7 - j]) return null
            }
            for (j in 0 until 7) {
                out[p] = matrix128[i + 1 + j]
                p++
            }
        }
        return out
    }

    // qqMask.Decrypt：r/n 双指针 32768 跳步 XOR
    private fun decryptXor(encrypted: ByteArray, matrix128: ByteArray): ByteArray {
        val out = encrypted.copyOf()
        var r = -1
        var n = -1
        for (i in out.indices) {
            r++
            n++
            if (r == 32768 || (r > 32768 && (r + 1) % 32768 == 0)) {
                r++
                n++
            }
            if (n >= 128) {
                n -= 128
            }
            out[i] = (out[i].toInt() xor matrix128[n].toInt()).toByte()
        }
        return out
    }

    // detectQQMaskFromEncrypted：按 128 字节步进，命中校验且解密头为 "fLaC" 的块即为真实掩码
    private fun detectQQMaskFromEncrypted(encrypted: ByteArray): ByteArray? {
        val max = if (encrypted.size > 32768) 32768 else encrypted.size
        var i = 0
        while (i + 128 <= max) {
            val block = encrypted.copyOfRange(i, i + 128)
            if (mask58From128(block) != null &&
                decryptXor(encrypted.copyOfRange(0, 4), block).contentEquals(FLAC_4)
            ) {
                return block
            }
            i += 128
        }
        return null
    }
}
