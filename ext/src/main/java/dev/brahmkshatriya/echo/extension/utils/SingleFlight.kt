package dev.brahmkshatriya.echo.extension.utils

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Deduplicates concurrent identical requests: the first caller for [getOrStart]'s
 * [key] runs [load], every concurrent caller joins its result, and (when a
 * [ttlMs] is set) completed results keep serving that TTL so re-opens of the
 * same page cost zero network calls.
 *
 * The host fires several concurrent flows per page open (details, tracks, feed),
 * each of which can independently reach the same extension endpoint — this turns
 * N duplicate browse sequences into exactly one.
 *
 * A caller that joins a winner which *fails* receives the same failure (a retry
 * storm would defeat the purpose); the next caller after the failure starts a
 * fresh load. Failed results are never cached.
 */
class SingleFlight<T : Any>(
    private val ttlMs: Long = 0L,
    private val maxEntries: Int = 32,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<T>>()
    private val results = object : LinkedHashMap<String, Pair<T, Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<T, Long>>) =
            size > maxEntries
    }

    suspend fun getOrStart(key: String, load: suspend () -> T): T {
        if (ttlMs > 0) synchronized(results) {
            results[key]?.takeIf { clock() - it.second < ttlMs }?.let { return it.first }
        }

        while (true) {
            val existing = inFlight[key]
            if (existing != null) return existing.await()

            val started = CompletableDeferred<T>()
            if (inFlight.putIfAbsent(key, started) == null) {
                try {
                    val value = load()
                    if (ttlMs > 0) synchronized(results) { results[key] = value to clock() }
                    started.complete(value)
                    return value
                } catch (t: Throwable) {
                    started.completeExceptionally(t)
                    throw t
                } finally {
                    inFlight.remove(key)
                }
            }
            // Lost the race to another caller that started first: loop and join it.
        }
    }
}
