package dev.brahmkshatriya.echo.extension.streaming

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs every [blocks] entry concurrently and hands their results to [onResult] in
 * completion order, stopping at the first result for which [onResult] returns
 * `true`. The remaining lanes are cancelled when a winner is found, so a slow or
 * hung lane never delays the winner (a blocking HTTP call cannot be interrupted,
 * but its result is discarded).
 *
 * A lane that throws is treated as a lane that produced no result, so one broken
 * extractor cannot abort a race another lane would win.
 *
 * [onResult] runs on the caller's coroutine, never concurrently with itself. That
 * matters when it touches shared, non-thread-safe state — the signature solver in
 * particular.
 */
internal suspend fun <T> raceCompletions(
    blocks: List<suspend () -> T>,
    scope: CoroutineScope? = null,
    onResult: suspend (T) -> Boolean
) {
    if (blocks.isEmpty()) return
    if (blocks.size == 1) {
        onResult(blocks.single().invoke())
        return
    }

    val raceScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val remaining = AtomicInteger(blocks.size)
    val results = Channel<T>(Channel.UNLIMITED)

    val jobs = blocks.map { block ->
        raceScope.launch {
            try {
                results.trySend(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A lane that fails is a lane that produced no result.
            } finally {
                // The channel closes only after every lane has either sent or
                // failed, so the consumer never waits for a result that can no
                // longer arrive.
                if (remaining.decrementAndGet() == 0) results.close()
            }
        }
    }

    try {
        repeat(blocks.size) {
            // Drains buffered results first, then returns null once every lane
            // has either produced a result or failed.
            val result = results.receiveCatching().getOrNull() ?: return
            if (onResult(result)) return
        }
    } finally {
        results.close()
        jobs.forEach { it.cancel() }
        if (scope == null) raceScope.cancel()
    }
}
