package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.streaming.YouTubeSignatureDeobfuscator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * DIAGNOSTIC (temporary): determines which PO Token binding combination makes an
 * age-restricted, signed-in WEB_REMIX playback succeed. Runs one player request
 * per player-body variant, then probes the same deciphered stream URL with each
 * URL token variant. Requires: `.env.local` with ECHO_YT_COOKIE, and the local
 * bgutil-compatible minter on 127.0.0.1:4416 (scratch/pot-provider/server.mjs).
 * Credentials are never printed.
 */
class RestrictedBindingDiagnosticTest {

    private val videoId = "hc0ZDaAZQT0"
    private val potUrl = "http://127.0.0.1:4416"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun loadEnv(key: String): String? {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val file = java.io.File(dir, ".env.local")
            if (file.isFile) {
                val value = file.useLines { lines ->
                    lines.firstOrNull { it.trimStart().startsWith("$key=") }
                        ?.substringAfter("=")?.trim()?.trim('"')?.trim('\'')
                }
                if (!value.isNullOrBlank()) return value
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun mint(binding: String): String {
        val body = """{"content_binding":"$binding"}"""
            .toRequestBody("application/json".toMediaType())
        client.newCall(
            Request.Builder().url("$potUrl/get_pot").post(body)
                .header("Content-Type", "application/json").build()
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val token = json.parseToJsonElement(text).jsonObject["poToken"]?.jsonPrimitive?.content
                ?: error("mint failed for binding len=${binding.length}")
            return token
        }
    }

    private fun fetchVisitorData(cookie: String): String {
        val req = Request.Builder()
            .url("https://music.youtube.com/")
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            return Regex(""""visitorData"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                ?: error("no visitorData in music.youtube.com homepage")
        }
    }

    private fun playerRequest(cookie: String, visitorData: String, playerToken: String?): Pair<Int, String?> {
        val cookieMap = dev.brahmkshatriya.echo.extension.utils.CookieParser.parse(cookie)
        val auth = dev.brahmkshatriya.echo.extension.utils.CookieParser.generateDynamicAuthHeader(cookieMap, "https://music.youtube.com")
        val payload = buildString {
            append("{\"videoId\":\"$videoId\",\"contentCheckOk\":true,\"racyCheckOk\":true,")
            append("\"playbackContext\":{\"contentPlaybackContext\":{\"html5Preference\":\"HTML5_PREF_WANTS\",\"signatureTimestamp\":20773}},")
            if (playerToken != null) {
                append("\"serviceIntegrityDimensions\":{\"poToken\":\"$playerToken\"},")
            }
            append("\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20260928.13.00\",\"hl\":\"en\",\"gl\":\"US\",\"visitorData\":\"$visitorData\"}}}")
        }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("X-Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20260928.13.00")
            .header("X-Goog-Api-Format-Version", "2")
            .header("Cookie", cookie)
            .header("Authorization", auth)
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Bootstrap-Logged-In", "true")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val status = try {
                json.parseToJsonElement(body).jsonObject["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.content
            } catch (_: Exception) { "HTTP ${resp.code}" }
            val adaptiveUrl = Regex(""""url":"([^"]+)"""").find(body)?.groupValues?.get(1)
            return resp.code to adaptiveUrl
        }.let { it }
    }

    @Test
    fun bindingVariants() = runTest {
        val cookie = loadEnv("ECHO_YT_COOKIE")
        val serverUp = runCatching {
            client.newCall(Request.Builder().url("$potUrl/ping").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        assumeTrue("Requires ECHO_YT_COOKIE and the local minter on $potUrl; skipping", cookie != null && serverUp)

        val visitor = fetchVisitorData(cookie!!)
        val tokenVideo = mint(videoId)
        val tokenVisitor = mint(visitor)
        println("visitorData len=${visitor.length}, tokenVideo len=${tokenVideo.length}, tokenVisitor len=${tokenVisitor.length}")

        data class Variant(val name: String, val playerToken: String?, val urlToken: String?)
        val variants = listOf(
            Variant("A_noPlayerToken_videoPot", null, tokenVideo),
            Variant("B_videoPlayer_videoPot", tokenVideo, tokenVideo),
            Variant("C_videoPlayer_visitorPot", tokenVideo, tokenVisitor),
            Variant("D_visitorPlayer_videoPot", tokenVisitor, tokenVideo),
            Variant("E_visitorPlayer_visitorPot", tokenVisitor, tokenVisitor),
            Variant("F_noPlayer_noPot", null, null),
        )

        println("%-28s %-12s %-10s %s".format("variant", "player", "urlPot", "probe"))
        for (v in variants) {
            val (code, firstUrl) = playerRequest(cookie, visitor, v.playerToken)
            if (firstUrl == null) {
                println("%-28s %-12s %-10s playerHTTP=%s no-url".format(v.name, v.playerToken?.length, v.urlToken?.length, code))
                continue
            }
            val resolved = YouTubeSignatureDeobfuscator.deobfuscateStreamUrl(videoId, firstUrl, null)
            if (resolved == null) {
                println("%-28s %-12s %-10s decipher failed".format(v.name, v.playerToken?.length, v.urlToken?.length))
                continue
            }
            val url = if (v.urlToken != null) {
                (if (resolved.contains("?")) "$resolved&pot=${v.urlToken}" else "$resolved?pot=${v.urlToken}")
            } else resolved
            val probe = client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36")
                    .build()
            ).execute().use { it.code }
            println("%-28s %-12s %-10s playerHTTP=%s probe=%s".format(v.name, v.playerToken?.length, v.urlToken?.length, code, probe))
        }
    }
}
