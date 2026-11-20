package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.utils.SingleFlight
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {

    @Test
    fun `concurrent callers share one execution`() = runTest {
        val flights = SingleFlight<String>()
        val executions = AtomicInteger()
        val results = (1..10).map {
            async {
                flights.getOrStart("page") {
                    executions.incrementAndGet()
                    delay(100)
                    "loaded"
                }
            }
        }.awaitAll()

        assertEquals(1, executions.get())
        results.forEach { assertEquals("loaded", it) }
    }

    @Test
    fun `completed results serve the ttl and expire after it`() = runTest {
        var now = 1_000L
        val flights = SingleFlight<String>(ttlMs = 500, clock = { now })
        val executions = AtomicInteger()

        flights.getOrStart("page") { executions.incrementAndGet(); "first" }
        flights.getOrStart("page") { executions.incrementAndGet(); "second" }
        assertEquals(1, executions.get())

        now += 501
        assertEquals("third", flights.getOrStart("page") { executions.incrementAndGet(); "third" })
        assertEquals(2, executions.get())
    }

    @Test
    fun `a failed load propagates to joiners and is not cached`() = runTest {
        val flights = SingleFlight<String>()
        val executions = AtomicInteger()

        val joiners = (1..5).map {
            async {
                runCatching {
                    flights.getOrStart("failing") {
                        executions.incrementAndGet()
                        delay(50)
                        throw IllegalStateException("boom")
                    }
                }
            }
        }
        val failures = joiners.awaitAll()
        // One execution served every joiner with the same failure…
        assertEquals(1, executions.get())
        failures.forEach { result ->
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

        // …and the next caller starts a fresh load.
        assertEquals("ok", flights.getOrStart("failing") { executions.incrementAndGet(); "ok" })
        assertEquals(2, executions.get())
    }

    @Test
    fun `different keys run independently`() = runTest {
        val flights = SingleFlight<String>()
        val executions = AtomicInteger()

        (1..2).map { n ->
            async { flights.getOrStart("key-$n") { executions.incrementAndGet(); "v$n" } }
        }.awaitAll()

        assertEquals(2, executions.get())
    }
}
