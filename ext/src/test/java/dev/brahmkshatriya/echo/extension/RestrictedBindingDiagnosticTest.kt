package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.streaming.YouTubeSignatureDeobfuscator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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

    companion object {
        private const val UA_145 = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        private const val UA_120 = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
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
            .header("User-Agent", UA_145)
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
            return resp.code to body
        }
    }

    /** Returns (muxedItag18Url, adaptiveItag251Url) — both deciphered, or null per slot. */
    private fun decipherStreams(body: String): Pair<String?, String?> {
        val root = json.parseToJsonElement(body).jsonObject
        // Feed the exact player JS to the deobfuscator, like the resolver does.
        root["assets"]?.jsonObject?.get("js")?.jsonPrimitive?.content?.let {
            YouTubeSignatureDeobfuscator.setPlayerJsUrl(it)
        }
        val streaming = root["streamingData"]?.jsonObject ?: return null to null

        fun fmtUrl(element: kotlinx.serialization.json.JsonElement): String? {
            val obj = element.jsonObject
            val plain = obj["url"]?.jsonPrimitive?.contentOrNull
            if (!plain.isNullOrEmpty()) {
                return YouTubeSignatureDeobfuscator.deobfuscateStreamUrl(videoId, plain, null)
            }
            val cipher = obj["signatureCipher"]?.jsonPrimitive?.contentOrNull
                ?: obj["cipher"]?.jsonPrimitive?.contentOrNull ?: return null
            val params = cipher.split('&').associate {
                val p = it.split('=', limit = 2)
                p[0] to (if (p.size > 1) java.net.URLDecoder.decode(p[1], "UTF-8") else "")
            }
            val raw = params["url"] ?: return null
            return YouTubeSignatureDeobfuscator.deobfuscateStreamUrl(videoId, raw, params["s"], params["sp"] ?: "sig")
        }

        val muxed = (streaming["formats"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull { it.jsonObject["itag"]?.jsonPrimitive?.contentOrNull == "18" }
            ?.let { fmtUrl(it) }
        val adaptive = (streaming["adaptiveFormats"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull { it.jsonObject["itag"]?.jsonPrimitive?.contentOrNull == "251" }
            ?.let { fmtUrl(it) }
        return muxed to adaptive
    }

    /**
     * Embedded-lane variants: which combination makes WEB_EMBEDDED_PLAYER serve
     * the age-restricted video (ideally with an HLS manifest)?
     */
    @Test
    fun webEmbeddedVariants() = runTest {
        val cookie = loadEnv("ECHO_YT_COOKIE")
        val serverUp = runCatching {
            client.newCall(Request.Builder().url("$potUrl/ping").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        assumeTrue("Requires ECHO_YT_COOKIE and the local minter; skipping", cookie != null && serverUp)

        val visitor = fetchVisitorData(cookie!!)
        val playerToken = mint(videoId)

        // Embed page scrape (same as EchoVideoEndpoint.fetchEmbedConfig) + player URL + sts from player JS.
        fun get(url: String, referer: String? = null, withCookie: Boolean = true): String =
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", UA_145)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .apply {
                        referer?.let { header("Referer", it) }
                        if (withCookie) header("Cookie", cookie)
                        header("Accept-Language", "en-US,en;q=0.9")
                    }
                    .build()
            ).execute().use { it.body?.string().orEmpty() }

        val embedHtml = get("https://www.youtube.com/embed/$videoId?html5=1", "https://www.reddit.com/")
        val hostFlags = Regex("\"encryptedHostFlags\"\\s*:\\s*\"([^\"]+)\"").find(embedHtml)?.groupValues?.get(1)
        val embedVisitor = Regex(""""visitorData"\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
        println("embedVisitor=${if (embedVisitor != null) "len=${embedVisitor.length}" else "none"}")
        val playerUrl = embedHtml.replace("\\/", "/").let { html ->
            Regex("\"PLAYER_JS_URL\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: Regex("\"jsUrl\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: Regex("(/s/player/[a-zA-Z0-9_-]+/[^\"'\\\\]+\\.js[^\"'\\\\]*)").find(html)?.groupValues?.get(1)
        }?.let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
        val stsFromPage = Regex("(?:signatureTimestamp|sts)\\s*:\\s*([0-9]{5})").find(embedHtml)?.groupValues?.get(1)?.toIntOrNull()
        val stsFromJs = playerUrl?.let {
            Regex("(?:signatureTimestamp|sts)\\s*:\\s*([0-9]{5})").find(get(it))?.groupValues?.get(1)?.toIntOrNull()
        }
        println("hostFlags=${if (hostFlags != null) "present" else "none"}, playerUrl=$playerUrl, stsPage=$stsFromPage, stsJs=$stsFromJs")

        val sts = stsFromJs ?: stsFromPage

        fun embeddedRequest(
            name: String,
            embedUrl: String?,
            sts: Int?,
            hostFlags: String?,
            onBehalfOf: String?,
            visitorOverride: String? = null,
            anonymous: Boolean = false,
            videoIdOverride: String? = null
        ) {
            val targetId = videoIdOverride ?: videoId
            val sessionVisitor = visitorOverride ?: visitor
            val payload = buildString {
                append("{\"videoId\":\"$targetId\",\"contentCheckOk\":true,\"racyCheckOk\":true,")
                append("\"playbackContext\":{\"contentPlaybackContext\":{\"html5Preference\":\"HTML5_PREF_WANTS\"")
                if (sts != null) append(",\"signatureTimestamp\":$sts")
                if (hostFlags != null) append(",\"encryptedHostFlags\":\"$hostFlags\"")
                append("}},")
                if (embedUrl != null) append("\"thirdParty\":{\"embedUrl\":\"$embedUrl\"},")
                append("\"context\":{\"client\":{\"clientName\":\"WEB_EMBEDDED_PLAYER\",\"clientVersion\":\"2.20260708.00.00\",\"hl\":\"en\",\"gl\":\"US\",\"visitorData\":\"$sessionVisitor\"}")
                if (onBehalfOf != null) append(",\"user\":{\"onBehalfOfUser\":\"$onBehalfOf\"}")
                append("}}")
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("User-Agent", UA_145)
                .header("Origin", "https://www.youtube.com")
                .header("X-Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("X-YouTube-Client-Name", "56")
                .header("X-YouTube-Client-Version", "2.20260708.00.00")
                .header("X-Goog-Api-Format-Version", "2")
                .header("X-Goog-Visitor-Id", sessionVisitor)
                .apply {
                    if (!anonymous) {
                        header("Cookie", cookie)
                        val cookieMap = dev.brahmkshatriya.echo.extension.utils.CookieParser.parse(cookie)
                        val auth = dev.brahmkshatriya.echo.extension.utils.CookieParser.generateDynamicAuthHeader(cookieMap, "https://www.youtube.com")
                        if (auth.isNotEmpty()) header("Authorization", auth)
                        header("X-Goog-AuthUser", "0")
                    }
                }
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val status = try {
                    val obj = json.parseToJsonElement(body).jsonObject
                    (obj["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.content ?: "HTTP ${resp.code}") +
                        " / " + (obj["playabilityStatus"]?.jsonObject?.get("reason")?.jsonPrimitive?.content ?: "-")
                } catch (_: Exception) { "HTTP ${resp.code}" }
                val hasHls = body.contains("\"hlsManifestUrl\"")
                val fmtCount = Regex("\"itag\"").findAll(body).count()
                println("%-38s status=%-46s hls=%-5s formats=%d".format(name, status.take(46), hasHls, fmtCount))
            }
        }

        embeddedRequest("V1_reddit_sts_flags (current)", "https://www.reddit.com/", sts, hostFlags, null)
        embeddedRequest("V2_youtubeEmbed_sts_flags", "https://www.youtube.com/embed/$videoId", sts, hostFlags, null)
        embeddedRequest("V3_noThirdParty", null, sts, hostFlags, null)
        embeddedRequest("V4_reddit_noFlags", "https://www.reddit.com/", sts, null, null)
        embeddedRequest("V5_reddit_stsJs_only", "https://www.reddit.com/", stsFromJs, null, null)
        embeddedRequest("V6_reddit_onBehalfVisitor", "https://www.reddit.com/", sts, hostFlags, "$visitor||")
        // innertubex uses the EMBED PAGE's own visitorData for the embedded session:
        embeddedRequest("V7_embedVisitor_reddit", "https://www.reddit.com/", sts, hostFlags, null, embedVisitor)
        embeddedRequest("V8_embedVisitor_anon", "https://www.reddit.com/", sts, hostFlags, null, embedVisitor, anonymous = true)
        embeddedRequest("V9_embedVisitor_header", "https://www.reddit.com/", sts, hostFlags, null, embedVisitor)
        embeddedRequest("V10_acctVisitor_header", "https://www.reddit.com/", sts, hostFlags, null, null)

        // Is the restricted video embeddable at all, and does the lane work for a normal video?
        println("restrictedEmbed: embedDisabled=${embedHtml.contains("Playback on other websites has been disabled", ignoreCase = true)}")
        val normalHtml = get("https://www.youtube.com/embed/dQw4w9WgXcQ?html5=1", "https://www.reddit.com/")
        val normalFlags = Regex("\"encryptedHostFlags\"\\s*:\\s*\"([^\"]+)\"").find(normalHtml)?.groupValues?.get(1)
        val normalStsPage = Regex("(?:signatureTimestamp|sts)\\s*:\\s*([0-9]{5})").find(normalHtml)?.groupValues?.get(1)?.toIntOrNull()
        val normalPlayerUrl = normalHtml.replace("\\/", "/").let { html ->
            Regex("\"PLAYER_JS_URL\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: Regex("(/s/player/[a-zA-Z0-9_-]+/[^\"'\\\\]+\\.js[^\"'\\\\]*)").find(html)?.groupValues?.get(1)
        }?.let { if (it.startsWith("http")) it else "https://www.youtube.com$it" }
        val normalSts = normalStsPage ?: normalPlayerUrl?.let { playerJs ->
            Regex("(?:signatureTimestamp|sts)\\s*:\\s*([0-9]{5})").find(get(playerJs))?.groupValues?.get(1)?.toIntOrNull()
        }
        embeddedRequest(
            "NORMAL_dQw_embed",
            "https://www.reddit.com/",
            normalSts,
            normalFlags,
            null,
            videoIdOverride = "dQw4w9WgXcQ"
        )
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

        data class Variant(val name: String, val playerToken: String?, val urlToken: String?, val probeUa: String = UA_145)
        val variants = listOf(
            Variant("A_noPlayerToken_videoPot", null, tokenVideo),
            Variant("B_videoPlayer_videoPot", tokenVideo, tokenVideo),
            Variant("C_videoPlayer_visitorPot", tokenVideo, tokenVisitor),
            Variant("D_visitorPlayer_videoPot", tokenVisitor, tokenVideo),
            Variant("E_visitorPlayer_visitorPot", tokenVisitor, tokenVisitor),
            Variant("F_noPlayer_noPot", null, null),
            // Extension's current probe UA for WEB_REMIX URLs (Chrome/120 fallback):
            Variant("G_noPot_probeChrome120", null, null, UA_120),
            Variant("H_videoPot_probeChrome120", tokenVideo, tokenVideo, UA_120),
        )

        println("%-26s %-10s %-10s %-10s %s".format("variant", "player", "urlPot", "muxed18", "adaptive251"))
        for (v in variants) {
            val (code, body) = playerRequest(cookie, visitor, v.playerToken)
            if (body == null) {
                println("%-26s playerHTTP=%s no-body".format(v.name, code))
                continue
            }
            val (muxed, adaptive) = decipherStreams(body)
            fun probe(url: String?, pot: String?): String {
                if (url == null) return "no-url"
                val withPot = if (pot != null) {
                    (if (url.contains("?")) "$url&pot=$pot" else "$url?pot=$pot")
                } else url
                return client.newCall(
                    Request.Builder().url(withPot).header("User-Agent", v.probeUa).build()
                ).execute().use { it.code }.toString()
            }
            println(
                "%-26s %-10s %-10s %-10s %s".format(
                    v.name,
                    v.playerToken?.length?.toString() ?: "-",
                    v.urlToken?.length?.toString() ?: "-",
                    probe(muxed, v.urlToken),
                    probe(adaptive, v.urlToken)
                )
            )
        }
    }
}
