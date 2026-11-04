package xyz.cncnlp.soundwave.cache

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import java.io.IOException

/** Holds the exact CacheKeyFactory key for the complete CacheDataSource open/read/close lifetime. */
@androidx.media3.common.util.UnstableApi
internal class LeasedCacheDataSource(
    private val delegate: DataSource,
    private val cacheKeyFactory: CacheKeyFactory,
    private val leases: Media3CacheReadLeaseRegistry
) : DataSource {
    private var lease: Media3CacheReadLease? = null

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        check(lease == null) { "DataSource must be closed before it is reopened" }
        lease = leases.acquire(cacheKeyFactory.buildCacheKey(dataSpec))
        try {
            return delegate.open(dataSpec)
        } catch (failure: Throwable) {
            closeAfterFailure(failure)
            throw failure
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return try {
            delegate.read(buffer, offset, length)
        } catch (failure: Throwable) {
            closeAfterFailure(failure)
            throw failure
        }
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        val currentLease = lease
        try {
            delegate.close()
        } finally {
            if (currentLease != null && lease === currentLease) {
                lease = null
                currentLease.close()
            }
        }
    }

    private fun closeAfterFailure(failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
    }

    internal class Factory(
        private val delegateFactory: DataSource.Factory,
        private val cacheKeyFactory: CacheKeyFactory,
        private val leases: Media3CacheReadLeaseRegistry
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            LeasedCacheDataSource(delegateFactory.createDataSource(), cacheKeyFactory, leases)
    }
}
