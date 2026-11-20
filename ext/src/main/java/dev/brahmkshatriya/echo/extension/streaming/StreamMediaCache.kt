package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * In-memory cache for resolved [Streamable.Media] with a TTL and an LRU bound.
 *
 * Two playbacks of the same track (or a prefetch racing the actual play request)
 * must not repeat the whole extraction cascade, so [getOrResolve] also performs
 * single-flight resolution per key.
 *
 * When a [StreamCacheStore] is supplied the current LRU snapshot is persisted
 * (debounced, on a daemon thread) and re-loaded lazily on first access, so the
 * **first tap after an app restart** can be a cache hit within the URL validity
 * window instead of a fresh player request. Entries are never revived past
 * their expiry, and nothing sensitive (cookies, PO tokens) is stored — signed
 * stream URLs are IP-bound to this device and carry their own expiry.
 */
class StreamMediaCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val store: StreamCacheStore? = null,
    private val persistDebounceMs: Long = PERSIST_DEBOUNCE_MS
) {
    private data class Entry(val media: Streamable.Media, val expiresAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }
    private val entryLock = Any()
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var loadedFromStore = store == null
    private val persistExecutor: ScheduledExecutorService? =
        if (store == null) null
        else Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "stream-cache-persist").apply { isDaemon = true }
        }

    @Volatile
    private var persistFuture: ScheduledFuture<*>? = null

    private fun loadFromStoreIfNeeded() {
        if (loadedFromStore) return
        val stored = store?.load().orEmpty()
        synchronized(entryLock) {
            // Newest entries win; anything already expired is dropped, never revived.
            stored.filter { it.expiresAt > clock() }
                .sortedBy { it.expiresAt }
                .forEach { entries[it.key] = Entry(it.media, it.expiresAt) }
            trimToBound()
        }
        loadedFromStore = true
    }

    private fun trimToBound() {
        while (entries.size > maxEntries) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }

    /** Schedules a debounced, best-effort persist of the current snapshot. */
    private fun schedulePersist() {
        val snapshot = synchronized(entryLock) {
            entries.entries
                .filter { it.value.expiresAt > clock() }
                .map { StoredStream(it.key, it.value.expiresAt, it.value.media) }
        }
        val executor = persistExecutor ?: return
        persistFuture?.cancel(false)
        persistFuture = executor.schedule({
            store?.save(snapshot)
        }, persistDebounceMs, TimeUnit.MILLISECONDS)
    }

    fun get(key: String): Streamable.Media? {
        loadFromStoreIfNeeded()
        return synchronized(entryLock) {
            val entry = entries[key] ?: return null
            if (entry.expiresAt <= clock()) {
                entries.remove(key)
                null
            } else {
                entry.media
            }
        }
    }

    fun put(key: String, media: Streamable.Media, ttl: Long = ttlMs): Unit {
        loadFromStoreIfNeeded()
        synchronized(entryLock) {
            entries[key] = Entry(media, clock() + ttl)
            trimToBound()
        }
        schedulePersist()
    }

    /** Drops every entry whose key starts with [prefix] (e.g. `"$videoId|"`). */
    fun invalidate(prefix: String) {
        loadFromStoreIfNeeded()
        synchronized(entryLock) {
            val iterator = entries.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().startsWith(prefix)) iterator.remove()
            }
        }
        schedulePersist()
    }

    fun clear() {
        loadFromStoreIfNeeded()
        synchronized(entryLock) { entries.clear() }
        store?.clear()
    }

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
        private const val PERSIST_DEBOUNCE_MS = 500L
    }
}
