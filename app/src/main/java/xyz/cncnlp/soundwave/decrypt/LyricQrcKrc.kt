package xyz.cncnlp.soundwave.decrypt

import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * QQ 音乐 QRC 与酷狗 KRC 歌词解密（纯算法移植，无业务接线）。
 *
 * 算法来源：folia-major 项目的 src/utils/lyrics/providers/qrcDecrypt.ts（共 399 行）
 * 与 krcDecrypt.ts（共 70 行），本文件为其 Kotlin 等价实现：
 * - decryptQrc：qrcDecrypt()（第 370-399 行）——hex/原始字节兼容、24 字节 3DES 密钥、
 *   ECB 逐 8 字节块解密、deflate(zlib) 解压为 UTF-8 文本
 * - decryptKrc：krcDecrypt()（第 56-69 行）——跳过 "krc1" 4 字节头、16 字节静态 key 循环异或、
 *   deflate(zlib) 解压为 UTF-8 文本
 *
 * 3DES 说明：qrcDecrypt.ts 的 tripledes_key_setup(key, 0)（第 294-312 行，0 = DECRYPT）
 * 生成 [D(key16), E(key8), D(key0)]，tripledes_crypt 依序施加，即
 * 明文 = D(key0)( E(key8)( D(key16)(密文) ) )，正是标准 3DES EDE 解密。
 * 故此处直接用 javax.crypto 的 DESede/ECB/NoPadding + SecretKeySpec(原 24 字节, "DESede")
 * 于 DECRYPT_MODE，与 TS 逐比特等价（NcmDecrypt.kt 同样使用 javax.crypto）。
 *
 * 解压用 java.util.zip.Inflater（默认 nowrap=false，即 zlib 格式，
 * 等价于 TS 的 DecompressionStream('deflate')），用完在 finally 中调用 end()。
 * 任何异常一律返回 null。
 */
object LyricQrcKrc {

    // qrcDecrypt.ts 第 383 行：new TextEncoder().encode('!@#)(*$%123ZXC!@!@#)(NHL')，24 字节
    private val QRC_KEY = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)

    // krcDecrypt.ts 第 8-10 行：KRC_KEY，16 字节（其中 206、210 超出有符号字节范围）
    private val KRC_KEY = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        206.toByte(), 210.toByte(), 110, 105
    )

    private const val DES_BLOCK_SIZE = 8

    /**
     * QQ 音乐 QRC 歌词解密。
     *
     * [encrypted] 为密文字节：若前 2 字节是 ASCII hex 字符且总长为偶数，
     * 视为 hex 编码先解码（对应 qrcDecrypt.ts 的 typeof string 分支，第 372-377 行）；
     * 否则按原始 3DES 密文处理。成功返回明文 LRC 文本，失败返回 null。
     */
    fun decryptQrc(encrypted: ByteArray): String? {
        if (encrypted.isEmpty()) return null
        return try {
            val cipherBytes = maybeHexDecode(encrypted)
            tripleDesEcbDecrypt(cipherBytes)?.let { inflateToString(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 酷狗 KRC 歌词解密。
     *
     * 跳过前 4 字节 "krc1" 头，剩余字节与 [KRC_KEY] 循环异或（krcDecrypt.ts 第 62-67 行），
     * 再 deflate 解压。成功返回明文 LRC 文本，失败返回 null。
     */
    fun decryptKrc(encrypted: ByteArray): String? {
        if (encrypted.size <= 4) return null
        return try {
            val data = ByteArray(encrypted.size - 4)
            for (i in data.indices) {
                data[i] = (encrypted[i + 4].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte()
            }
            inflateToString(data)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 3DES ECB 解密，每 8 字节一块独立处理，NoPadding。
     * 末块不足 8 字节时补零解密、只取原长度（照 qrcDecrypt.ts 第 387-396 行）。
     * 输入为空或非正长度返回 null。
     */
    private fun tripleDesEcbDecrypt(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QRC_KEY, "DESede"))
        val out = ByteArray(data.size)
        var i = 0
        while (i < data.size) {
            val blockLen = minOf(DES_BLOCK_SIZE, data.size - i)
            val block = ByteArray(DES_BLOCK_SIZE)
            data.copyInto(block, 0, i, i + blockLen)
            val decrypted = cipher.doFinal(block)
            decrypted.copyInto(out, i, 0, blockLen)
            i += DES_BLOCK_SIZE
        }
        return out
    }

    /**
     * 若 [input] 形如 hex 文本（前 2 字节为 ASCII hex 且长度为偶数）则解码为字节，否则原样返回。
     * 对应 qrcDecrypt.ts 第 372-377 行的 typeof string 分支（含 .trim()）。
     */
    private fun maybeHexDecode(input: ByteArray): ByteArray {
        val hex = trimAscii(input)
        if (hex.size >= 2 && hex.size % 2 == 0 && isHexDigit(hex[0]) && isHexDigit(hex[1])) {
            return hexDecode(hex)
        }
        return input
    }

    private fun hexDecode(hex: ByteArray): ByteArray {
        val out = ByteArray(hex.size / 2)
        var i = 0
        while (i < hex.size) {
            out[i / 2] = ((hexNibble(hex[i]) shl 4) or hexNibble(hex[i + 1])).toByte()
            i += 2
        }
        return out
    }

    private fun hexNibble(b: Byte): Int {
        val c = b.toInt() and 0xFF
        return when (c) {
            in 0x30..0x39 -> c - 0x30
            in 0x41..0x46 -> c - 0x37
            in 0x61..0x66 -> c - 0x57
            else -> throw IllegalArgumentException("not a hex digit: $c")
        }
    }

    private fun isHexDigit(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c in 0x30..0x39 || c in 0x41..0x46 || c in 0x61..0x66
    }

    private fun trimAscii(data: ByteArray): ByteArray {
        var start = 0
        var end = data.size
        while (start < end && isAsciiSpace(data[start])) start++
        while (end > start && isAsciiSpace(data[end - 1])) end--
        return if (start == 0 && end == data.size) data else data.copyOfRange(start, end)
    }

    private fun isAsciiSpace(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c == 0x20 || c in 0x09..0x0D
    }

    /**
     * zlib 格式 inflate 解压为 UTF-8 字符串（等价于 TS 的 decompressDeflate）。
     * Inflater 用完在 finally 中 end()。解压产生任何输出前失败则抛出（对应 TS
     * 的 chunks.length === 0 时 rethrow），已有部分输出后出错则保留部分结果。
     */
    private fun inflateToString(data: ByteArray): String {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n <= 0) break
                chunks.add(buffer.copyOfRange(0, n))
                total += n
            }
        } catch (e: Exception) {
            if (total == 0) throw e
        } finally {
            inflater.end()
        }
        require(total > 0) { "no decompressed output" }
        val out = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return String(out, Charsets.UTF_8)
    }
}
