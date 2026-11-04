package xyz.cncnlp.soundwave.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.media3.common.util.UnstableApi
class CleartextPolicyDataSourceTest {
    @Test
    fun deniedHttpHostIsRejectedBeforeDelegateOpens() {
        val delegate = RecordingDataSource()
        var checks = 0
        val source = CleartextPolicyDataSource(delegate) { host ->
            checks++
            assertEquals("outside.example", host)
            false
        }

        try {
            source.open(DataSpec(Uri.parse("http://outside.example/audio")))
            fail("unlisted HTTP host must be rejected")
        } catch (_: CleartextTrafficNotPermittedException) {
            // Expected: no request reaches the delegate.
        }

        assertEquals(1, checks)
        assertEquals(0, delegate.openCount)
    }

    @Test
    fun permittedHttpHostIsCheckedOnceAndDelegated() {
        val delegate = RecordingDataSource()
        var checkedHost = ""
        var checks = 0
        val source = CleartextPolicyDataSource(delegate) { host ->
            checks++
            checkedHost = host
            true
        }

        assertEquals(1L, source.open(DataSpec(Uri.parse("HTTP://MobileCDN.Kugou.com/audio"))))
        assertEquals("mobilecdn.kugou.com", checkedHost)
        assertEquals(1, checks)
        assertEquals(1, delegate.openCount)
    }

    @Test
    fun httpsDoesNotConsultCleartextPolicy() {
        val delegate = RecordingDataSource()
        val source = CleartextPolicyDataSource(delegate) {
            fail("HTTPS must not consult the cleartext policy")
            false
        }

        assertEquals(1L, source.open(DataSpec(Uri.parse("https://audio.example/audio"))))
        assertEquals(1, delegate.openCount)
    }

    private class RecordingDataSource : DataSource {
        var openCount = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            openCount++
            return 1L
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = null

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() = Unit
    }
}
