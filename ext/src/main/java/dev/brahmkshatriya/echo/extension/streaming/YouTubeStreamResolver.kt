package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.YtmKtVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.YoutubeFormatResponse
import dev.brahmkshatriya.echo.extension.utils.stringSetting
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class YouTubeStreamResolver(
    private val api: YoutubeiApi,
    private val settings: Settings,
    private val videoEndpoint: EchoVideoEndpoint,
    private val visitorEndpoint: EchoVisitorEndpoint,
    private val cache: StreamMediaCache = StreamMediaCache(),
    private val poTokenProvider: PoTokenProvider = OffPoTokenProvider,
    /** Identity of the current session, used to keep cached media per account. */
    private val sessionKey: () -> String = { "" }
) {
    companion object {
        private val HEIGHT_REGEX = Regex("(\\d+)p")
        private const val PROBE_TIMEOUT_MS = 800L
    }

    private fun getRealNewPipe() = RealNewPipeVideoFormatsEndpoint()

    private val ytmKtEndpoint by lazy {
        YtmKtVideoFormatsEndpoint(api)
    }

    private val probeClient get() = StreamHttpClient.probe

    /** Sorted HLS master playlist URIs, memoized by the original manifest URL. */
    private val sortedHlsCache = ConcurrentHashMap<String, String>()

    private fun getUserAgentForUrl(url: String): String {
        return when {
            url.contains("c=VISIONOS") -> SpoofClient.VISIONOS.userAgent
            url.contains("c=IOS") -> SpoofClient.IOS.userAgent
            url.contains("c=TVHTML5") -> SpoofClient.TV.userAgent
            url.contains("c=MWEB") -> SpoofClient.MWEB.userAgent
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    }

    /**
     * Single request used as the last-line check for a resolved source. It must NOT
     * send a `Range` header: googlevideo answers ranged requests with 206 even for
     * URLs it rejects with 403 for normal playback (verified against the old
     * spoofed clients), which made a dead source look healthy. Only the response
     * code matters, and the body is closed immediately.
     */
    private fun probeStream(url: String): Boolean {
        if (url.contains(".m3u8") || url.contains("manifest/hls_variant")) return true
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", getUserAgentForUrl(url))
            .build()
        return probeClient.newCall(req).execute().use { resp ->
            if (resp.code == 403) {
                false
            } else {
                true
            }
        }
    }

    /**
     * Runs [probeStream] on IO with a short budget. A timeout, network error or any
     * non-403 response is treated as playable, so a slow edge node can never reject
     * the source. The OkHttp call timeout in [StreamHttpClient.probe] is the real
     * upper bound, because a blocking `execute()` ignores coroutine cancellation.
     */
    private suspend fun probePlayable(url: String): Boolean = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            try {
                probeStream(url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("YouTubeStreamResolver: probe error (${e.message}), assuming playable")
                true
            }
        }
        result ?: true
    }

    private suspend fun probeAllPlayable(vararg urls: String): Boolean = coroutineScope {
        urls.map { url -> async(Dispatchers.IO) { probePlayable(url) } }
            .map { it.await() }
            .all { it }
    }

    private fun createHlsSource(masterUrl: String, isMusicOrigin: Boolean = true): Streamable.Source.Http {
        return Streamable.Source.Http(
            request = createStreamingRequest(masterUrl, isMusicOrigin),
            type = Streamable.SourceType.HLS,
            quality = 2160,
            // No title: Echo's quality sheet prints the source title as a header
            // line — an "Auto" label there is noise next to the real track details.
            title = null,
            isVideo = true
        )
    }

    private fun getSortedHlsUrl(videoId: String, hlsUrl: String): String {
        sortedHlsCache[hlsUrl]?.let { cached ->
            val stillValid = !cached.startsWith("file:") || runCatching {
                java.io.File(java.net.URI(cached)).exists()
            }.getOrDefault(false)
            if (stillValid) return cached
            sortedHlsCache.remove(hlsUrl)
        }

        return runCatching {
            // Clean up stale temp files older than 1 hour
            runCatching {
                val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "")
                if (tempDir.isDirectory) {
                    val oneHourAgo = System.currentTimeMillis() - 3600_000L
                    tempDir.listFiles { f ->
                        f.name.startsWith("hls_") && f.name.endsWith(".m3u8") && f.lastModified() < oneHourAgo
                    }?.forEach { it.delete() }
                }
            }

            // Fetch YouTube's master playlist
            val req = okhttp3.Request.Builder()
                .url(hlsUrl)
                .header("User-Agent", getUserAgentForUrl(hlsUrl))
                .header("Accept", "*/*")
                .build()

            val rawM3u8 = StreamHttpClient.hls.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching hlsUrl
                resp.body?.string().orEmpty()
            }

            if (rawM3u8.isBlank() || !rawM3u8.contains("#EXT-X-STREAM-INF")) {
                return@runCatching hlsUrl
            }

            val sortedM3u8 = sortHlsMasterPlaylist(rawM3u8)
            val tempFile = java.io.File.createTempFile("hls_${videoId}_", ".m3u8")
            tempFile.deleteOnExit()
            tempFile.writeText(sortedM3u8)
            val fileUri = if (tempFile.absolutePath.startsWith("/")) {
                "file://${tempFile.absolutePath}"
            } else {
                tempFile.toURI().toString()
            }
            println("YouTubeStreamResolver: Re-ordered HLS master playlist (highest quality first) -> $fileUri")
            sortedHlsCache[hlsUrl] = fileUri
            fileUri
        }.getOrElse {
            println("YouTubeStreamResolver: Failed to sort HLS playlist, using direct URL: ${it.message}")
            hlsUrl
        }
    }


    private fun createStreamingRequest(
        url: String,
        isMusicOrigin: Boolean = true
    ): NetworkRequest {
        val isGoogleVideo = url.contains("googlevideo.com")
        val headers = mutableMapOf(
            "User-Agent" to getUserAgentForUrl(url),
            "Accept" to "*/*"
        )
        if (!isGoogleVideo && !url.startsWith("file:")) {
            val origin = if (isMusicOrigin || url.contains("c=WEB_REMIX")) "https://music.youtube.com" else "https://www.youtube.com"
            headers["Origin"] = origin
            headers["Referer"] = "$origin/"
            val cookie = RealNewPipeVideoFormatsEndpoint.globalCookie
            if (!cookie.isNullOrBlank()) {
                headers["Cookie"] = cookie
            }
        }
        println("createStreamingRequest: isGoogleVideo=$isGoogleVideo, headers=${headers.keys}, url=${url.redactedStreamUrl()}")
        return NetworkRequest(
            url = url,
            headers = headers
        )
    }

    private fun spoofSetting(): String =
        settings.stringSetting("spoof_stream_client", valid = SpoofClient.entries.map { it.id }.toSet() + "auto")
            ?.trim()?.lowercase() ?: "auto"

    /**
     * TVHTML5 answers `UNPLAYABLE / "The page needs to be reloaded."` when it is
     * called without a session, so the lane is only worth a request when a cookie
     * is available. visionOS remains the anonymous fast path.
     */
    private fun hasSessionCookie(): Boolean =
        !RealNewPipeVideoFormatsEndpoint.globalCookie.isNullOrBlank() ||
            !api.user_auth_state?.headers?.get("cookie").isNullOrBlank()

    private fun sessionSuffix(): String {
        // Makes the effective provider state unambiguous in every playback log:
        // a missing WEB_REMIX lane is always explained by this line.
        println("YouTubeStreamResolver: ${(poTokenProvider as? SettingsPoTokenProvider)?.describeState() ?: "potoken: kind=${poTokenProvider.kind}"}")
        return "${poTokenProvider.kind}:${sessionKey()}"
    }

    suspend fun resolveStreamable(videoId: String): Streamable.Media {
        return resolveStreamable(null, videoId)
    }

    suspend fun resolveStreamable(
        streamable: Streamable?,
        videoId: String
    ): Streamable.Media {
        val type = streamable?.extras?.get("type") ?: "audio"
        val height = streamable?.extras?.get("height") ?: "0"
        val key = "$videoId|$type|$height|${spoofSetting()}|${sessionSuffix()}"
        println("Loading streamable media for video ID: $videoId (type=$type)")
        return try {
            cache.getOrResolve(key) { resolveStreamableInternal(streamable, videoId) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never keep a half-broken result around: a retry must hit the network.
            cache.invalidate("$videoId|$type|")
            throw e
        }
    }

    suspend fun resolveBackground(videoId: String): Streamable.Media {
        return resolveBackground(null, videoId)
    }

    suspend fun resolveBackground(streamable: Streamable?, videoId: String): Streamable.Media {
        val targetHeight = streamable?.extras?.get("height")?.toIntOrNull()
            ?: streamable?.quality?.takeIf { it > 0 }
            ?: 720
        val key = "$videoId|background|$targetHeight|${spoofSetting()}|${sessionSuffix()}"
        return try {
            cache.getOrResolve(key) { resolveBackgroundInternal(videoId, targetHeight) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            cache.invalidate("$videoId|background|")
            throw e
        }
    }

    private suspend fun resolveBackgroundInternal(videoId: String, targetHeight: Int): Streamable.Media {
        println("Loading background video for video ID: $videoId at ${targetHeight}p")

        // 1. Fast path: visionOS (single player request, URLs that actually play)
        val spoofedVideoUrl = runCatching {
            val res = trySpoofedExtractor(videoId, SpoofClient.VISIONOS, isVideoRequest = true, targetHeight = targetHeight)
            if (res is ExtractionResult.Success && res.media is Streamable.Media.Server) {
                val videoSource = res.media.sources.firstOrNull { it.isVideo } as? Streamable.Source.Http
                videoSource?.request?.url
            } else null
        }.getOrNull() ?: runCatching {
            val res = trySpoofedExtractor(videoId, SpoofClient.IOS, isVideoRequest = true, targetHeight = targetHeight)
            if (res is ExtractionResult.Success && res.media is Streamable.Media.Server) {
                val videoSource = res.media.sources.firstOrNull { it.isVideo } as? Streamable.Source.Http
                videoSource?.request?.url
            } else null
        }.getOrNull()

        // 2. Fallback: RealNewPipe (muxed first, then separate video)
        val realNewPipeUrl = spoofedVideoUrl ?: runCatching {
            val muxedStreams = getRealNewPipe().getMuxedVideoStreams(videoId).getOrNull() ?: emptyList()
            val muxedMatch = muxedStreams.firstOrNull { format ->
                val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                h == targetHeight
            }
            if (muxedMatch?.url != null) {
                muxedMatch.url
            } else {
                val separate = getRealNewPipe().getSeparateStreams(videoId, null).getOrNull()
                val videoMatch = separate?.first?.minByOrNull { format ->
                    val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    abs(h - targetHeight)
                } ?: separate?.first?.firstOrNull()
                videoMatch?.url ?: muxedStreams.firstOrNull()?.url
            }
        }.getOrNull()

        // 3. Fallback: YtmKt
        val backgroundUrl = realNewPipeUrl ?: runCatching {
            val muxedStreams = ytmKtEndpoint.getMuxedVideoStreams(videoId).getOrNull() ?: emptyList()
            val muxedMatch = muxedStreams.firstOrNull { format ->
                val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                h == targetHeight
            }
            if (muxedMatch?.url != null) {
                muxedMatch.url
            } else {
                val separate = ytmKtEndpoint.getSeparateStreams(videoId, null).getOrNull()
                val videoMatch = separate?.first?.minByOrNull { format ->
                    val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    abs(h - targetHeight)
                } ?: separate?.first?.firstOrNull()
                videoMatch?.url ?: muxedStreams.firstOrNull()?.url
            }
        }.getOrNull() ?: runCatching {
            val restricted = tryRestrictedVideoExtractor(videoId, isVideoRequest = true, targetHeight = targetHeight)
            if (restricted is ExtractionResult.Success && restricted.media is Streamable.Media.Server) {
                val videoSource = restricted.media.sources.firstOrNull { it.isVideo } as? Streamable.Source.Http
                videoSource?.request?.url
            } else null
        }.getOrNull() ?: throw Exception("Failed to resolve background video for $videoId at ${targetHeight}p")

        return Streamable.Media.Background(createStreamingRequest(backgroundUrl, isMusicOrigin = false))
    }
    
    private suspend fun resolveStreamableInternal(
        streamable: Streamable?,
        videoId: String
    ): Streamable.Media {
        val streamType = streamable?.extras?.get("type")
        val isVideoRequest = streamType == "video"
        val targetHeight = streamable?.extras?.get("height")?.toIntOrNull()

        val errorReasons = mutableListOf<String>()

        // Web-client player requests must carry visitorData (context + X-Goog-Visitor-Id):
        // token-consistent URLs are rejected by the CDN otherwise. Only fetch when a
        // token provider is active, so token-free playback never pays the extra request.
        if (poTokenProvider.kind != "off" && api.visitor_id == null) {
            runCatching { api.visitor_id = visitorEndpoint.getVisitorId() }
                .onFailure { println("YouTubeStreamResolver: visitor id fetch failed (${it.message})") }
        }

        val spoofClientSetting = spoofSetting()
        val specificClient = SpoofClient.fromId(spoofClientSetting)
        println("resolveStreamableInternal: spoof_stream_client setting = '$spoofClientSetting', specificClient = ${specificClient?.displayName ?: "Auto"}")

        // 1. If user selected a specific spoof client (visionOS, iOS, TV, MWEB, WEB_REMIX), try it first!
        if (specificClient != null) {
            val spoofResult = trySpoofedExtractorWithTokenRetry(videoId, specificClient, isVideoRequest, targetHeight)
            if (spoofResult is ExtractionResult.Success) {
                println("Successfully loaded streamable using spoofed client: ${specificClient.displayName}")
                return spoofResult.media
            } else if (spoofResult is ExtractionResult.Error) {
                errorReasons.add("${specificClient.displayName}: ${spoofResult.reason}")
            }
        }
        
        if (isVideoRequest) {
            // Video request cascade
            // 1. Fast path: visionOS. One player request and it returns an HLS
            //    manifest plus already-signed progressive URLs that actually play.
            //    (iOS/TV currently hand out URLs that 403 on playback.)
            val visionOsResult = trySpoofedExtractor(videoId, SpoofClient.VISIONOS, isVideoRequest = true, targetHeight)
            if (visionOsResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using ${SpoofClient.VISIONOS.displayName}")
                return visionOsResult.media
            } else if (visionOsResult is ExtractionResult.Error) {
                errorReasons.add("${SpoofClient.VISIONOS.displayName}: ${visionOsResult.reason}")
            }

            // 2. Fallback: RealNewPipe extracts HLS + all progressive qualities with
            //    dynamic JS deciphering (most robust, but slower).
            println("RealNewPipe extractor for video ID: $videoId (Video)")
            val realNewPipeResult = tryRealNewPipeExtractor(videoId, isVideoRequest = true, targetHeight)
            if (realNewPipeResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using RealNewPipe extractor")
                return realNewPipeResult.media
            } else if (realNewPipeResult is ExtractionResult.Error) {
                errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
            }

            // 3. Fallback: token-free TV clients (cookie-compatible), then the
            //    embedded lane (token-free, returns an HLS manifest — the full
            //    quality grid — for age-restricted videos), then web clients
            //    only when a PO Token provider is configured.
            val videoCascade = buildList {
                if (hasSessionCookie()) {
                    add(SpoofClient.TV_CURRENT)
                    add(SpoofClient.TV_DOWNGRADED)
                    add(SpoofClient.WEB_EMBEDDED)
                }
                if (poTokenProvider.kind != "off") add(SpoofClient.WEB_REMIX)
            }.filter { it != specificClient }

            for (client in videoCascade) {
                val res = trySpoofedExtractorWithTokenRetry(videoId, client, isVideoRequest = true, targetHeight)
                if (res is ExtractionResult.Success) {
                    println("Successfully loaded video streamable using ${client.displayName}")
                    return res.media
                } else if (res is ExtractionResult.Error) {
                    errorReasons.add("${client.displayName}: ${res.reason}")
                }
            }

            // 3. Fallback: YtmKt (same Innertube family, different client)
            println("YtmKt extractor for video ID: $videoId (Video)")
            val ytmKtResult = tryYtmKtEndpoint(videoId, isVideoRequest = true, targetHeight)
            if (ytmKtResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using YtmKt library")
                return ytmKtResult.media
            } else if (ytmKtResult is ExtractionResult.Error) {
                errorReasons.add("YtmKt: ${ytmKtResult.reason}")
            }

            // 4. Fallback: YouTube Music API
            println("YouTube Music API extractor for video ID: $videoId (Video)")
            val youtubeMusicResult = tryYouTubeMusicApi(videoId, isVideoRequest = true, targetHeight)
            if (youtubeMusicResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using YouTube Music API")
                return youtubeMusicResult.media
            } else if (youtubeMusicResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
            }

            // 5. Fallback: Restricted / Age-Restricted Video Extractor
            println("Restricted extractor for video ID: $videoId (Video)")
            val restrictedResult = tryRestrictedVideoExtractor(videoId, isVideoRequest = true, targetHeight)
            if (restrictedResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using Restricted Video Extractor")
                return restrictedResult.media
            } else if (restrictedResult is ExtractionResult.Error) {
                errorReasons.add("Restricted Extractor: ${restrictedResult.reason}")
            }
        } else {
            // Audio request cascade
            // 1. Fast path: visionOS. One player request, returns already-signed
            //    direct URLs with all audio qualities. (iOS/TV URLs currently 403
            //    on real playback, so they are only fallbacks.)
            val visionOsResult = trySpoofedExtractor(videoId, SpoofClient.VISIONOS, isVideoRequest = false, targetHeight)
            if (visionOsResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using ${SpoofClient.VISIONOS.displayName}")
                return visionOsResult.media
            } else if (visionOsResult is ExtractionResult.Error) {
                errorReasons.add("${SpoofClient.VISIONOS.displayName}: ${visionOsResult.reason}")
            }

            // 2. Token-free TV chain. TVHTML5 is the only token-free client that
            //    is also cookie-compatible, so it is the first lane after visionOS.
            //    Both versions are tried because YouTube rolls versions gradually;
            //    each result is probed, so a dead one falls through to the next.
            val audioCascade = buildList {
                if (hasSessionCookie()) {
                    add(SpoofClient.TV_CURRENT)
                    add(SpoofClient.TV_DOWNGRADED)
                }
                // Web clients need a PO Token. Only spend the request when a
                // provider is configured; otherwise this lane can never succeed.
                if (poTokenProvider.kind != "off") add(SpoofClient.WEB_REMIX)
            }.filter { it != specificClient }

            for (client in audioCascade) {
                val res = trySpoofedExtractorWithTokenRetry(videoId, client, isVideoRequest = false, targetHeight)
                if (res is ExtractionResult.Success) {
                    println("Successfully loaded audio streamable using ${client.displayName}")
                    return res.media
                } else if (res is ExtractionResult.Error) {
                    errorReasons.add("${client.displayName}: ${res.reason}")
                }
            }

            // 3. Fallback: RealNewPipe extracts OPUS (160/70/50 kbps) + AAC (128/48 kbps)
            //    with dynamic JS deciphering.
            println("RealNewPipe extractor for video ID: $videoId (Audio - Opus/AAC)")
            val realNewPipeResult = tryRealNewPipeExtractor(videoId, isVideoRequest = false, targetHeight)
            if (realNewPipeResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using RealNewPipe (Opus/AAC)")
                return realNewPipeResult.media
            } else if (realNewPipeResult is ExtractionResult.Error) {
                errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
            }

            // 4. Fallback: YtmKt (also supports Opus)
            println("YtmKt extractor for video ID: $videoId (Audio)")
            val ytmKtResult = tryYtmKtEndpoint(videoId, isVideoRequest = false, targetHeight)
            if (ytmKtResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using YtmKt")
                return ytmKtResult.media
            } else if (ytmKtResult is ExtractionResult.Error) {
                errorReasons.add("YtmKt: ${ytmKtResult.reason}")
            }

            // 5. Fallback: YouTube Music API (AAC)
            println("YouTube Music API extractor for video ID: $videoId (Audio - AAC fallback)")
            val youtubeMusicResult = tryYouTubeMusicApi(videoId, isVideoRequest = false, targetHeight)
            if (youtubeMusicResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using YouTube Music API")
                return youtubeMusicResult.media
            } else if (youtubeMusicResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
            }

            // 6. Fallback: Restricted / Age-Restricted Video Extractor
            println("Trying Restricted Video Extractor for video ID: $videoId (Audio)")
            val restrictedResult = tryRestrictedVideoExtractor(videoId, isVideoRequest = false, targetHeight)
            if (restrictedResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using Restricted Video Extractor")
                return restrictedResult.media
            } else if (restrictedResult is ExtractionResult.Error) {
                errorReasons.add("Restricted Extractor: ${restrictedResult.reason}")
            }
        }

        val detailedError = if (errorReasons.isNotEmpty()) {
            val joined = errorReasons.joinToString("\n")
            // Signed-in users hitting 403s everywhere with no token source are
            // almost certainly on an age-restricted/account-locked track that
            // only web clients can serve; say so instead of a wall of 403s, and
            // include the provider state so the error dialog itself shows why.
            val providerState = (poTokenProvider as? SettingsPoTokenProvider)?.describeState()
                ?: "kind=${poTokenProvider.kind}"
            val hint = if (poTokenProvider.kind == "off" && hasSessionCookie() &&
                errorReasons.any { it.contains("403") }
            ) {
                "\n\nTip: this track is likely age-restricted or account-locked and needs a PO Token. " +
                    "Set Settings → PO Token Provider → On-device (or a bgutil server URL), then retry. " +
                    "[provider state: $providerState]"
            } else {
                ""
            }
            "Failed to resolve streaming URLs for video $videoId:\n$joined$hint"
        } else {
            "Failed to resolve streaming URLs for video $videoId - all extraction methods failed"
        }
        throw Exception(detailedError)
    }
    
    private sealed class ExtractionResult {
        data class Success(val media: Streamable.Media) : ExtractionResult()
        data class Error(val reason: String) : ExtractionResult()
        data object Failed : ExtractionResult()
    }

    private suspend fun tryRealNewPipeExtractor(
        videoId: String,
        isVideoRequest: Boolean,
        targetHeight: Int?
    ): ExtractionResult {
        return try {
            if (isVideoRequest) {
                // Fetch all stream types in one network call
                val allResult = getRealNewPipe().getAllStreams(videoId)
                if (allResult.isSuccess) {
                    val streams = allResult.getOrThrow()

                    // 1. If HLS manifest available, use single HLS master playlist for video
                    // ExoPlayer automatically exposes all audio and video qualities under Audio Selection & Video Selection,
                    // without showing redundant individual source buttons!
                    val hlsUrl = streams.hlsUrl
                    if (!hlsUrl.isNullOrEmpty()) {
                        val playableHlsUrl = getSortedHlsUrl(videoId, hlsUrl)
                        println("RealNewPipe: Using sorted HLS master playlist for video (${playableHlsUrl.redactedStreamUrl()})")
                        return ExtractionResult.Success(
                            Streamable.Media.Server(listOf(createHlsSource(playableHlsUrl)), merged = false)
                        )
                    }

                    // 2. Fallback if HLS unavailable: Progressive video + audio
                    if (targetHeight != null) {
                        // User specifically selected a height (e.g. 1080p, 720p, 480p, 360p)
                        val selectedVideo = streams.videoOnly.minByOrNull { format ->
                            val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            abs(h - targetHeight)
                        } ?: streams.muxed.minByOrNull { format ->
                            val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            abs(h - targetHeight)
                        } ?: streams.videoOnly.firstOrNull() ?: streams.muxed.firstOrNull()
                        val bestAudio = streams.audio.firstOrNull()

                        if (selectedVideo?.url != null && bestAudio?.url != null) {
                            val actualHeight = HEIGHT_REGEX.find(selectedVideo.mimeType)
                                ?.groupValues?.get(1)?.toIntOrNull() ?: targetHeight
                            val videoSource = Streamable.Source.Http(
                                request = createStreamingRequest(selectedVideo.url!!),
                                type = Streamable.SourceType.Progressive,
                                quality = actualHeight,
                                title = "${actualHeight}p",
                                isVideo = true
                            )
                            val audioSource = Streamable.Source.Http(
                                request = createStreamingRequest(bestAudio.url!!),
                                type = Streamable.SourceType.Progressive,
                                quality = if (bestAudio.bitrate > 0) bestAudio.bitrate / 1000 else 0
                            )
                            return ExtractionResult.Success(
                                Streamable.Media.Server(listOf(videoSource, audioSource), merged = true)
                            )
                        }
                    } else {
                        // No height requested: pair the best video with the best
                        // audio (merged=true) — a video-only quality list would play
                        // without sound in Echo.
                        val bestAudioFormat = streams.audio.firstOrNull()
                        val bestVideo = streams.videoOnly
                            .mapNotNull { format ->
                                val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull()
                                if (h != null && !format.url.isNullOrEmpty()) h to format else null
                            }
                            .maxByOrNull { it.first }
                        if (bestVideo != null && bestAudioFormat?.url != null) {
                            val h = bestVideo.first
                            val videoSource = Streamable.Source.Http(
                                request = createStreamingRequest(bestVideo.second.url!!),
                                type = Streamable.SourceType.Progressive,
                                quality = h,
                                title = "${h}p",
                                isVideo = true
                            )
                            val audioSource = Streamable.Source.Http(
                                request = createStreamingRequest(bestAudioFormat.url!!),
                                type = Streamable.SourceType.Progressive,
                                quality = if (bestAudioFormat.bitrate > 0) bestAudioFormat.bitrate / 1000 else 0
                            )
                            println("RealNewPipe: Merged best video (${h}p) + audio for quality-less video request")
                            return ExtractionResult.Success(
                                Streamable.Media.Server(listOf(videoSource, audioSource), merged = true)
                            )
                        }

                        val distinctVideos = (streams.videoOnly + streams.muxed)
                            .mapNotNull { format ->
                                val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull()
                                if (h != null && !format.url.isNullOrEmpty()) h to format else null
                            }
                            .groupBy { it.first }
                            .map { (_, list) -> list.first().second }
                            .sortedByDescending {
                                HEIGHT_REGEX.find(it.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            }

                        val sources = distinctVideos.map { vFormat ->
                            val h = HEIGHT_REGEX.find(vFormat.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 360
                            Streamable.Source.Http(
                                request = createStreamingRequest(vFormat.url!!),
                                type = Streamable.SourceType.Progressive,
                                quality = h,
                                title = "${h}p",
                                isVideo = true
                            )
                        }

                        if (sources.isNotEmpty()) {
                            println("RealNewPipe: Returning ${sources.size} progressive video qualities (muxed/audio-less fallback): ${sources.map { it.title }}")
                            return ExtractionResult.Success(
                                Streamable.Media.Server(sources, merged = false)
                            )
                        }
                    }
                }
            }

            // Audio extraction
            println("RealNewPipe: Extracting audio streams for $videoId")
            val audioResult = getRealNewPipe().getAudioFormats(videoId)
            if (audioResult.isFailure) {
                return ExtractionResult.Failed
            }

            val rawAudioFormats = audioResult.getOrThrow()
            if (rawAudioFormats.isEmpty()) {
                return ExtractionResult.Failed
            }

            // Return ALL audio qualities (Opus 160k, AAC 128k, Opus 70k, Opus 50k, AAC 48k)
            val sortedAudioFormats = rawAudioFormats.sortedByDescending { it.bitrate }
            val allSources = sortedAudioFormats
                .filter { it.url != null }
                .map { format ->
                    Streamable.Source.Http(
                        request = createStreamingRequest(format.url!!),
                        type = Streamable.SourceType.Progressive,
                        quality = if (format.bitrate > 0) format.bitrate / 1000 else 0
                    )
                }

            if (allSources.isEmpty()) return ExtractionResult.Failed
            return ExtractionResult.Success(Streamable.Media.Server(allSources, merged = false))
        } catch (e: Exception) {
            println("RealNewPipe Exception: ${e.message}")
            val errorMsg = e.message ?: "Unknown error"
            return ExtractionResult.Error(errorMsg)
        }
    }

    private suspend fun tryYtmKtEndpoint(
        videoId: String,
        isVideoRequest: Boolean,
        targetHeight: Int?
    ): ExtractionResult {
        return try {
            if (isVideoRequest) {
                val reqHeight = targetHeight ?: 720
                val separateResult = ytmKtEndpoint.getSeparateStreams(videoId, maxQuality = null)
                if (separateResult.isSuccess) {
                    val (videoFormats, audioFormats) = separateResult.getOrThrow()
                    val selectedVideo = videoFormats.minByOrNull { format ->
                        val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        abs(h - reqHeight)
                    } ?: videoFormats.firstOrNull()
                    val bestAudio = audioFormats.firstOrNull()

                    if (selectedVideo?.url != null && bestAudio?.url != null) {
                        val actualHeight = HEIGHT_REGEX.find(selectedVideo.mimeType)
                            ?.groupValues?.get(1)?.toIntOrNull() ?: reqHeight
                        val videoSource = Streamable.Source.Http(
                            request = createStreamingRequest(selectedVideo.url!!),
                            type = Streamable.SourceType.Progressive,
                            quality = actualHeight,
                            title = "${actualHeight}p",
                            isVideo = true
                        )
                        val audioSource = Streamable.Source.Http(
                            request = createStreamingRequest(bestAudio.url!!),
                            type = Streamable.SourceType.Progressive,
                            quality = if (bestAudio.bitrate > 0) bestAudio.bitrate / 1000 else 0
                        )
                        return ExtractionResult.Success(
                            Streamable.Media.Server(listOf(videoSource, audioSource), merged = true)
                        )
                    }
                }
            }

            val audioResult = ytmKtEndpoint.getAudioFormats(videoId)
            if (audioResult.isFailure) {
                return ExtractionResult.Error(audioResult.exceptionOrNull()?.message ?: "Failed to get audio")
            }

            val rawAudio = audioResult.getOrThrow()
            if (rawAudio.isEmpty()) {
                return ExtractionResult.Error("No audio streams")
            }

            // Return ALL audio qualities as separate sources so the user can pick in the Source panel
            val sortedAudio = rawAudio.sortedByDescending { it.bitrate }
            val allSources = sortedAudio
                .filter { it.url != null }
                .map { format ->
                    Streamable.Source.Http(
                        request = createStreamingRequest(format.url!!),
                        type = Streamable.SourceType.Progressive,
                        quality = if (format.bitrate > 0) format.bitrate / 1000 else 0
                    )
                }

            if (allSources.isEmpty()) return ExtractionResult.Error("No usable audio streams")
            return ExtractionResult.Success(Streamable.Media.Server(allSources, merged = false))
        } catch (e: Exception) {
            return ExtractionResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun trySpoofedExtractor(
        videoId: String,
        client: SpoofClient,
        isVideoRequest: Boolean,
        targetHeight: Int? = null
    ): ExtractionResult {
        return try {
            println("SpoofClientExtractor: Trying client ${client.displayName} for $videoId (isVideo=$isVideoRequest)")
            val videoResponse = videoEndpoint.getSpoofedVideo(videoId, client)
                ?: return ExtractionResult.Error("${client.displayName} returned no response or playable formats")
            extractMediaFromFormatResponse(
                videoId = videoId,
                videoResponse = videoResponse,
                isVideoRequest = isVideoRequest,
                targetHeight = targetHeight,
                isMusicOrigin = client == SpoofClient.WEB_REMIX,
                client = client
            )
        } catch (e: Exception) {
            println("SpoofClientExtractor (${client.displayName}) Exception: ${e.message}")
            ExtractionResult.Error(e.message ?: "${client.displayName} extraction failed")
        }
    }

    /**
     * A web-client lane can fail its health check when the token it was given was
     * minted for an earlier request (expired, or invalidated by a 403). Re-mint
     * once and retry the lane before falling through to the next extractor.
     */
    private suspend fun trySpoofedExtractorWithTokenRetry(
        videoId: String,
        client: SpoofClient,
        isVideoRequest: Boolean,
        targetHeight: Int? = null
    ): ExtractionResult {
        val first = trySpoofedExtractor(videoId, client, isVideoRequest, targetHeight)
        if (first is ExtractionResult.Success) return first
        val failedReason = (first as? ExtractionResult.Error)?.reason.orEmpty()
        val retryable = client.requiresStreamToken &&
            poTokenProvider.kind != "off" &&
            failedReason.contains("403")
        if (!retryable) return first
        println("YouTubeStreamResolver: ${client.displayName} rejected its stream URL; retrying with a fresh PO Token")
        poTokenProvider.invalidate()
        return trySpoofedExtractor(videoId, client, isVideoRequest, targetHeight)
    }

    private suspend fun tryYouTubeMusicApi(
        videoId: String,
        isVideoRequest: Boolean,
        targetHeight: Int? = null
    ): ExtractionResult {
        return try {
            try {
                if (api.visitor_id == null) {
                    api.visitor_id = visitorEndpoint.getVisitorId()
                }
            } catch (e: Exception) {
                println("Exception ensuring visitor ID: ${e.message}")
            }

            // resolve=false avoids the redundant web remix request, cutting network latency in half
            val (video, _) = videoEndpoint.getVideo(false, videoId)
            extractMediaFromFormatResponse(videoId, video, isVideoRequest, targetHeight, isMusicOrigin = true)
        } catch (e: Exception) {
            ExtractionResult.Error(e.message ?: "Unknown error")
        }
    }

    private suspend fun tryRestrictedVideoExtractor(
        videoId: String,
        isVideoRequest: Boolean,
        targetHeight: Int? = null
    ): ExtractionResult {
        return try {
            // A PO Token is bound to the visitor used for the player request, so
            // the binding must exist before the restricted request is made.
            if (api.visitor_id == null) {
                runCatching { api.visitor_id = visitorEndpoint.getVisitorId() }
            }
            println("RestrictedVideoExtractor: Trying web_creator / restricted player for $videoId")
            val result = videoEndpoint.getRestrictedVideo(videoId)
                ?: return ExtractionResult.Error("Restricted player returned no response")
            extractMediaFromFormatResponse(
                videoId = videoId,
                videoResponse = result.response,
                isVideoRequest = isVideoRequest,
                targetHeight = targetHeight,
                isMusicOrigin = false,
                client = result.client
            )
        } catch (e: Exception) {
            println("RestrictedVideoExtractor Exception: ${e.message}")
            ExtractionResult.Error(e.message ?: "Restricted extraction failed")
        }
    }

    private suspend fun extractMediaFromFormatResponse(
        videoId: String,
        videoResponse: YoutubeFormatResponse,
        isVideoRequest: Boolean,
        targetHeight: Int? = null,
        isMusicOrigin: Boolean = false,
        client: SpoofClient? = null
    ): ExtractionResult {
        val streamingData = videoResponse.streamingData
            ?: return ExtractionResult.Error("Player response contains no streaming data")

        // Decipher against the base.js this response actually came from.
        YouTubeSignatureDeobfuscator.setPlayerJsUrl(videoResponse.assets?.js)

        // 1. Try HLS / DASH manifest if present for Video
        val hlsUrl = streamingData.hlsManifestUrl ?: streamingData.dashManifestUrl
        if (isVideoRequest && !hlsUrl.isNullOrEmpty()) {
            val playableHlsUrl = getSortedHlsUrl(videoId, hlsUrl)
            println("YouTubeStreamResolver: Using sorted HLS master playlist for video (${playableHlsUrl.redactedStreamUrl()})")
            return ExtractionResult.Success(
                Streamable.Media.Server(listOf(createHlsSource(playableHlsUrl, isMusicOrigin)), merged = false)
            )
        }

        val allFormats = streamingData.formats + streamingData.adaptiveFormats
        if (allFormats.isEmpty()) {
            return ExtractionResult.Error("No formats in player streamingData")
        }

        data class ResolvedFormat(
            val itag: Long,
            val mimeType: String,
            val bitrate: Int,
            val height: Long?,
            val url: String,
            val isAudio: Boolean,
            val isVideo: Boolean
        )

        val resolved = allFormats.mapNotNull { f ->
            val cipher = f.signatureCipher ?: f.cipher
            val url = if (!f.url.isNullOrEmpty()) {
                YouTubeSignatureDeobfuscator.deobfuscateStreamUrl(videoId, f.url, null)
            } else if (!cipher.isNullOrEmpty()) {
                deobfuscateCipherUrl(videoId, cipher)
            } else null

            if (url == null) null
            else {
                val mime = f.mimeType.lowercase()
                ResolvedFormat(
                    itag = f.itag ?: 0L,
                    mimeType = f.mimeType,
                    bitrate = f.bitrate,
                    height = f.height,
                    url = url,
                    isAudio = mime.contains("audio/"),
                    isVideo = mime.contains("video/")
                )
            }
        }

        if (resolved.isEmpty()) {
            return ExtractionResult.Error("Failed to decipher any format URLs")
        }

        println("YouTubeStreamResolver: Deciphered ${resolved.size} of ${allFormats.size} formats:")
        resolved.forEach {
            println("  itag=${it.itag}, height=${it.height}p, isVideo=${it.isVideo}, isAudio=${it.isAudio}, bitrate=${it.bitrate / 1000}k, mime=${it.mimeType}")
        }

        // Web clients serve URLs that the CDN rejects until a PO Token bound to
        // this visitor is appended. Token-free clients (visionOS, TVHTML5) skip
        // this entirely; skipping the lane is better than probing a URL that
        // cannot succeed.
        // Web clients can carry a PO Token. It is optional: the CDN also serves a
        // correctly signed URL without one, and failing the lane because no
        // provider is configured would be worse than playing without a token.
        val tokenCapable = client?.requiresStreamToken == true
        val token = if (tokenCapable) poTokenProvider.token(videoId) else null
        if (tokenCapable) {
            println(
                if (token.isNullOrBlank()) {
                    "YouTubeStreamResolver: no PO Token available for ${client?.displayName}; playing without one"
                } else {
                    "YouTubeStreamResolver: PO Token for ${client?.displayName}: binding=$videoId, " +
                        "token=${token.length} chars"
                }
            )
        }
        fun withToken(url: String): String = url.withPoToken(token)

        if (isVideoRequest) {
            val videos = resolved.filter { it.isVideo && (it.height ?: 0L) > 0 }
            val audios = resolved.filter { it.isAudio }
            val bestAudio = audios.maxByOrNull { it.bitrate } ?: audios.firstOrNull()

            val distinctVideoFormats = videos
                .groupBy { it.height!!.toInt() }
                .map { (_, formatsAtHeight) ->
                    formatsAtHeight.firstOrNull { it.mimeType.contains("mp4", ignoreCase = true) }
                        ?: formatsAtHeight.firstOrNull { it.mimeType.contains("webm", ignoreCase = true) }
                        ?: formatsAtHeight.maxByOrNull { it.bitrate }
                        ?: formatsAtHeight.first()
                }
                .sortedByDescending { it.height }

            if (targetHeight != null && distinctVideoFormats.isNotEmpty() && bestAudio != null) {
                val selectedVideo = distinctVideoFormats.minByOrNull {
                    abs(it.height!!.toInt() - targetHeight)
                } ?: distinctVideoFormats.first()

                val h = selectedVideo.height!!.toInt()
                val videoSource = Streamable.Source.Http(
                    request = createStreamingRequest(withToken(selectedVideo.url), isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = h,
                    title = "${h}p",
                    isVideo = true
                )
                val audioBitrate = normalizeAudioBitrate(bestAudio.itag, bestAudio.bitrate)
                val audioSource = Streamable.Source.Http(
                    request = createStreamingRequest(withToken(bestAudio.url), isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = audioBitrate,
                    title = null,
                    isVideo = false
                )
                println("YouTubeStreamResolver: Video request (targetHeight=$targetHeight). Selected: ${videoSource.title} (itag=${selectedVideo.itag}), audio: itag=${bestAudio.itag} (${audioBitrate}kbps). Returning merged video+audio.")
                // One parallel check for the two streams that will actually play.
                if (!probeAllPlayable(withToken(selectedVideo.url), withToken(bestAudio.url))) {
                    if (!token.isNullOrBlank()) poTokenProvider.invalidate()
                    println("YouTubeStreamResolver: Extracted video/audio stream URL rejected with HTTP 403.")
                    return ExtractionResult.Error("Video stream failed health check (HTTP 403 Forbidden)")
                }
                return ExtractionResult.Success(Streamable.Media.Server(listOf(videoSource, audioSource), merged = true))
            } else if (distinctVideoFormats.isNotEmpty()) {
                // No height requested (the default video streamable): Echo plays a
                // merged=true set with ALL sources simultaneously, so pair the best
                // video with the best audio — a merged=false quality list would play
                // a single video-only source (no sound).
                val bestVideo = distinctVideoFormats.first()
                val h = bestVideo.height!!.toInt()
                val videoSource = Streamable.Source.Http(
                    request = createStreamingRequest(withToken(bestVideo.url), isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = h,
                    title = "${h}p",
                    isVideo = true
                )
                if (bestAudio == null) {
                    // No audio formats in the response: fall back to the video-only
                    // quality list rather than silence.
                    val sources = distinctVideoFormats.map { format ->
                        val fh = format.height!!.toInt()
                        Streamable.Source.Http(
                            request = createStreamingRequest(withToken(format.url), isMusicOrigin = isMusicOrigin),
                            type = Streamable.SourceType.Progressive,
                            quality = fh,
                            title = "${fh}p",
                            isVideo = true
                        )
                    }
                    if (!probePlayable(sources.first().request.url)) {
                        if (!token.isNullOrBlank()) poTokenProvider.invalidate()
                        println("YouTubeStreamResolver: Top video-only source rejected with HTTP 403, rejecting extractor result")
                        return ExtractionResult.Error("Video stream failed health check (HTTP 403 Forbidden)")
                    }
                    println("YouTubeStreamResolver: Video request (no audio formats). Returning ${sources.size} video-only qualities")
                    return ExtractionResult.Success(Streamable.Media.Server(sources, merged = false))
                }
                val audioBitrate = normalizeAudioBitrate(bestAudio.itag, bestAudio.bitrate)
                val audioSource = Streamable.Source.Http(
                    request = createStreamingRequest(withToken(bestAudio.url), isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = audioBitrate,
                    title = null,
                    isVideo = false
                )
                // Verify the video that will actually play first.
                if (!probePlayable(videoSource.request.url)) {
                    if (!token.isNullOrBlank()) poTokenProvider.invalidate()
                    println("YouTubeStreamResolver: Top video source rejected with HTTP 403, rejecting extractor result")
                    return ExtractionResult.Error("Video stream failed health check (HTTP 403 Forbidden)")
                }
                println("YouTubeStreamResolver: Video request (targetHeight=null). Merged best video (${h}p) + audio (${audioBitrate}kbps)")
                return ExtractionResult.Success(
                    Streamable.Media.Server(listOf(videoSource, audioSource), merged = true)
                )
            }

            // Fallback: Muxed formats if separate streams unavailable and playable
            val muxedFormats = resolved.filter { (it.itag == 18L || it.itag == 22L) || (it.isVideo && it.mimeType.contains("mp4a")) }
            val muxed = muxedFormats.firstOrNull { probePlayable(withToken(it.url)) }
            if (muxed != null) {
                val h = muxed.height?.toInt() ?: if (muxed.itag == 18L) 360 else 720
                val muxedSource = Streamable.Source.Http(
                    request = createStreamingRequest(withToken(muxed.url), isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = h,
                    title = "${h}p",
                    isVideo = true
                )
                println("YouTubeStreamResolver: Returning muxed video source: ${muxedSource.title} (itag=${muxed.itag})")
                return ExtractionResult.Success(Streamable.Media.Server(listOf(muxedSource), merged = false))
            }

            return ExtractionResult.Error("No playable video formats found")
        }

        // Audio extraction: include ALL audio qualities so the user can choose in the Source panel.
        // No per-format probing here: quality lists must be built without network round-trips.
        val adaptiveAudio = resolved.filter { it.isAudio && it.itag != 18L }.sortedByDescending {
            normalizeAudioBitrate(it.itag, it.bitrate)
        }
        val muxedAudio = resolved.firstOrNull { it.itag == 18L }
        println("YouTubeStreamResolver: Audio extraction for $videoId: ${adaptiveAudio.size} adaptive audio formats, muxed format 18 present: ${muxedAudio != null}")

        val sources = mutableListOf<Streamable.Source.Http>()

        // Add adaptive audio qualities (Opus 160k, AAC 128k, Opus 70k, Opus 50k, etc.)
        adaptiveAudio.forEach { format ->
            val kbps = normalizeAudioBitrate(format.itag, format.bitrate)
            sources.add(
                Streamable.Source.Http(
                    request = createStreamingRequest(withToken(format.url), isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = kbps,
                    title = null,
                    isVideo = false
                )
            )
        }

        // Also add progressive muxed format 18 (AAC 128kbps) if not already added
        if (muxedAudio != null && sources.none { it.quality == 128 }) {
            sources.add(
                Streamable.Source.Http(
                    request = createStreamingRequest(withToken(muxedAudio.url), isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = 128,
                    title = null,
                    isVideo = false
                )
            )
        }

        if (sources.isEmpty()) {
            return ExtractionResult.Error("No playable audio streams found")
        }

        // Verify only the source Echo will start with; a dead top source means the
        // whole client response is unusable (all URLs come from the same client), so
        // let the cascade move to the next extractor instead of caching a bad result.
        if (!probePlayable(sources.first().request.url)) {
            if (!token.isNullOrBlank()) poTokenProvider.invalidate()
            println("YouTubeStreamResolver: Top audio source rejected with HTTP 403, rejecting extractor result")
            // A signed-in user hitting this on a web client without a token means
            // YouTube served an age/account-restricted track: report the actual
            // cause so it is not mistaken for a broken stream or a bad cookie.
            if (client?.requiresStreamToken == true && token.isNullOrBlank()) {
                return ExtractionResult.Error(
                    "This track is age-restricted or account-locked and needs a PO Token. " +
                        "Set Settings → PO Token Provider → On-device (no server needed), or a bgutil server URL."
                )
            }
            return ExtractionResult.Error("Audio stream failed health check (HTTP 403 Forbidden)")
        }

        println("YouTubeStreamResolver: Returning ${sources.size} audio qualities: ${sources.map { "Quality ${it.quality}" }}")
        return ExtractionResult.Success(Streamable.Media.Server(sources, merged = false))
    }

    private fun normalizeAudioBitrate(itag: Long, rawBitrate: Int): Int {
        return when (itag) {
            251L -> 160
            140L -> 128
            250L -> 70
            249L -> 50
            else -> if (rawBitrate > 0) rawBitrate / 1000 else 128
        }
    }

    private fun deobfuscateCipherUrl(videoId: String, cipherString: String): String? {
        return runCatching {
            val params = cipherString.split('&').associate {
                val parts = it.split('=', limit = 2)
                parts[0] to (if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) else "")
            }
            val rawUrl = params["url"] ?: return@runCatching null
            val s = params["s"]
            val sp = params["sp"] ?: "sig"

            YouTubeSignatureDeobfuscator.deobfuscateStreamUrl(videoId, rawUrl, s, sp)
        }.onFailure {
            println("deobfuscateCipherUrl error: ${it.javaClass.simpleName} - ${it.message}")
            it.printStackTrace()
        }.getOrNull()
    }
}

/**
 * Rewrites an HLS master playlist for Echo's player: one muxed variant per height
 * (highest bandwidth), strictly descending, audio renditions stripped so the audio
 * follows the playing variant. See `HlsPlaylistTransformTest`.
 */
internal fun sortHlsMasterPlaylist(m3u8Content: String): String {
    val lines = m3u8Content.lines()
    val headerLines = mutableListOf<String>()
    val variantPairs = mutableListOf<Pair<String, String>>()

    var currentStreamInf: String? = null
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
            currentStreamInf = trimmed
        } else if (currentStreamInf != null) {
            variantPairs.add(currentStreamInf to trimmed)
            currentStreamInf = null
        } else {
            headerLines.add(trimmed)
        }
    }

    // One variant per height (highest bandwidth wins) in strict descending
    // order: multiple renditions per height produced duplicate quality chips
    // in Echo's sheet, and ExoPlayer's ABR starts on the LAST (lowest) entry —
    // which highlighted an oddball low-fps 144p variant while playing 1080p.
    val bestPerHeight = variantPairs
        .groupBy { (inf, _) ->
            val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(inf)
            res?.groupValues?.get(2)?.toLongOrNull() ?: 0L
        }
        .map { (_, pairsAtHeight) ->
            pairsAtHeight.maxByOrNull { (inf, _) ->
                Regex("BANDWIDTH=(\\d+)").find(inf)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } ?: pairsAtHeight.first()
        }
        .sortedByDescending { (inf, _) ->
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(inf)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(inf)
            val h = res?.groupValues?.get(2)?.toLongOrNull() ?: 0L
            h * 100_000_000L + bandwidth
        }

    val out = StringBuilder()
    for (h in headerLines) {
        // Audio renditions MUST stay: the variant CODECS list an mp4a codec, but
        // the variant segments are video-only fMP4 — the audio samples live in
        // the rendition. Removing them breaks playback with "Unable to bind a
        // sample queue to TrackGroup (audio/mp4a-latm)".
        out.appendLine(h)
    }
    for ((inf, url) in bestPerHeight) {
        out.appendLine(inf)
        out.appendLine(url)
    }
    return out.toString()
}
