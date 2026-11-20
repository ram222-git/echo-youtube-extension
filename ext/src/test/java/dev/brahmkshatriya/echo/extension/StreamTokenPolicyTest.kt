package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.streaming.SpoofClient
import dev.brahmkshatriya.echo.extension.streaming.normalizedPoTokenBaseUrl
import dev.brahmkshatriya.echo.extension.streaming.requiresSignatureTimestamp
import dev.brahmkshatriya.echo.extension.streaming.requiresStreamToken
import dev.brahmkshatriya.echo.extension.streaming.withPoToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTokenPolicyTest {

    private val streamUrl =
        "https://rr1---sn-abc.googlevideo.com/videoplayback?itag=251&sig=abc&expire=123"

    @Test
    fun `appends pot without dropping existing parameters`() {
        val result = streamUrl.withPoToken("TOKEN").toHttpUrl()

        assertEquals("TOKEN", result.queryParameter("pot"))
        assertEquals("251", result.queryParameter("itag"))
        assertEquals("abc", result.queryParameter("sig"))
    }

    @Test
    fun `replaces an existing pot instead of duplicating it`() {
        val url = "$streamUrl&pot=old".toHttpUrl()
        assertEquals("old", url.queryParameter("pot"))

        val result = streamUrl.withPoToken("TOKEN").withPoToken("TOKEN")
        val parsed = result.toHttpUrl()

        assertEquals("TOKEN", parsed.queryParameter("pot"))
        // HttpUrl keeps a single value per query parameter unless addQueryParameter is used.
        assertEquals(1, parsed.queryParameterValues("pot").size)
    }

    @Test
    fun `leaves non-googlevideo urls untouched`() {
        val url = "https://music.youtube.com/watch?v=abc"
        assertEquals(url, url.withPoToken("TOKEN"))
    }

    @Test
    fun `does nothing without a token`() {
        assertEquals(streamUrl, streamUrl.withPoToken(null))
        assertEquals(streamUrl, streamUrl.withPoToken(""))
        assertEquals(streamUrl, streamUrl.withPoToken("   "))
    }

    @Test
    fun `token free clients never require a token`() {
        assertFalse(SpoofClient.VISIONOS.requiresStreamToken)
        assertFalse(SpoofClient.TV.requiresStreamToken)
        assertFalse(SpoofClient.TV_CURRENT.requiresStreamToken)
        assertFalse(SpoofClient.TV_DOWNGRADED.requiresStreamToken)
        assertFalse(SpoofClient.IOS.requiresStreamToken)
    }

    @Test
    fun `normalises a pasted provider url`() {
        assertEquals("http://192.168.1.10:4416", "  http://192.168.1.10:4416/  ".normalizedPoTokenBaseUrl())
        assertEquals("http://192.168.1.10:4416", "192.168.1.10:4416".normalizedPoTokenBaseUrl())
        assertEquals("http://192.168.1.10:4416", "http://192.168.1.10:4416/get_pot".normalizedPoTokenBaseUrl())
        assertEquals("https://pot.example.com", "https://pot.example.com/get_pot/".normalizedPoTokenBaseUrl())
        assertEquals(null, "   ".normalizedPoTokenBaseUrl())
    }

    @Test
    fun `web clients require a token`() {
        assertTrue(SpoofClient.WEB_REMIX.requiresStreamToken)
        assertTrue(SpoofClient.MWEB.requiresStreamToken)
    }

    @Test
    fun `mobile and tv clients skip the signature timestamp`() {
        assertFalse(SpoofClient.VISIONOS.requiresSignatureTimestamp)
        assertFalse(SpoofClient.IOS.requiresSignatureTimestamp)
        assertFalse(SpoofClient.TV.requiresSignatureTimestamp)
        assertFalse(SpoofClient.TV_CURRENT.requiresSignatureTimestamp)
        assertFalse(SpoofClient.TV_DOWNGRADED.requiresSignatureTimestamp)
    }

    @Test
    fun `html5 clients require the signature timestamp`() {
        assertTrue(SpoofClient.WEB_REMIX.requiresSignatureTimestamp)
        assertTrue(SpoofClient.MWEB.requiresSignatureTimestamp)
        assertTrue(SpoofClient.WEB_EMBEDDED.requiresSignatureTimestamp)
    }
}
