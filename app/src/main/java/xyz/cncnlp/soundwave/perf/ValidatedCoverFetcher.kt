package xyz.cncnlp.soundwave.perf

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import xyz.cncnlp.soundwave.CacheManager
import xyz.cncnlp.soundwave.isValidCoverImageFile

/** Routes remote Coil images through the same bounded, DNS-pinned cover pipeline as cached covers. */
internal class ValidatedCoverFetcherFactory(
    private val resolveInlineCover: (Context, String) -> File? = { context, url ->
        CacheManager.coverModel(context, url) as? File
    },
    private val resolveCover: (Context, String) -> String = { context, url ->
        CacheManager.ensureCover(context, url)
    }
) : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        val scheme = data.scheme?.lowercase()
        val context = options.context.applicationContext
        if (scheme == "data") return ValidatedInlineDataCoverFetcher(data, context, resolveInlineCover)
        if (scheme == "file") {
            val path = data.path?.takeIf { it.isNotBlank() } ?: return null
            return ValidatedCoverFileFetcher(File(path))
        }
        if (scheme != "http" && scheme != "https") return null
        return ValidatedCoverFetcher(data, context, resolveCover)
    }
}

/** Cached File models otherwise use Coil's stock file fetcher and can race async cache validation. */
internal class ValidatedCoverFileFetcherFactory : Fetcher.Factory<File> {
    override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher =
        ValidatedCoverFileFetcher(data)
}

private class ValidatedCoverFileFetcher(private val file: File) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        if (!file.isAbsolute || !isValidCoverImageFile(file)) {
            throw IOException("Cover file exceeds image validation limits")
        }
        SourceResult(
            source = ImageSource(file = file.toOkioPath()),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }
}

private class ValidatedInlineDataCoverFetcher(
    private val uri: Uri,
    private val context: Context,
    private val resolveInlineCover: (Context, String) -> File?
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val dataUrl = uri.toString()
        // CacheManager returning null normally lets call sites keep the original model. This
        // fetcher owns data: URIs so Coil cannot fall through and decode an oversized payload.
        if (!xyz.cncnlp.soundwave.isCoverDataUrlSizeAllowed(dataUrl.length)) {
            throw IOException("Inline cover exceeds size limit")
        }
        val file = resolveInlineCover(context, dataUrl)
            ?: throw IOException("Inline cover could not be validated")
        if (!file.isAbsolute || !isValidCoverImageFile(file)) {
            throw IOException("Validated inline cover cache file is unavailable")
        }
        SourceResult(
            source = ImageSource(file = file.toOkioPath()),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }
}

private class ValidatedCoverFetcher(
    private val uri: Uri,
    private val context: Context,
    private val resolveCover: (Context, String) -> String
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val originalUrl = uri.toString()
        val resolvedPath = resolveCover(context, originalUrl)
        // ensureCover returns the input URL when policy checks, storage, or download fail.
        // Do not return null from the factory or allow Coil's stock HTTP fetcher to bypass policy.
        if (resolvedPath.isBlank() || resolvedPath == originalUrl) {
            throw IOException("Cover could not be loaded through the validated image path")
        }
        val file = File(resolvedPath)
        if (!file.isAbsolute || !isValidCoverImageFile(file)) {
            throw IOException("Validated cover cache file is unavailable")
        }
        SourceResult(
            source = ImageSource(file = file.toOkioPath()),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }
}
