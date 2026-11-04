package xyz.cncnlp.soundwave.sync

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 资料名加密（用户 2026-11-03 20:48 拍板的新方案，取代「资料名永不序列化」的旧不变量）。
 *
 * 隐私不变量（新版措辞）：资料名仅以加密形式序列化进 WebDAV 载荷——AES-256-GCM，
 * 密钥由当前 WebDAV 密码经 PBKDF2-HMAC-SHA256（120k 次迭代）派生，随机盐与 IV 随密文
 * 一起存放在载荷字段中；明文永不出现，文件名/owner 的 swown2_ 哈希方案不变。
 *
 * 用途：新设备填好 WebDAV 凭据后可列举同步目录、解开资料名并提示导入，不必先猜中同名；
 * 密码不对（或事后改过密码）时解密失败，按「未发现」处理，不报错、不泄露任何信息。
 *
 * PBKDF2 以 HMAC 原语手工实现：派生口径完全自控，JVM 单测与 Android 运行时行为一致，
 * 不依赖各平台 SecretKeyFactory 的算法名可用性。
 */
internal object WebDavProfileNameCrypto {
    private const val FORMAT_VERSION = 1
    private const val KDF_ID = "PBKDF2-HMAC-SHA256"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    /** 解密端接受的迭代下限/上限：防被篡改的载荷以极端迭代数拖死解密（DoS）或降级迭代。 */
    private const val MIN_ACCEPTED_ITERATIONS = 10_000
    private const val MAX_ACCEPTED_ITERATIONS = 2_000_000
    private const val MAX_NAME_CHARS = 256

    private val random = SecureRandom()

    /**
     * 加密资料名 → 自包含 JSON 串（v/kdf/iter/salt/iv/ct，Base64 标准编码）。
     * 名字为空或密码为空时返回 null（调用方据此跳过写入，载荷不带该字段）。
     */
    fun encrypt(profileName: String, webDavPassword: String): String? {
        val name = profileName.trim()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS || webDavPassword.isEmpty()) return null
        return try {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val key = deriveKey(webDavPassword.toByteArray(Charsets.UTF_8), salt, ITERATIONS, KEY_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val ct = cipher.doFinal(name.toByteArray(Charsets.UTF_8))
            JSONObject()
                .put("v", FORMAT_VERSION)
                .put("kdf", KDF_ID)
                .put("iter", ITERATIONS)
                .put("salt", Base64.getEncoder().encodeToString(salt))
                .put("iv", Base64.getEncoder().encodeToString(iv))
                .put("ct", Base64.getEncoder().encodeToString(ct))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解密 [encrypt] 的产物。任何失败（格式不对、密码不对、密文被篡改）一律返回 null，
     * 调用方按「未发现云端资料」处理，不向用户报错。
     */
    fun decrypt(payload: String, webDavPassword: String): String? {
        if (webDavPassword.isEmpty()) return null
        return try {
            val json = JSONObject(payload)
            if (json.optInt("v", -1) != FORMAT_VERSION || json.optString("kdf") != KDF_ID) return null
            val iterations = json.optInt("iter", -1)
            if (iterations !in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) return null
            val salt = Base64.getDecoder().decode(json.optString("salt"))
            val iv = Base64.getDecoder().decode(json.optString("iv"))
            val ct = Base64.getDecoder().decode(json.optString("ct"))
            if (salt.size !in 8..64 || iv.size != IV_BYTES || ct.isEmpty()) return null
            val key = deriveKey(webDavPassword.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = String(cipher.doFinal(ct), Charsets.UTF_8).trim()
            plain.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_CHARS }
        } catch (_: Exception) {
            null
        }
    }

    /** PBKDF2（RFC 2898）单块派生（keyLen ≤ 32 时一块足够；此处恒为 32）。 */
    internal fun deriveKey(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        require(iterations > 0 && keyBytes in 1..32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        // U1 = PRF(password, salt || INT32_BE(1))
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return t.copyOf(keyBytes)
    }
}
