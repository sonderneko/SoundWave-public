package xyz.cncnlp.soundwave

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * 云账号密码（审计 F-07）落盘加密：AndroidKeyStore 内 AES/GCM，密钥不出安全硬件。
 *
 * 设计约定（迁移兼容）：key "pass" 中存放的是旧版明文或 "v2:" 前缀密文二者之一。
 * 读取方一律经 [classify] 判定（批19 #3）：仅无前缀视为旧明文并尽力 [encrypt] 原地迁移；
 * 带前缀但解不开 = 密文损坏 / KeyStore 变化 / 数据篡改（Corrupted），绝不静默当明文。
 * 除迁移所需的瞬时明文外，后续任何写入都必须是 [encrypt] 的产物（空密码存空串），
 * 绝不降级回存明文。
 */
internal object SecretStore {
    private const val KEY_ALIAS = "cloud_cred_password"
    private const val PACKED_PREFIX = "v2:"

    /** 明文 → "v2:" + Base64(iv‖密文)；空明文返回 ""；keystore/加密不可用时返回 null。 */
    fun encrypt(context: Context, plain: String): String? {
        if (plain.isEmpty()) return ""
        // 1.0.655：KeyStore 守护进程在开机/解锁初期可能瞬时失败；短暂重试，避免因一次抖动
        // 导致密码未落盘、下次启动被提示“需重新登录”。
        repeat(TRANSIENT_ATTEMPTS) { attempt ->
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                // randomized=true 禁止 caller-provided IV；让 AndroidKeyStore 生成随机 IV。
                cipher.init(Cipher.ENCRYPT_MODE, keyForEncrypt())
                val iv = cipher.iv
                val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                return PACKED_PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
            } catch (_: Exception) {
                backoff(attempt)
            }
        }
        return null
    }

    /** 密文 → 明文；key 缺失、格式不符、认证失败（含旧明文输入）或 KeyStore 暂不可用一律返回 null。 */
    fun decrypt(context: Context, enc: String): String? =
        (decryptDetailed(enc) as? DecryptResult.Ok)?.plain

    /**
     * 解密判定结果（批19 #3，additive）：拆开旧「decrypt 返回 null」的粗判定 ——
     * 仅无 v2: 前缀视为旧版明文（可迁移）；带前缀但解不开 = 密文损坏 / KeyStore 变化 /
     * 数据篡改，处置一致（Corrupted，提示重新登录），绝不静默当明文沿用。
     * 1.0.655：KeyStore 服务瞬时异常（非认证失败、非密钥失效）单列为 [Unavailable]，
     * 调用方应静默稍后重试，不得据此提示重新登录或清空凭据。
     */
    sealed interface DecryptResult {
        /** 带前缀且解密成功。 */
        data class Ok(val plain: String) : DecryptResult

        /** 无 v2: 前缀：旧版明文，调用方应尽力 [encrypt] 原地迁移。 */
        object LegacyPlaintext : DecryptResult

        /** 带前缀但确定不可读：密文损坏 / 密钥缺失或失效 / GCM 认证失败 / 数据篡改。 */
        object Corrupted : DecryptResult

        /** KeyStore 暂时不可用（服务未就绪、IPC 失败等）；密文本身可能完好，稍后重试。 */
        object Unavailable : DecryptResult
    }

    /**
     * 密文分类（批19 #3，additive）：单次解密判定，不回写存储。
     * 空串按旧版明文（旧版空密码即存明文空串，与 [encrypt] 空串语义一致）。
     */
    fun classify(context: Context, stored: String): DecryptResult = decryptDetailed(stored)

    private fun decryptDetailed(stored: String): DecryptResult {
        if (!stored.startsWith(PACKED_PREFIX)) return DecryptResult.LegacyPlaintext
        val raw = try {
            Base64.decode(stored.removePrefix(PACKED_PREFIX), Base64.NO_WRAP)
        } catch (_: Exception) {
            return DecryptResult.Corrupted
        }
        if (raw.size <= 12) return DecryptResult.Corrupted
        val iv = raw.copyOfRange(0, 12)
        val encrypted = raw.copyOfRange(12, raw.size)
        var last: DecryptResult = DecryptResult.Unavailable
        for (attempt in 0 until TRANSIENT_ATTEMPTS) {
            last = try {
                // 解密路径绝不生成新密钥：密钥缺失=旧密文永久不可读（Corrupted），生成新钥只会掩盖问题。
                val key = existingKey() ?: return DecryptResult.Corrupted
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                return DecryptResult.Ok(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            } catch (failure: Exception) {
                classifyFailure(failure)
            }
            if (last == DecryptResult.Corrupted) return last
            backoff(attempt)
        }
        return last
    }

    /**
     * 纯函数（可单测）：区分“确定不可读”与“KeyStore 瞬时不可用”。
     * 普通 InvalidKeyException / KeyStoreException / ProviderException 在 keystore2 上常由服务忙、
     * IPC 失败等瞬时原因引起，归为 [DecryptResult.Unavailable]；只有认证失败与密钥永久失效才是 Corrupted。
     */
    internal fun classifyFailure(failure: Throwable): DecryptResult {
        var t: Throwable? = failure
        var depth = 0
        while (t != null && depth < 6) {
            val name = t.javaClass.name
            if (t is javax.crypto.AEADBadTagException || t is javax.crypto.BadPaddingException ||
                t is javax.crypto.IllegalBlockSizeException || t is java.security.UnrecoverableKeyException ||
                t is IllegalArgumentException || name.endsWith("KeyPermanentlyInvalidatedException")
            ) return DecryptResult.Corrupted
            t = t.cause.takeIf { it !== t }
            depth++
        }
        return DecryptResult.Unavailable
    }

    private fun backoff(attempt: Int) {
        if (attempt >= TRANSIENT_ATTEMPTS - 1) return
        try {
            Thread.sleep(40L * (attempt + 1))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun existingKey(): javax.crypto.SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey
        if (key == null && store.containsAlias(KEY_ALIAS)) {
            // 别名存在但暂时取不到密钥：视为瞬时故障，绝不当作“密钥缺失”。
            throw java.security.KeyStoreException("key alias present but not loadable")
        }
        return key
    }

    /** 加密取钥；并发首次生成串行化，避免两次 generateKey 覆盖别名使先写入的密文永久失效。 */
    @Synchronized
    private fun keyForEncrypt(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        // 别名存在却取不到：瞬时故障，抛出交由重试，绝不生成新钥覆盖旧钥（会让已存密文全部失效）。
        if (store.containsAlias(KEY_ALIAS)) throw java.security.KeyStoreException("key alias present but not loadable")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private const val TRANSIENT_ATTEMPTS = 3
}
