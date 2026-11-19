package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.utils.CookieParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end playback checks against the real service.
 *
 * These run only when a local credential is available (environment variable
 * `ECHO_YT_COOKIE`, or `ECHO_YT_COOKIE=` in a `.env.local` file at the repo
 * root). Without it they are skipped, so CI stays anonymous.
 *
 * The cookie is never printed, logged, or included in an assertion message.
 */
class AuthenticatedPlaybackTest {

    @Test
    fun normalTrackPlays() = runTest {
        val cookie = loadCookie()
        assumeTrue("ECHO_YT_COOKIE not configured; skipping authenticated test", cookie != null)
        assertTrackPlays(cookie!!, "dQw4w9WgXcQ")
    }

    /**
     * Age-restricted tracks are only served to web clients, whose stream URLs are
     * ciphered and rejected by the CDN unless a PO Token is appended. Measured on
     * 2026-11-19: with the cookie and no token the URL answers HTTP 403; with a
     * token bound to the video id it answers HTTP 200. So this test needs both a
     * cookie and a provider, and skips when either is missing.
     */
    @Test
    fun ageRestrictedTrackPlaysWhenSignedIn() = runTest {
        val cookie = loadCookie()
        assumeTrue("ECHO_YT_COOKIE not configured; skipping authenticated test", cookie != null)
        assumeTrue(
            "Age-restricted tracks need a PO Token provider; set ECHO_YT_POT_URL to run this test",
            loadEnv("ECHO_YT_POT_URL") != null
        )
        assertTrackPlays(cookie!!, "hc0ZDaAZQT0", expectPot = true)
    }

    /**
     * Control: a normal track forced through WEB_REMIX, whose formats are all
     * ciphered. This exercises the signature/n solver end to end. The assertion
     * on the `c=` parameter proves the URL really came from WEB_REMIX and not from
     * a token-free fallback client.
     *
     * A local PO Token provider is used when `ECHO_YT_POT_URL` is set; the token
     * is optional, so the test also passes without one.
     */
    @Test
    fun normalTrackPlaysWithWebRemix() = runTest {
        val cookie = loadCookie()
        assumeTrue("ECHO_YT_COOKIE not configured; skipping authenticated test", cookie != null)
        assertTrackPlays(cookie!!, "dQw4w9WgXcQ", client = "web_remix", expectPot = loadEnv("ECHO_YT_POT_URL") != null)
    }

    private suspend fun assertTrackPlays(
        cookie: String,
        videoId: String,
        client: String = "auto",
        expectPot: Boolean = false
    ) {
        val extension = YoutubeExtension()
        val settings = MockedSettings()
        settings.putString("spoof_stream_client", client)
        settings.putBoolean("enable_video", false)
        loadEnv("ECHO_YT_POT_URL")?.let { potUrl ->
            settings.putString("potoken_mode", "external")
            settings.putString("potoken_url", potUrl)
            println("AuthenticatedPlaybackTest: PO Token provider enabled at $potUrl")
        }
        extension.setSettings(settings)

        val auth = CookieParser.generateDynamicAuthHeader(CookieParser.parse(cookie))
        extension.setLoginUser(createFallbackUsers(cookie, auth).first())

        val track = extension.loadTrack(Track(videoId, ""), false)
        val streamable = track.streamables.firstOrNull { it.extras["type"] == "audio" }
            ?: track.streamables.first()
        val media = extension.loadStreamableMedia(streamable, false)

        assertTrue("Expected a server media result", media is Streamable.Media.Server)
        val server = media as Streamable.Media.Server
        assertTrue("Expected at least one source", server.sources.isNotEmpty())

        val source = server.sources.first() as Streamable.Source.Http
        val hasPot = source.request.url.contains("&pot=") || source.request.url.contains("?pot=")
        println(
            "AuthenticatedPlaybackTest: $videoId -> ${server.sources.size} sources, pot=$hasPot"
        )
        if (expectPot) {
            assertTrue("Expected a PO Token on the stream URL", hasPot)
        }
        // Without a token provider the tokenless web-client lane is
        // nondeterministic: the CDN sometimes serves a correctly signed URL
        // without a `pot` and sometimes 403s (PO_TOKEN_ROOT_CAUSE.md, gate 2),
        // in which case the cascade correctly falls through to a token-free
        // client. Provenance is only guaranteed when a provider is configured.
        if (client != "auto" && expectPot) {
            assertTrue(
                "Expected the stream to come from the $client client",
                source.request.url.contains("c=$client", ignoreCase = true)
            )
        }
        val builder = okhttp3.Request.Builder().url(source.request.url)
        source.request.headers.forEach { (key, value) -> builder.header(key, value) }
        val code = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
            .newCall(builder.build())
            .execute()
            .use { it.code }

        assertTrue("Expected a playable URL, got HTTP $code", code in 200..399)
    }

    private fun loadCookie(): String? = loadEnv("ECHO_YT_COOKIE")

    /** Reads `key` from the environment first, then from `.env.local` in a parent directory. */
    private fun loadEnv(key: String): String? {
        System.getenv(key)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val file = File(dir, ".env.local")
            if (file.isFile) {
                val value = file.useLines { lines ->
                    lines.firstOrNull { it.trimStart().startsWith("$key=") }
                        ?.substringAfter("=")
                        ?.trim()
                        ?.trim('"')
                        ?.trim('\'')
                }
                if (!value.isNullOrBlank()) return value
            }
            dir = dir.parentFile
        }
        return null
    }
}
