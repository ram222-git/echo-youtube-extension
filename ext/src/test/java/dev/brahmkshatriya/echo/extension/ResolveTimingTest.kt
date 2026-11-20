package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.streaming.ResolveTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveTimingTest {

    @Test
    fun `summary reports lanes in insertion order and accumulated times`() {
        val timing = ResolveTiming()
        timing.lane("visionOS")
        timing.lane("TV")
        timing.lane("visionOS") // deduplicated

        timing.addPlayer(120)
        timing.addPlayer(80)
        timing.addExtract(15)
        timing.addProbe(40)
        timing.addProbe(20)
        timing.addVisitor(5)

        val summary = timing.summary()

        assertTrue(summary.contains("lanes=visionOS,TV"))
        assertTrue(summary.contains("player=200ms/2"))
        assertTrue(summary.contains("extract=15ms"))
        assertTrue(summary.contains("probe=60ms/2"))
        assertTrue(summary.contains("visitor=5ms"))
    }

    @Test
    fun `empty summary is explicit instead of blank`() {
        val summary = ResolveTiming().summary()

        assertEquals(
            "lanes=none player=0ms/0 extract=0ms probe=0ms/0 visitor=0ms",
            summary
        )
    }
}
