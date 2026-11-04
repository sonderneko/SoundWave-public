package xyz.cncnlp.soundwave

import java.net.SocketTimeoutException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import xyz.cncnlp.soundwave.source.FeedListOutcome
import xyz.cncnlp.soundwave.source.PlaylistSearchOutcome
import xyz.cncnlp.soundwave.source.direct.PlatformTransportError
import xyz.cncnlp.soundwave.source.direct.PlatformTransportErrorKind
import xyz.cncnlp.soundwave.source.direct.PlatformTransportOutcome
import xyz.cncnlp.soundwave.source.direct.isCleartextApiAllowed
import xyz.cncnlp.soundwave.source.direct.ApiPolicyRejectedException

/** Managed playlist catalog transport. Two attempts and the legacy delay; no credentials/state. */
internal class PlaylistCatalogTextClient(
    private val absoluteUrl: (String) -> String,
    private val exchange: (String) -> Pair<Int, String>,
    private val ensureActive: () -> Unit = {},
    private val pause: () -> Unit = {}
) {
    fun get(path: String): PlatformTransportOutcome<String> {
        val url = absoluteUrl(path)
        ensureActive()
        if (!isCleartextApiAllowed(url)) return failure(PlatformTransportErrorKind.POLICY)
        var result: PlatformTransportOutcome<String> = failure(PlatformTransportErrorKind.NETWORK)
        repeat(2) {
            ensureActive()
            result = try {
                val (code, text) = exchange(url)
                ensureActive()
                when {
                    code !in 200..299 -> PlatformTransportOutcome.Failure(
                        PlatformTransportError(PlatformTransportErrorKind.HTTP_STATUS, httpStatus = code))
                    text.isEmpty() -> failure(PlatformTransportErrorKind.PARSE)
                    else -> PlatformTransportOutcome.Success(text)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ApiPolicyRejectedException) {
                failure(PlatformTransportErrorKind.POLICY)
            } catch (_: SocketTimeoutException) {
                failure(PlatformTransportErrorKind.TIMEOUT)
            } catch (_: InterruptedIOException) {
                // Includes OkHttp's whole-call timeout; scope cancellation escapes above.
                failure(PlatformTransportErrorKind.TIMEOUT)
            } catch (_: Exception) {
                failure(PlatformTransportErrorKind.NETWORK)
            }
            if (result is PlatformTransportOutcome.Success) return result
            ensureActive()
            pause()
            ensureActive()
        }
        return result
    }

    private fun failure(kind: PlatformTransportErrorKind) =
        PlatformTransportOutcome.Failure(PlatformTransportError(kind))
}

/** One API family, not a universal music/provider parser. Source identifiers remain explicit. */
internal class PlaylistCatalogApi(private val text: PlaylistCatalogTextClient) {
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun searchPath(keyword: String, source: String, limit: Int): String {
        val srcQ = if (source.isNotEmpty()) "&source=${encode(source)}&src=${encode(source)}" else ""
        return "/music/playlist?action=search&keyword=${encode(keyword.ifBlank { "推荐" })}$srcQ&limit=$limit"
    }

    fun searchLegacy(keyword: String, source: String, limit: Int): List<Playlist> =
        when (val response = text.get(searchPath(keyword, source, limit))) {
            is PlatformTransportOutcome.Success -> PlaylistCatalogPayloads.legacy(response.value, source)
            is PlatformTransportOutcome.Failure -> emptyList()
        }

    fun searchCategory(keyword: String, source: String, limit: Int): PlatformTransportOutcome<List<Playlist>> =
        decode(text.get(searchPath(keyword, source, limit.coerceIn(1, 200))), source, false)

    fun recommend(source: String, limit: Int): PlatformTransportOutcome<List<Playlist>> =
        decode(text.get("/music/playlist?action=recommend&source=${encode(source)}&src=${encode(source)}&limit=$limit"), source, true)

    private fun decode(response: PlatformTransportOutcome<String>, source: String, nested: Boolean): PlatformTransportOutcome<List<Playlist>> =
        when (response) {
            is PlatformTransportOutcome.Success -> PlaylistCatalogPayloads.validated(response.value, source, nested)
            is PlatformTransportOutcome.Failure -> response
        }
}

/** Loss of detail is deliberate at the existing UI contracts, not inside transport/decoding. */
internal fun PlatformTransportOutcome<List<Playlist>>.asCategoryOutcome(): PlaylistSearchOutcome = when (this) {
    is PlatformTransportOutcome.Success -> PlaylistSearchOutcome.Success(value)
    is PlatformTransportOutcome.Failure -> PlaylistSearchOutcome.Failure
}
internal fun PlatformTransportOutcome<List<Playlist>>.asFeedOutcome(): FeedListOutcome<List<Playlist>> = when (this) {
    is PlatformTransportOutcome.Success -> FeedListOutcome.Success(value)
    is PlatformTransportOutcome.Failure -> FeedListOutcome.Failure
}
