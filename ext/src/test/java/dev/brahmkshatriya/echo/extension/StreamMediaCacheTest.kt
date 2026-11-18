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
}
