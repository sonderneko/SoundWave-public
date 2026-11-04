package xyz.cncnlp.soundwave.network

import android.net.Uri
import android.security.NetworkSecurityPolicy
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.Locale

/** Rejects cleartext media requests before the underlying HTTP source can open a connection. */
@androidx.media3.common.util.UnstableApi
internal class CleartextPolicyDataSource(
    private val delegate: DataSource,
    private val cleartextAllowed: (String) -> Boolean = ::systemCleartextAllowed
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme?.equals("http", ignoreCase = true) == true) {
            val host = uri.host?.lowercase(Locale.ROOT)
                ?: throw CleartextTrafficNotPermittedException()
            val permitted = try {
                cleartextAllowed(host)
            } catch (_: Exception) {
                false
            }
            if (!permitted) throw CleartextTrafficNotPermittedException()
        }
        return delegate.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    @Throws(IOException::class)
    override fun close() = delegate.close()

    internal class Factory(
        private val delegateFactory: DataSource.Factory,
        private val cleartextAllowed: (String) -> Boolean = ::systemCleartextAllowed
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CleartextPolicyDataSource(delegateFactory.createDataSource(), cleartextAllowed)
    }
}

private fun systemCleartextAllowed(host: String): Boolean =
    NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)

internal class CleartextTrafficNotPermittedException : IOException(
    "Cleartext media traffic is denied by the network security policy"
)
