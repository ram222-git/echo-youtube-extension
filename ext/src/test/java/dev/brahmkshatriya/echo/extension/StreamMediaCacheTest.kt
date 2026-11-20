package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.extension.streaming.StreamMediaCache
import dev.brahmkshatriya.echo.extension.streaming.YouTubeSignatureDeobfuscator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class StreamMediaCacheTest {

    private fun media(): Streamable.Media = Streamable.Media.Server(emptyList(), merged = false)

    @Test
    fun testEntriesExpireAfterTtl() {
        var now = 1_000L
        val cache = StreamMediaCache(ttlMs = 500L, clock = { now })
        cache.put("song", media())

        assertNotNull(cache.get("song"))
        now += 499
        assertNotNull(cache.get("song"))
        now += 2
        assertNull(cache.get("song"))
    }

    @Test
    fun testLeastRecentlyUsedEntryIsEvicted() {
        val cache = StreamMediaCache(maxEntries = 2)
        val first = media()
        val second = media()
        val third = media()

        cache.put("a", first)
        cache.put("b", second)
        // Touch "a" so "b" becomes the least recently used entry.
        assertSame(first, cache.get("a"))
        cache.put("c", third)

        assertSame(first, cache.get("a"))
        assertNull(cache.get("b"))
        assertSame(third, cache.get("c"))
    }

    @Test
    fun testConcurrentRequestsResolveOnlyOnce() = runTest {
        val cache = StreamMediaCache()
        val calls = AtomicInteger()
        val resolved = media()

        val results = (1..5).map {
            async {
                cache.getOrResolve("same-key") {
                    calls.incrementAndGet()
                    delay(100)
                    resolved
                }
            }
        }.awaitAll()

        assertEquals(1, calls.get())
        results.forEach { assertSame(resolved, it) }
        assertSame(resolved, cache.get("same-key"))
    }

    @Test
    fun testFailedResolutionIsNotCached() = runTest {
        val cache = StreamMediaCache()
        val resolved = media()

        val failure = runCatching {
            cache.getOrResolve("retry") { throw IllegalStateException("boom") }
        }
        assertEquals(true, failure.isFailure)
        assertNull(cache.get("retry"))

        assertSame(resolved, cache.getOrResolve("retry") { resolved })
        assertSame(resolved, cache.get("retry"))
    }

    @Test
    fun testInvalidateByVideoPrefix() {
        val cache = StreamMediaCache()
        cache.put("video|audio|0|auto", media())
        cache.put("video|video|0|auto", media())
        cache.put("other|audio|0|auto", media())

        cache.invalidate("video|audio|")

        assertNull(cache.get("video|audio|0|auto"))
        assertNotNull(cache.get("video|video|0|auto"))
        assertNotNull(cache.get("other|audio|0|auto"))
    }

    @Test
    fun testDirectUrlWithoutSignatureOrNIsReturnedUnchanged() {
        val url = "https://rr1---sn-example.googlevideo.com/videoplayback?expire=1&itag=251"
        assertEquals(url, YouTubeSignatureDeobfuscator.deobfuscateStreamUrl("video", url, null))
    }

    @Test
    fun testStorePersistsEntriesForTheNextInstance() {
        val file = java.io.File(
            java.nio.file.Files.createTempDirectory("stream-cache-test").toFile(),
            "stream-cache.json"
        )
        val store = dev.brahmkshatriya.echo.extension.streaming.FileStreamCacheStore(file)
        var now = 1_000L
        val cache = StreamMediaCache(ttlMs = 500L, clock = { now }, store = store, persistDebounceMs = 0)
        val resolved = media()
        cache.put("v1|audio|0|auto|off:anon", resolved)

        // The persist is debounced on a daemon thread; poll briefly for the file.
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("cache file was not persisted", file.isFile)

        // A fresh cache (app restart) within the expiry window serves the entry.
        // Equality, not identity: the entry went through a JSON round-trip.
        val reloaded = StreamMediaCache(ttlMs = 500L, clock = { now }, store = store)
        assertEquals(resolved, reloaded.get("v1|audio|0|auto|off:anon"))

        // Past expiry the entry is dropped, never revived.
        now += 600
        assertNull(reloaded.get("v1|audio|0|auto|off:anon"))
        file.delete()
    }

    @Test
    fun testClearRemovesThePersistedStore() {
        val file = java.io.File(
            java.nio.file.Files.createTempDirectory("stream-cache-test").toFile(),
            "stream-cache.json"
        )
        val store = dev.brahmkshatriya.echo.extension.streaming.FileStreamCacheStore(file)
        val cache = StreamMediaCache(clock = { 1_000L }, store = store, persistDebounceMs = 0)
        cache.put("v1|audio|0|auto|off:anon", media())
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(20)

        cache.clear()

        assertNull(cache.get("v1|audio|0|auto|off:anon"))
        assertTrue(
            "store entries survived clear()",
            store.load().none { it.key == "v1|audio|0|auto|off:anon" }
        )
        file.delete()
    }
}
