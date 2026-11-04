package xyz.cncnlp.soundwave.source

/** A list request can succeed with an authoritative empty list or fail outright. */
internal sealed interface FeedListOutcome<out T> {
    data class Success<T>(val data: T) : FeedListOutcome<T>
    object Failure : FeedListOutcome<Nothing>
}

/** Preserve the legacy List API contract for callers that do not consume status. */
internal fun <T> FeedListOutcome<List<T>>.legacyListValue(): List<T> = when (this) {
    is FeedListOutcome.Success -> data
    FeedListOutcome.Failure -> emptyList()
}

internal fun classifyListHttpResponse(code: Int, text: String): FeedListOutcome<String> =
    if (code in 200..299 && text.isNotEmpty()) FeedListOutcome.Success(text) else FeedListOutcome.Failure

/** Decode only successful non-empty HTTP text; a transport/status failure never reaches the parser. */
internal fun <T> decodeListResponseOutcome(
    response: FeedListOutcome<String>,
    parse: (String) -> FeedListOutcome<List<T>>
): FeedListOutcome<List<T>> = when (response) {
    is FeedListOutcome.Success -> parse(response.data)
    FeedListOutcome.Failure -> FeedListOutcome.Failure
}

/** A thrown request/parser error is a failure, never an empty successful list. */
internal fun <T> captureListRequestOutcome(
    request: () -> FeedListOutcome<List<T>>
): FeedListOutcome<List<T>> = try {
    request()
} catch (cancelled: java.util.concurrent.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    FeedListOutcome.Failure
}
