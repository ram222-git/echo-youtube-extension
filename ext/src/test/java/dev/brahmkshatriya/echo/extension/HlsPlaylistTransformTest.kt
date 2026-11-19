package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.streaming.sortHlsMasterPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the HLS master playlist rewrite applied before handing a manifest to
 * Echo's player: one muxed variant per height (highest bandwidth), descending,
 * without the audio renditions that made the player's selected-track highlight
 * stick to a low variant while a higher one played.
 */
class HlsPlaylistTransformTest {

    private fun sort(content: String) = sortHlsMasterPlaylist(content)

    @Test
    fun `keeps one variant per height, highest bandwidth, descending`() {
        val input = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4688074,RESOLUTION=1920x1080,FRAME-RATE=25,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="234"
            1080_a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1248435,RESOLUTION=1280x720,FRAME-RATE=25,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="234"
            720_a.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1763840,RESOLUTION=1920x1080,FRAME-RATE=25,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="234"
            1080_b.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=78539,RESOLUTION=256x144,FRAME-RATE=13,CODECS="avc1.4d400c,mp4a.40.5",AUDIO="233"
            144_13fps.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=168130,RESOLUTION=256x144,FRAME-RATE=25,CODECS="avc1.4d400c,mp4a.40.5",AUDIO="233"
            144_25fps.m3u8
        """.trimIndent()

        val out = sort(input)
        val infLines = out.lines().filter { it.startsWith("#EXT-X-STREAM-INF") }
        val urls = out.lines().filter { it.isNotEmpty() && !it.startsWith("#") }

        // 3 distinct heights: 1080, 720, 144 — the 13fps oddball loses to the
        // higher-bandwidth 25fps 144p variant of the same height, and the
        // higher-bandwidth 1080p rendition (4688 kbps) wins within its height.
        assertEquals(listOf("1080_a.m3u8", "720_a.m3u8", "144_25fps.m3u8"), urls)
        assertEquals(3, infLines.size)
        // Strictly descending by height.
        val heights = infLines.map { Regex("RESOLUTION=\\d+x(\\d+)").find(it)!!.groupValues[1].toInt() }
        assertEquals(heights, heights.sortedDescending())
    }

    @Test
    fun `keeps audio renditions — the variant segments are video-only fmp4`() {
        val input = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="234",NAME="AAC",CHANNELS="2",URI="a234.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="vtt",NAME="en",URI="subs.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1248435,RESOLUTION=1280x720,AUDIO="234",SUBTITLES="vtt"
            720.m3u8
        """.trimIndent()

        val out = sort(input)

        // Removing the renditions breaks playback ("Unable to bind a sample queue
        // to TrackGroup (audio/mp4a-latm)") — they carry the audio samples.
        assertTrue(out.contains("TYPE=AUDIO"))
        assertTrue(out.contains("AUDIO=\"234\""))
        // Subtitle renditions are untouched.
        assertTrue(out.contains("TYPE=SUBTITLES"))
        assertTrue(out.contains("SUBTITLES=\"vtt\""))
        assertTrue(out.contains("720.m3u8"))
    }

    @Test
    fun `header tags other than audio renditions survive`() {
        val input = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=256x144,CODECS="avc1.4d400c,mp4a.40.5"
            144.m3u8
        """.trimIndent()

        val out = sort(input)
        assertTrue(out.contains("#EXTM3U"))
        assertTrue(out.contains("#EXT-X-VERSION:6"))
    }
}
