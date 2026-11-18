package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for resolved [Streamable.Media] with a TTL and an LRU bound.
 *
 * Two playbacks of the same track (or a prefetch racing the actual play request)
 * must not repeat the whole extraction cascade, so [getOrResolve] also performs
 * single-flight resolution per key.
 */
class StreamMediaCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val media: Streamable.Media, val expiresAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }
    private val entryLock = Any()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    fun get(key: String): Streamable.Media? = synchronized(entryLock) {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= clock()) {
            entries.remove(key)
            null
        } else {
            entry.media
        }
    }

    fun put(key: String, media: Streamable.Media, ttl: Long = ttlMs): Unit = synchronized(entryLock) {
        entries[key] = Entry(media, clock() + ttl)
    }

    /** Drops every entry whose key starts with [prefix] (e.g. `"$videoId|"`). */
    fun invalidate(prefix: String) = synchronized(entryLock) {
        val iterator = entries.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) iterator.remove()
        }
    }

    fun clear() = synchronized(entryLock) { entries.clear() }

    /**
     * Returns the cached media for [key], or runs [resolve] exactly once even when
     * several callers ask for the same key concurrently. Failed resolutions are
     * not cached, so a retry after an error always hits the network again.
     */
    suspend fun getOrResolve(key: String, resolve: suspend () -> Streamable.Media): Streamable.Media {
        get(key)?.let { return it }

        val keyLock = keyLocks.getOrPut(key) { Mutex() }
        return try {
            keyLock.withLock {
                get(key) ?: resolve().also { put(key, it) }
            }
        } finally {
            if (keyLocks.size > maxEntries * 2) {
                val iterator = keyLocks.entries.iterator()
                while (iterator.hasNext()) {
                    if (!iterator.next().value.isLocked) iterator.remove()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
        const val DEFAULT_TTL_MS = 25 * 60 * 1000L
    }
}
