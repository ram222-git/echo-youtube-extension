package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.extension.streaming.StreamPrefetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class StreamPrefetcherTest {

    private fun audioStreamable(videoId: String) = Streamable.server(
        id = "yt_audio_$videoId",
        quality = 0,
        title = "YouTube",
        extras = mapOf("videoId" to videoId, "type" to "audio")
    )

    private fun audioTrack(videoId: String) = Track(
        id = videoId,
        title = videoId,
        extras = mapOf("videoId" to videoId),
        streamables = listOf(audioStreamable(videoId))
    )

    private class Recorder {
        val calls = mutableListOf<Pair<String, Streamable?>>()
        fun resolved(videoId: String): Int = calls.count { it.first == videoId }
    }

    @Test
    fun `prefetches at most the configured number of tracks`() = runTest {
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this,
            maxPerList = 2
        )

        prefetcher.prefetchTracks((1..5).map { audioTrack("v$it") })
        advanceUntilIdle()

        assertEquals(2, recorder.calls.size)
        assertEquals(setOf("v1", "v2"), recorder.calls.map { it.first }.toSet())
    }

    @Test
    fun `the same video is never prefetched twice within the dedupe window`() = runTest {
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this
        )

        prefetcher.prefetchTracks(listOf(audioTrack("v1")))
        advanceUntilIdle()
        prefetcher.prefetchTracks(listOf(audioTrack("v1")))
        advanceUntilIdle()

        assertEquals(1, recorder.resolved("v1"))
    }

    @Test
    fun `a video can be prefetched again after the dedupe window`() = runTest {
        var now = 1_000L
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this,
            dedupeTtlMs = 500L,
            clock = { now }
        )

        prefetcher.prefetchTrack(audioTrack("v1"))
        advanceUntilIdle()
        now += 501
        prefetcher.prefetchTrack(audioTrack("v1"))
        advanceUntilIdle()

        assertEquals(2, recorder.resolved("v1"))
    }

    @Test
    fun `a failed prefetch can be retried`() = runTest {
        val attempts = AtomicInteger()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { _, _ ->
                if (attempts.incrementAndGet() == 1) throw IllegalStateException("boom")
            },
            scope = this
        )

        prefetcher.prefetchTrack(audioTrack("v1"))
        advanceUntilIdle()
        prefetcher.prefetchTrack(audioTrack("v1"))
        advanceUntilIdle()

        assertEquals(2, attempts.get())
    }

    @Test
    fun `video streamables are skipped in favour of the audio one`() = runTest {
        val video = Streamable.server(
            id = "yt_video_v1",
            quality = 0,
            title = "YouTube Video",
            extras = mapOf("videoId" to "v1", "type" to "video")
        )
        val audio = audioStreamable("v1")
        val track = audioTrack("v1").copy(streamables = listOf(video, audio))

        var seen: Streamable? = null
        var seenId: String? = null
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId ->
                seen = streamable
                seenId = videoId
            },
            scope = this
        )

        prefetcher.prefetchTrack(track)
        advanceUntilIdle()

        assertSame(audio, seen)
        assertEquals("v1", seenId)
    }

    @Test
    fun `falls back to the track id when no streamables are present`() = runTest {
        var seen: Streamable? = audioStreamable("other")
        var seenId: String? = null
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId ->
                seen = streamable
                seenId = videoId
            },
            scope = this
        )

        prefetcher.prefetchTrack(Track(id = "v9", title = "v9"))
        advanceUntilIdle()

        assertNull(seen)
        assertEquals("v9", seenId)
    }

    @Test
    fun `blank tracks are ignored`() = runTest {
        val calls = AtomicInteger()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { _, _ -> calls.incrementAndGet() },
            scope = this
        )

        prefetcher.prefetchTrack(Track(id = "", title = ""))
        advanceUntilIdle()

        assertEquals(0, calls.get())
    }

    @Test
    fun `the concurrency cap is respected`() = runTest {
        val current = AtomicInteger()
        val peak = AtomicInteger()
        val completed = AtomicInteger()

        val prefetcher = StreamPrefetcher(
            resolveStreamable = { _, _ ->
                val running = current.incrementAndGet()
                peak.updateAndGet { maxOf(it, running) }
                delay(10)
                current.decrementAndGet()
                completed.incrementAndGet()
            },
            scope = this,
            maxPerList = 5,
            maxConcurrent = 2
        )

        prefetcher.prefetchTracks((1..5).map { audioTrack("v$it") })
        advanceUntilIdle()

        assertEquals(5, completed.get())
        assertEquals(2, peak.get())
    }

    @Test
    fun `disabled prefetching resolves nothing`() = runTest {
        val calls = AtomicInteger()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { _, _ -> calls.incrementAndGet() },
            scope = this,
            enabled = { false }
        )

        prefetcher.prefetchTracks(listOf(audioTrack("v1")))
        advanceUntilIdle()

        assertEquals(0, calls.get())
    }

    @Test
    fun `shelves contribute their tracks but not their categories`() = runTest {
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this
        )

        val shelves = listOf(
            Shelf.Lists.Tracks("tracks", "Songs", listOf(audioTrack("a1"), audioTrack("a2"))),
            Shelf.Lists.Items(
                "items",
                "More",
                listOf(audioTrack("i1"), audioTrack("i2"))
            ),
            Shelf.Category("category", "Rock")
        )

        prefetcher.prefetchShelves(shelves)
        advanceUntilIdle()

        assertEquals(3, recorder.calls.size)
        assertTrue(recorder.calls.map { it.first }.containsAll(listOf("a1", "a2", "i1")))
    }

    @Test
    fun `a single track shelf is prefetched`() = runTest {
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this
        )

        prefetcher.prefetchShelves(listOf(Shelf.Item(audioTrack("s1"))))
        advanceUntilIdle()

        assertEquals(listOf("s1"), recorder.calls.map { it.first })
    }

    @Test
    fun `prefetchPaged loads the first page once and prefetches its tracks`() = runTest {
        val loads = AtomicInteger()
        val paged = PagedData.Continuous<Track> {
            loads.incrementAndGet()
            Page(listOf(audioTrack("p1"), audioTrack("p2")), null)
        }
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this
        )

        prefetcher.prefetchPaged(paged)
        prefetcher.prefetchPaged(paged)
        advanceUntilIdle()

        assertEquals(1, loads.get())
        assertEquals(setOf("p1", "p2"), recorder.calls.map { it.first }.toSet())
    }

    @Test
    fun `prefetchPaged does not load the page when disabled`() = runTest {
        val loads = AtomicInteger()
        val paged = PagedData.Continuous<Track> {
            loads.incrementAndGet()
            Page(listOf(audioTrack("p1")), null)
        }
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { _, _ -> },
            scope = this,
            enabled = { false }
        )

        prefetcher.prefetchPaged(paged)
        advanceUntilIdle()

        assertEquals(0, loads.get())
    }

    @Test
    fun `onCurrentTrack prefetches the successors from the served list`() = runTest {
        var now = 1_000L
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this,
            maxPerList = 4,
            dedupeTtlMs = 600L,
            clock = { now }
        )
        prefetcher.prefetchTracks((1..5).map { audioTrack("l$it") })
        advanceUntilIdle()
        assertEquals(4, recorder.calls.size)

        // Later (dedupe window expired) the second track of the list starts: its
        // two successors are warmed even though the list prefetch claimed them before.
        now += 601
        prefetcher.onCurrentTrack(audioTrack("l2"))
        advanceUntilIdle()

        assertEquals(2, recorder.resolved("l3"))
        assertEquals(2, recorder.resolved("l4"))
        assertEquals(0, recorder.resolved("l5"))
    }

    @Test
    fun `onCurrentTrack ignores tracks that were never served in a list`() = runTest {
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this
        )
        prefetcher.prefetchTracks((1..4).map { audioTrack("l$it") })
        advanceUntilIdle()

        prefetcher.onCurrentTrack(audioTrack("unknown"))
        advanceUntilIdle()

        assertEquals(3, recorder.calls.size)
    }

    @Test
    fun `onCurrentTrack warms exactly SUCCESSOR_COUNT tracks and is deduped`() = runTest {
        var now = 1_000L
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this,
            maxPerList = 3,
            dedupeTtlMs = 600L,
            clock = { now }
        )
        prefetcher.prefetchTracks((1..10).map { audioTrack("l$it") })
        advanceUntilIdle()
        assertEquals(3, recorder.calls.size)

        // Successors l2/l3 are still claimed by the list prefetch: nothing new.
        prefetcher.onCurrentTrack(audioTrack("l1"))
        advanceUntilIdle()
        assertEquals(3, recorder.calls.size)

        // After the dedupe window a skip to nothing-yet-played re-claims: exactly
        // SUCCESSOR_COUNT successors are warmed.
        now += 601
        prefetcher.onCurrentTrack(audioTrack("l1"))
        advanceUntilIdle()

        assertEquals(2, recorder.resolved("l2"))
        assertEquals(2, recorder.resolved("l3"))
        assertEquals(0, recorder.resolved("l4"))
    }

    @Test
    fun `a track change finds its successors in the list that served it`() = runTest {
        var now = 1_000L
        val recorder = Recorder()
        val prefetcher = StreamPrefetcher(
            resolveStreamable = { streamable, videoId -> recorder.calls += videoId to streamable },
            scope = this,
            maxPerList = 4,
            dedupeTtlMs = 600L,
            clock = { now }
        )
        prefetcher.prefetchTracks(listOf(audioTrack("a1"), audioTrack("a2"), audioTrack("a3")))
        prefetcher.prefetchTracks(listOf(audioTrack("b1"), audioTrack("b2"), audioTrack("b3")))
        advanceUntilIdle()

        now += 601
        prefetcher.onCurrentTrack(audioTrack("a1"))
        advanceUntilIdle()

        assertEquals(2, recorder.resolved("a2"))
        assertEquals(2, recorder.resolved("a3"))
        assertEquals(1, recorder.resolved("b1"))
    }
}
