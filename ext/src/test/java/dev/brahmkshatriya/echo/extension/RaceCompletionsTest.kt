package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.streaming.raceCompletions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RaceCompletionsTest {

    @Test
    fun `stops at the first accepted result`() = runTest {
        val received = mutableListOf<String>()

        raceCompletions(
            blocks = listOf(
                { delay(50); "slow" },
                { delay(10); "fast" }
            ),
            scope = this,
            onResult = { received += it; it == "fast" }
        )

        assertEquals(listOf("fast"), received)
    }

    @Test
    fun `skips failures and accepts the first winner`() = runTest {
        val received = mutableListOf<Int>()

        raceCompletions(
            blocks = listOf(
                { delay(5); 1 },
                { delay(10); 2 },
                { delay(20); 3 }
            ),
            scope = this,
            onResult = { received += it; it == 3 }
        )

        assertEquals(listOf(1, 2, 3), received)
    }

    @Test
    fun `visits every result when none is accepted`() = runTest {
        val seen = mutableListOf<Int>()

        withTimeout(1_000) {
            raceCompletions(
                blocks = listOf({ 1 }, { 2 }, { 3 }),
                scope = this@runTest,
                onResult = { seen += it; false }
            )
        }

        assertEquals(listOf(1, 2, 3).sorted(), seen.sorted())
    }

    @Test
    fun `a throwing lane does not abort a race another lane can win`() = runTest {
        val received = mutableListOf<String>()
        val blocks: List<suspend () -> String> = listOf(
            { error("boom") },
            { delay(10); "ok" }
        )

        withTimeout(1_000) {
            raceCompletions(
                blocks = blocks,
                scope = this@runTest,
                onResult = { received += it; true }
            )
        }

        assertEquals(listOf("ok"), received)
    }

    @Test
    fun `a throwing lane does not prevent visiting the remaining results`() = runTest {
        val seen = mutableListOf<Int>()
        val blocks: List<suspend () -> Int> = listOf(
            { error("boom") },
            { delay(5); 1 },
            { delay(10); 2 }
        )

        withTimeout(1_000) {
            raceCompletions(
                blocks = blocks,
                scope = this@runTest,
                onResult = { seen += it; false }
            )
        }

        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun `every lane throwing completes without hanging`() = runTest {
        val calls = AtomicInteger()
        val blocks: List<suspend () -> Unit> = listOf(
            { error("one") },
            { error("two") }
        )

        withTimeout(1_000) {
            raceCompletions(
                blocks = blocks,
                scope = this@runTest,
                onResult = { calls.incrementAndGet(); true }
            )
        }

        assertEquals(0, calls.get())
    }

    @Test
    fun `onResult is never entered concurrently`() = runTest {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()

        raceCompletions(
            blocks = (1..6).map { index -> { delay(index.toLong()); index } },
            scope = this,
            onResult = {
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                delay(1)
                inFlight.decrementAndGet()
                false
            }
        )

        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun `an empty race does nothing`() = runTest {
        var called = false

        raceCompletions(blocks = emptyList<suspend () -> Int>(), scope = this) { called = true; true }

        assertTrue(!called)
    }

    @Test
    fun `a single block runs on the caller and its result is delivered`() = runTest {
        val received = mutableListOf<Int>()
        val blocks: List<suspend () -> Int> = listOf({ 7 })

        raceCompletions(blocks = blocks, scope = this) { received += it; true }

        assertEquals(listOf(7), received)
    }
}
