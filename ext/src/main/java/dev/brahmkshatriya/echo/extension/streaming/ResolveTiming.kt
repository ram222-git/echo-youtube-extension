package dev.brahmkshatriya.echo.extension.streaming

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-resolve stopwatch used by [YouTubeStreamResolver]'s one-line summary log.
 *
 * Lanes run on different threads, so the counters are atomics. [lanes] is only
 * touched from the thread that started the resolve.
 */
internal class ResolveTiming {
    private val playerMs = AtomicLong()
    private val extractMs = AtomicLong()
    private val probeMs = AtomicLong()
    private val visitorMs = AtomicLong()
    private val playerCalls = AtomicInteger()
    private val probeCalls = AtomicInteger()
    private val lanes = LinkedHashSet<String>()

    fun lane(name: String) {
        synchronized(lanes) { lanes.add(name) }
    }

    fun addPlayer(ms: Long) {
        playerMs.addAndGet(ms)
        playerCalls.incrementAndGet()
    }

    fun addExtract(ms: Long) = extractMs.addAndGet(ms)

    fun addProbe(ms: Long) {
        probeMs.addAndGet(ms)
        probeCalls.incrementAndGet()
    }

    fun addVisitor(ms: Long) = visitorMs.addAndGet(ms)

    fun summary(): String {
        val laneList = synchronized(lanes) { lanes.toList() }
        return buildString {
            append("lanes=").append(laneList.joinToString(",").ifEmpty { "none" })
            append(" player=").append(playerMs.get()).append("ms/").append(playerCalls.get())
            append(" extract=").append(extractMs.get()).append("ms")
            append(" probe=").append(probeMs.get()).append("ms/").append(probeCalls.get())
            append(" visitor=").append(visitorMs.get()).append("ms")
        }
    }
}
