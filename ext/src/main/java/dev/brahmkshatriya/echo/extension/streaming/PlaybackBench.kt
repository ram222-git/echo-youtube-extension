package dev.brahmkshatriya.echo.extension.streaming

import java.util.concurrent.ConcurrentHashMap

/**
 * Log-only benchmark marks for the play-tap → audible pipeline.
 *
 * One `[EchoBench]` line per event, all timestamps on a monotonic clock relative
 * to process start, so the five mission measurements (first play, replay, auto
 * next, manual skip, rapid skip) can be read straight from logcat next to the
 * host-side marks (`playTap`, `playerReady`, `firstAudibleOutput`) that share
 * the same process and the same monotonic origin.
 *
 * Only durations and cache outcomes are logged — never URLs, cookies or tokens.
 */
internal object PlaybackBench {

    /** Monotonic process-relative clock: matches the host's SystemClock-based marks. */
    private val originNanos = System.nanoTime()

    private val pendingStarts = ConcurrentHashMap<String, Long>()

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private fun rel(): Long = nowMs() - originNanos / 1_000_000

    /** An instant event, e.g. `playRequest v=abc`, `tokenReady kind=android minted=120ms`. */
    fun mark(label: String, detail: String? = null) {
        println("[EchoBench] t+${rel()}ms $label${detail?.let { " $it" } ?: ""}")
    }

    /** Starts a span for [key]; cheap enough to call speculatively. */
    fun spanStart(key: String) {
        pendingStarts[key] = nowMs()
    }

    /** Ends the span for [key] (no-op when never started). */
    fun spanEnd(key: String, label: String, detail: String? = null) {
        val started = pendingStarts.remove(key) ?: return
        println("[EchoBench] t+${rel()}ms $label took=${nowMs() - started}ms${detail?.let { " $it" } ?: ""}")
    }
}
