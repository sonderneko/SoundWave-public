package xyz.cncnlp.soundwave.script

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import java.util.LinkedHashMap

/**
 * Coalesces identical in-flight suspend work and memoizes only negative results.
 * Successful URLs are deliberately not retained here; UrlResolver owns their
 * media-validation-aware cache and expiry policy.
 */
internal class SingleFlightNegativeCache<K : Any, V>(
    private val failureTtlMillis: Long,
    private val maxFailureEntries: Int,
    private val isFailure: (V) -> Boolean,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private sealed interface FlightResult<out V> {
        data class Value<V>(val value: V) : FlightResult<V>
        object Retry : FlightResult<Nothing>
    }

    private sealed interface Join<out V> {
        data class Cached<V>(val value: V) : Join<V>
        data class Waiting<V>(val deferred: CompletableDeferred<FlightResult<V>>) : Join<V>
        data class Producing<V>(val deferred: CompletableDeferred<FlightResult<V>>) : Join<V>
    }

    private data class CachedFailure<V>(val value: V, val expiresAt: Long)

    private val lock = Any()
    private val flights = mutableMapOf<K, CompletableDeferred<FlightResult<V>>>()
    private val failures = LinkedHashMap<K, CachedFailure<V>>(16, 0.75f, true)

    init {
        require(failureTtlMillis >= 0L)
        require(maxFailureEntries > 0)
    }

    suspend fun run(key: K, producer: suspend () -> V): V {
        while (true) {
            val join = synchronized(lock) {
                val cached = failures[key]
                if (cached != null) {
                    if (cached.expiresAt > nowMillis()) return@synchronized Join.Cached(cached.value)
                    failures.remove(key)
                }
                val existing = flights[key]
                if (existing != null) Join.Waiting(existing)
                else CompletableDeferred<FlightResult<V>>().let { deferred ->
                    flights[key] = deferred
                    Join.Producing(deferred)
                }
            }

            when (join) {
                is Join.Cached -> return join.value
                is Join.Waiting -> when (val result = join.deferred.await()) {
                    is FlightResult.Value -> return result.value
                    FlightResult.Retry -> continue
                }
                is Join.Producing -> {
                    try {
                        val value = producer()
                        synchronized(lock) {
                            if (failureTtlMillis > 0L && isFailure(value)) {
                                failures[key] = CachedFailure(value, nowMillis() + failureTtlMillis)
                                while (failures.size > maxFailureEntries) {
                                    val oldest = failures.entries.iterator()
                                    if (oldest.hasNext()) { oldest.next(); oldest.remove() } else break
                                }
                            }
                            if (flights[key] === join.deferred) flights.remove(key)
                            join.deferred.complete(FlightResult.Value(value))
                        }
                        return value
                    } catch (cancelled: CancellationException) {
                        synchronized(lock) {
                            if (flights[key] === join.deferred) flights.remove(key)
                            join.deferred.complete(FlightResult.Retry)
                        }
                        throw cancelled
                    } catch (error: Throwable) {
                        synchronized(lock) {
                            if (flights[key] === join.deferred) flights.remove(key)
                            join.deferred.complete(FlightResult.Retry)
                        }
                        throw error
                    }
                }
            }
        }
    }

    fun clearFailures() = synchronized(lock) { failures.clear() }

    internal val failureCount: Int get() = synchronized(lock) { failures.size }
    internal val inFlightCount: Int get() = synchronized(lock) { flights.size }
}
