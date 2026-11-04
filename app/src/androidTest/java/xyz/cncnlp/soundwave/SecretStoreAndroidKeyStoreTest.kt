package xyz.cncnlp.soundwave

import android.content.Context
import android.security.keystore.KeyInfo
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/** Real device/emulator coverage: a JVM cipher cannot enforce AndroidKeyStore caller-IV policy.
 * Uses the production alias without deleting/replacing its key or touching account preferences.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreAndroidKeyStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun accountAndWebDavPasswordsRoundTripThroughAndroidKeyStore() {
        for (password in listOf("account-密码-🔐", "webdav:p@ss/word? &=+")) {
            val packed = encryptRequired(password)
            assertTrue(packed.startsWith("v2:"))
            assertEquals(12 + password.toByteArray(Charsets.UTF_8).size + 16, unpack(packed).size)
            assertEquals(password, SecretStore.decrypt(context, packed))
            assertEquals(SecretStore.DecryptResult.Ok(password), SecretStore.classify(context, packed))
        }
    }

    @Test
    fun repeatedEncryptionUsesDifferentProviderGeneratedIvs() {
        val password = "same password"
        val first = encryptRequired(password)
        val second = encryptRequired(password)
        assertFalse(unpack(first).copyOfRange(0, 12).contentEquals(unpack(second).copyOfRange(0, 12)))
        assertFalse(first == second)
        assertEquals(password, SecretStore.decrypt(context, first))
        assertEquals(password, SecretStore.decrypt(context, second))
    }

    @Test
    fun independentlyPackedOriginalV2CiphertextRemainsReadableAfterEncryption() {
        // Bootstrap the existing production alias; never delete/recreate it for the test.
        encryptRequired("bootstrap")
        val originalKey = existingKey()
        val password = "original-v2-密码"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, originalKey)
        assertEquals(12, cipher.iv.size)
        // Original wire format, built independently of SecretStore.encrypt. A legacy caller IV
        // has no distinguishing marker: decrypt must continue accepting this iv || ciphertext+tag.
        val originalV2 = "v2:" + Base64.encodeToString(
            cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
        assertEquals(password, SecretStore.decrypt(context, originalV2))
        encryptRequired("subsequent password")
        assertEquals(password, SecretStore.decrypt(context, originalV2))
        assertEquals(SecretStore.DecryptResult.Ok(password), SecretStore.classify(context, originalV2))
    }

    @Test
    fun randomizedEncryptionPolicyRemainsEnabledAndRejectsCallerIv() {
        encryptRequired("bootstrap")
        val key = existingKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        // KeyInfo does not expose randomized-encryption state in compileSdk 34; the caller-IV
        // rejection below verifies the configured policy behavior directly.
        val failure = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").init(
                Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12))
            )
        }.exceptionOrNull()
        assertTrue("AndroidKeyStore must reject a caller IV for encryption", failure is InvalidAlgorithmParameterException)
    }

    @Test
    fun emptyPasswordAndTamperedCiphertextKeepExistingSemantics() {
        assertEquals("", SecretStore.encrypt(context, ""))
        assertEquals(SecretStore.DecryptResult.LegacyPlaintext, SecretStore.classify(context, ""))
        val raw = unpack(encryptRequired("authenticated password"))
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        val tampered = "v2:" + Base64.encodeToString(raw, Base64.NO_WRAP)
        assertEquals(SecretStore.DecryptResult.Corrupted, SecretStore.classify(context, tampered))
    }

    private fun encryptRequired(password: String): String {
        val packed = SecretStore.encrypt(context, password)
        assertNotNull("Encryption must succeed under AndroidKeyStore randomized=true", packed)
        return requireNotNull(packed)
    }

    private fun unpack(packed: String): ByteArray =
        Base64.decode(packed.removePrefix("v2:"), Base64.NO_WRAP)

    private fun existingKey(): SecretKey = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey("cloud_cred_password", null) as SecretKey
    }
}
