package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Speculative, best-effort audio resolution for the first few tracks of a list.
 *
 * Echo plays a list item by calling `loadStreamableMedia` directly, which means
 * the player request only starts on tap. Warming the [StreamMediaCache] as soon
 * as the list is rendered turns that tap into a cache hit.
 *
 * Everything here is bounded and disposable:
 *  - at most [maxPerList] tracks per loaded page,
 *  - at most [maxConcurrent] player requests in flight,
 *  - the same video is never prefetched twice within [dedupeTtlMs],
 *  - failures are silent (the real play request retries them).
 */
class StreamPrefetcher(
    private val resolveStreamable: suspend (Streamable?, String) -> Unit,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean = { true },
    private val maxPerList: Int = DEFAULT_MAX_PER_LIST,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val dedupeTtlMs: Long = DEDUPE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Target(val videoId: String, val streamable: Streamable?)

    private val permits = Semaphore(maxConcurrent)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * The most recently served ordered track lists (albums, playlists, radios,
     * track shelves). The extension never sees the host player's queue, but it
     * *served* these lists — so when the host reports the currently playing track
     * via [onCurrentTrack], the successor in the served list is a good prediction
     * of what a skip or an auto-advance will need next.
     */
    private val recentLists = ArrayDeque<List<Track>>()

    /** videoId -> reservation expiry. LRU-bounded so long sessions cannot grow it. */
    private val reserved = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_RESERVATIONS
    }

    fun prefetchTrack(track: Track) = start(sequenceOf(track))

    fun prefetchTracks(tracks: List<Track>) {
        noteList(tracks)
        start(tracks.asSequence())
    }

    /**
     * Loads the first page of [paged] (the same call the host makes right after,
     * so the page cache is shared) and prefetches the tracks it contains.
     */
    fun prefetchPaged(paged: PagedData<Track>) {
        if (!enabled()) return
        scope.launch {
            runCatching { paged.loadPage(null) }
                .onSuccess { prefetchTracks(it.data) }
                .onFailure { println("StreamPrefetcher: first page load failed: ${it.message}") }
        }
    }

    /**
     * Prefetches the first tracks found in [shelves]. Track lists, track items and
     * single track media are considered; categories and albums are not (nothing to
     * play yet).
     */
    fun prefetchShelves(shelves: List<Shelf>) {
        shelves.forEach { shelf -> (shelf.tracks()).takeIf { it.isNotEmpty() }?.let(::noteList) }
        start(shelves.asSequence().flatMap { it.tracks().asSequence() })
    }

    /**
     * Called by the host's tracker when a track starts playing. Looks the track up
     * in the most recently served lists and prefetches the following tracks, so a
     * manual skip — or an auto-advance on a host without player-side preloading —
     * finds the stream already resolved.
     *
     * Rapid skips are safe: claims are per-video, duplicated work collapses into
     * the resolver's single-flight cache, and every prefetch is bounded.
     */
    fun onCurrentTrack(track: Track?) {
        if (!enabled()) return
        val id = track?.id?.takeIf { it.isNotBlank() } ?: return
        val list = synchronized(recentLists) {
            recentLists.firstOrNull { candidates -> candidates.any { it.id == id } }
        } ?: return
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        start(list.asSequence().drop(index + 1).take(SUCCESSOR_COUNT))
    }

    /** Keeps a bounded, most-recent-first memory of served ordered lists. */
    private fun noteList(tracks: List<Track>) {
        if (tracks.size < SUCCESSOR_COUNT + 1) return
        synchronized(recentLists) {
            recentLists.removeAll { existing -> existing == tracks }
            recentLists.addFirst(tracks.take(MAX_LIST_LENGTH))
            while (recentLists.size > MAX_RECENT_LISTS) recentLists.removeLast()
        }
    }

    private fun start(candidates: Sequence<Track>) {
        if (!enabled()) return

        val targets = candidates
            .mapNotNull(::audioTarget)
            .filter { claim(it.videoId) }
            .take(maxPerList)
            .toList()
        if (targets.isEmpty()) return

        scope.launch {
            coroutineScope {
                targets.map { target ->
                    async {
                        permits.withPermit {
                            val resolved = runCatching {
                                resolveStreamable(target.streamable, target.videoId)
                            }
                            resolved
                                .onSuccess {
                                    println("StreamPrefetcher: prefetched ${target.videoId}")
                                }
                                .onFailure {
                                    println("StreamPrefetcher: prefetch failed for ${target.videoId}: ${it.message}")
                                }
                            release(target.videoId, resolved.isSuccess)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * Resolves the same streamable Echo would ask for: the track's audio server
     * streamable when present (same cache key as the real request), otherwise a
     * plain audio request by video id.
     */
    private fun audioTarget(track: Track): Target? {
        val streamable = track.streamables.firstOrNull {
            it.type == Streamable.MediaType.Server && it.extras["type"] != "video"
        }
        val videoId = streamable?.extras?.get("videoId")
            ?: track.extras["videoId"]
            ?: track.id.takeIf { it.isNotBlank() }
            ?: return null
        return Target(videoId, streamable)
    }

    private fun claim(videoId: String): Boolean {
        synchronized(reserved) {
            val expiry = reserved[videoId]
            if (expiry != null && expiry > clock()) return false
        }
        if (!inFlight.add(videoId)) return false
        synchronized(reserved) { reserved[videoId] = clock() + dedupeTtlMs }
        return true
    }

    private fun release(videoId: String, succeeded: Boolean) {
        inFlight.remove(videoId)
        if (!succeeded) {
            // Allow a later attempt instead of suppressing the video for the whole TTL.
            synchronized(reserved) { reserved.remove(videoId) }
        }
    }

    companion object {
        const val DEFAULT_MAX_PER_LIST = 3
        const val DEFAULT_MAX_CONCURRENT = 2
        const val DEDUPE_TTL_MS = 10 * 60 * 1000L
        const val MAX_RESERVATIONS = 256

        /** How many tracks after the current one are warmed on a track change. */
        const val SUCCESSOR_COUNT = 2
        const val MAX_RECENT_LISTS = 4
        const val MAX_LIST_LENGTH = 100
    }
}

private fun Shelf.tracks(): List<Track> = when (this) {
    is Shelf.Lists.Tracks -> list
    is Shelf.Lists.Items -> list.filterIsInstance<Track>()
    is Shelf.Item -> listOfNotNull(media as? Track)
    else -> emptyList()
}
