package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.YtmKtVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.YoutubeFormatResponse
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs

class YouTubeStreamResolver(
    private val api: YoutubeiApi,
    private val settings: Settings,
    private val videoEndpoint: EchoVideoEndpoint,
    private val visitorEndpoint: EchoVisitorEndpoint
) {
    companion object {
        private val HEIGHT_REGEX = Regex("(\\d+)p")
    }

    private fun getRealNewPipe() = RealNewPipeVideoFormatsEndpoint()

    private val ytmKtEndpoint by lazy {
        YtmKtVideoFormatsEndpoint(api)
    }

    private val probeClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun getUserAgentForUrl(url: String): String {
        return when {
            url.contains("c=ANDROID_VR") -> SpoofClient.ANDROID_VR.userAgent
            url.contains("c=IOS") -> SpoofClient.IOS.userAgent
            url.contains("c=TVHTML5") -> SpoofClient.TV.userAgent
            url.contains("c=MWEB") -> SpoofClient.MWEB.userAgent
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    }

    private fun isStreamPlayable(url: String): Boolean {
        if (url.contains(".m3u8") || url.contains("manifest/hls_variant")) return true
        return runCatching {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", getUserAgentForUrl(url))
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val code = resp.code
                val isPlayable = code == 200 || code == 206 || (code in 300..399)
                if (code == 403) {
                    println("YouTubeStreamResolver: Stream health check failed with HTTP 403 Forbidden for ${url.take(60)}...")
                    false
                } else {
                    println("YouTubeStreamResolver: Stream health check HTTP $code for ${url.take(60)}... (playable=$isPlayable)")
                    isPlayable
                }
            }
        }.getOrElse {
            println("YouTubeStreamResolver: Stream health check error: ${it.message}, defaulting to playable")
            true
        }
    }

    private fun createHlsSource(masterUrl: String, isMusicOrigin: Boolean = true): Streamable.Source.Http {
        return Streamable.Source.Http(
            request = createStreamingRequest(masterUrl, isMusicOrigin),
            type = Streamable.SourceType.HLS,
            quality = 1080,
            title = "Auto",
            isVideo = true
        )
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
        if (!isGoogleVideo) {
            val origin = if (isMusicOrigin || url.contains("c=WEB_REMIX")) "https://music.youtube.com" else "https://www.youtube.com"
            headers["Origin"] = origin
            headers["Referer"] = "$origin/"
            val cookie = RealNewPipeVideoFormatsEndpoint.globalCookie
            if (!cookie.isNullOrBlank()) {
                headers["Cookie"] = cookie
            }
        }
        println("createStreamingRequest: isGoogleVideo=$isGoogleVideo, headers=${headers.keys}, url=${url.take(80)}...")
        return NetworkRequest(
            url = url,
            headers = headers
        )
    }

    suspend fun resolveStreamable(videoId: String): Streamable.Media {
        return resolveStreamable(null, videoId)
    }

    suspend fun resolveStreamable(
        streamable: Streamable?,
        videoId: String
    ): Streamable.Media {
        println("Loading streamable media for video ID: $videoId, title: ${streamable?.title}")
        return resolveStreamableInternal(streamable, videoId)
    }

    suspend fun resolveBackground(videoId: String): Streamable.Media {
        return resolveBackground(null, videoId)
    }

    suspend fun resolveBackground(streamable: Streamable?, videoId: String): Streamable.Media {
        val targetHeight = streamable?.extras?.get("height")?.toIntOrNull()
            ?: streamable?.quality?.takeIf { it > 0 }
            ?: 720
        println("Loading background video for video ID: $videoId at ${targetHeight}p")

        // 1. Try RealNewPipe: check muxed stream first, then separate video
        val realNewPipeUrl = runCatching {
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

        // 2. Try Spoofed clients (iOS / Android VR / TV)
        val spoofedVideoUrl = realNewPipeUrl ?: runCatching {
            val res = trySpoofedExtractor(videoId, SpoofClient.IOS, isVideoRequest = true, targetHeight = targetHeight)
            if (res is ExtractionResult.Success && res.media is Streamable.Media.Server) {
                val videoSource = res.media.sources.firstOrNull { it.isVideo } as? Streamable.Source.Http
                videoSource?.request?.url
            } else null
        }.getOrNull() ?: runCatching {
            val res = trySpoofedExtractor(videoId, SpoofClient.ANDROID_VR, isVideoRequest = true, targetHeight = targetHeight)
            if (res is ExtractionResult.Success && res.media is Streamable.Media.Server) {
                val videoSource = res.media.sources.firstOrNull { it.isVideo } as? Streamable.Source.Http
                videoSource?.request?.url
            } else null
        }.getOrNull()

        // 3. Fallback to YtmKt
        val backgroundUrl = spoofedVideoUrl ?: runCatching {
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

        val spoofSetting = settings.getString("spoof_stream_client")?.trim()?.lowercase() ?: "auto"
        val specificClient = SpoofClient.fromId(spoofSetting)
        println("resolveStreamableInternal: spoof_stream_client setting = '$spoofSetting', specificClient = ${specificClient?.displayName ?: "Auto"}")

        // 1. If user selected a specific spoof client (e.g. Android VR, iOS, TV, MWEB, WEB_REMIX), try it first!
        if (specificClient != null) {
            val spoofResult = trySpoofedExtractor(videoId, specificClient, isVideoRequest, targetHeight)
            if (spoofResult is ExtractionResult.Success) {
                println("Successfully loaded streamable using spoofed client: ${specificClient.displayName}")
                return spoofResult.media
            } else if (spoofResult is ExtractionResult.Error) {
                errorReasons.add("${specificClient.displayName}: ${spoofResult.reason}")
            }
        }
        
        if (isVideoRequest) {
            // Video request cascade
            // 1. Primary: RealNewPipe extracts HLS + 1080p, 720p, 480p, 360p, 240p, 144p with dynamic JS deciphering (guaranteed HTTP 206)
            println("RealNewPipe extractor for video ID: $videoId (Video)")
            val realNewPipeResult = tryRealNewPipeExtractor(videoId, isVideoRequest = true, targetHeight)
            if (realNewPipeResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using RealNewPipe extractor")
                return realNewPipeResult.media
            } else if (realNewPipeResult is ExtractionResult.Error) {
                errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
            }

            // 2. Fallback: Spoofed clients
            val videoCascade = if (specificClient != null) {
                listOf(SpoofClient.IOS, SpoofClient.ANDROID_VR, SpoofClient.TV).filter { it != specificClient }
            } else {
                listOf(SpoofClient.IOS, SpoofClient.ANDROID_VR, SpoofClient.TV)
            }

            for (client in videoCascade) {
                val res = trySpoofedExtractor(videoId, client, isVideoRequest = true, targetHeight)
                if (res is ExtractionResult.Success) {
                    println("Successfully loaded video streamable using ${client.displayName}")
                    return res.media
                } else if (res is ExtractionResult.Error) {
                    errorReasons.add("${client.displayName}: ${res.reason}")
                }
            }

            // Fallback 1: YouTube Music API
            println("YouTube Music API extractor for video ID: $videoId (Video)")
            val youtubeMusicResult = tryYouTubeMusicApi(videoId, isVideoRequest = true, targetHeight)
            if (youtubeMusicResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using YouTube Music API")
                return youtubeMusicResult.media
            } else if (youtubeMusicResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
            }

            // Fallback 2: Restricted / Age-Restricted Video Extractor
            println("Restricted extractor for video ID: $videoId (Video)")
            val restrictedResult = tryRestrictedVideoExtractor(videoId, isVideoRequest = true, targetHeight)
            if (restrictedResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using Restricted Video Extractor")
                return restrictedResult.media
            } else if (restrictedResult is ExtractionResult.Error) {
                errorReasons.add("Restricted Extractor: ${restrictedResult.reason}")
            }

            // Fallback 3: YtmKt
            println("YtmKt extractor for video ID: $videoId (Video)")
            val ytmKtResult = tryYtmKtEndpoint(videoId, isVideoRequest = true, targetHeight)
            if (ytmKtResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using YtmKt library")
                return ytmKtResult.media
            } else if (ytmKtResult is ExtractionResult.Error) {
                errorReasons.add("YtmKt: ${ytmKtResult.reason}")
            }
        } else {
            // Audio request cascade
            // 1. Primary: RealNewPipe extracts OPUS (160kbps, 70kbps, 50kbps) + AAC (128kbps, 48kbps) with dynamic JS deciphering (guaranteed HTTP 206)
            println("RealNewPipe extractor for video ID: $videoId (Audio - Opus/AAC)")
            val realNewPipeResult = tryRealNewPipeExtractor(videoId, isVideoRequest = false, targetHeight)
            if (realNewPipeResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using RealNewPipe (Opus/AAC)")
                return realNewPipeResult.media
            } else if (realNewPipeResult is ExtractionResult.Error) {
                errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
            }

            // 2. Fallback: YtmKt (also supports Opus)
            println("YtmKt extractor for video ID: $videoId (Audio)")
            val ytmKtResult = tryYtmKtEndpoint(videoId, isVideoRequest = false, targetHeight)
            if (ytmKtResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using YtmKt")
                return ytmKtResult.media
            } else if (ytmKtResult is ExtractionResult.Error) {
                errorReasons.add("YtmKt: ${ytmKtResult.reason}")
            }

            // 3. Fallback: Spoof clients with stream health verification
            val audioCascade = listOf(SpoofClient.ANDROID_VR, SpoofClient.IOS, SpoofClient.WEB_REMIX, SpoofClient.TV)
            for (client in audioCascade) {
                val res = trySpoofedExtractor(videoId, client, isVideoRequest = false, targetHeight)
                if (res is ExtractionResult.Success) {
                    println("Successfully loaded audio streamable using ${client.displayName}")
                    return res.media
                } else if (res is ExtractionResult.Error) {
                    errorReasons.add("${client.displayName}: ${res.reason}")
                }
            }

            // 4. Fallback: YouTube Music API (AAC)
            println("YouTube Music API extractor for video ID: $videoId (Audio - AAC fallback)")
            val youtubeMusicResult = tryYouTubeMusicApi(videoId, isVideoRequest = false, targetHeight)
            if (youtubeMusicResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using YouTube Music API")
                return youtubeMusicResult.media
            } else if (youtubeMusicResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
            }

            // 5. Fallback: Restricted / Age-Restricted Video Extractor
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
            "Failed to resolve streaming URLs for video $videoId:\n" + errorReasons.joinToString("\n")
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
                        // targetHeight == null: Expose ALL available video qualities
                        val sources = mutableListOf<Streamable.Source.Http>()

                        // 1. If HLS manifest available, add as "Auto"
                        val hlsUrl = streams.hlsUrl
                        if (!hlsUrl.isNullOrEmpty()) {
                            println("RealNewPipe: Adding HLS master playlist as Auto")
                            sources.add(createHlsSource(hlsUrl))
                        }

                        // 2. Add all distinct progressive video qualities (1080p, 720p, 480p, 360p, 240p, 144p)
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

                        distinctVideos.forEach { vFormat ->
                            val h = HEIGHT_REGEX.find(vFormat.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 360
                            sources.add(
                                Streamable.Source.Http(
                                    request = createStreamingRequest(vFormat.url!!),
                                    type = Streamable.SourceType.Progressive,
                                    quality = h,
                                    title = "${h}p",
                                    isVideo = true
                                )
                            )
                        }

                        if (sources.isNotEmpty()) {
                            println("RealNewPipe: Returning ${sources.size} video qualities: ${sources.map { it.title }}")
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
                isMusicOrigin = client == SpoofClient.WEB_REMIX
            )
        } catch (e: Exception) {
            println("SpoofClientExtractor (${client.displayName}) Exception: ${e.message}")
            ExtractionResult.Error(e.message ?: "${client.displayName} extraction failed")
        }
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
            println("RestrictedVideoExtractor: Trying web_creator / restricted player for $videoId")
            val videoResponse = videoEndpoint.getRestrictedVideo(videoId)
                ?: return ExtractionResult.Error("Restricted player returned no response")
            extractMediaFromFormatResponse(videoId, videoResponse, isVideoRequest, targetHeight, isMusicOrigin = false)
        } catch (e: Exception) {
            println("RestrictedVideoExtractor Exception: ${e.message}")
            ExtractionResult.Error(e.message ?: "Restricted extraction failed")
        }
    }

    private fun extractMediaFromFormatResponse(
        videoId: String,
        videoResponse: YoutubeFormatResponse,
        isVideoRequest: Boolean,
        targetHeight: Int? = null,
        isMusicOrigin: Boolean = false
    ): ExtractionResult {
        val streamingData = videoResponse.streamingData
            ?: return ExtractionResult.Error("Player response contains no streaming data")

        // 1. Try HLS / DASH manifest if present for Video
        val hlsUrl = streamingData.hlsManifestUrl ?: streamingData.dashManifestUrl
        if (isVideoRequest && !hlsUrl.isNullOrEmpty()) {
            println("YouTubeStreamResolver: Using HLS master playlist for video ($hlsUrl)")
            return ExtractionResult.Success(
                Streamable.Media.Server(listOf(createHlsSource(hlsUrl, isMusicOrigin)), merged = false)
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
                    request = createStreamingRequest(selectedVideo.url, isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = h,
                    title = "${h}p",
                    isVideo = true
                )
                val audioBitrate = normalizeAudioBitrate(bestAudio.itag, bestAudio.bitrate)
                val audioSource = Streamable.Source.Http(
                    request = createStreamingRequest(bestAudio.url, isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = audioBitrate,
                    title = null,
                    isVideo = false
                )
                println("YouTubeStreamResolver: Video request (targetHeight=$targetHeight). Selected: ${videoSource.title} (itag=${selectedVideo.itag}), audio: itag=${bestAudio.itag} (${audioBitrate}kbps). Returning merged video+audio.")
                if (!isStreamPlayable(selectedVideo.url) || !isStreamPlayable(bestAudio.url)) {
                    println("YouTubeStreamResolver: Extracted video/audio stream URL failed health check (HTTP 403 Forbidden). Rejecting extractor result.")
                    return ExtractionResult.Error("Video stream failed health check (HTTP 403 Forbidden)")
                }
                return ExtractionResult.Success(Streamable.Media.Server(listOf(videoSource, audioSource), merged = true))
            } else if (distinctVideoFormats.isNotEmpty()) {
                // Return all available playable video qualities
                val playableVideos = distinctVideoFormats.filter { isStreamPlayable(it.url) }
                if (playableVideos.isNotEmpty()) {
                    val sources = playableVideos.map { format ->
                        val h = format.height!!.toInt()
                        Streamable.Source.Http(
                            request = createStreamingRequest(format.url, isMusicOrigin = isMusicOrigin),
                            type = Streamable.SourceType.Progressive,
                            quality = h,
                            title = "${h}p",
                            isVideo = true
                        )
                    }
                    println("YouTubeStreamResolver: Video request (targetHeight=null). Returning ${sources.size} playable video qualities: ${sources.map { it.title }}")
                    return ExtractionResult.Success(Streamable.Media.Server(sources, merged = false))
                }
            }

            // Fallback: Muxed formats if separate streams unavailable and playable
            val muxedFormats = resolved.filter { ((it.itag == 18L || it.itag == 22L) || (it.isVideo && it.mimeType.contains("mp4a"))) && isStreamPlayable(it.url) }
            if (muxedFormats.isNotEmpty()) {
                val muxed = muxedFormats.first()
                val h = muxed.height?.toInt() ?: if (muxed.itag == 18L) 360 else 720
                val muxedSource = Streamable.Source.Http(
                    request = createStreamingRequest(muxed.url, isMusicOrigin = isMusicOrigin),
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

        // Audio extraction: include ALL playable audio qualities so the user can choose in the Source panel.
        val adaptiveAudio = resolved.filter { it.isAudio && it.itag != 18L && isStreamPlayable(it.url) }.sortedByDescending {
            normalizeAudioBitrate(it.itag, it.bitrate)
        }
        val muxedAudio = resolved.firstOrNull { it.itag == 18L && isStreamPlayable(it.url) }
        println("YouTubeStreamResolver: Audio extraction for $videoId: ${adaptiveAudio.size} adaptive audio formats, muxed format 18 playable: ${muxedAudio != null}")

        val sources = mutableListOf<Streamable.Source.Http>()

        // Add adaptive audio qualities (Opus 160k, AAC 128k, Opus 70k, Opus 50k, etc.)
        adaptiveAudio.forEach { format ->
            val kbps = normalizeAudioBitrate(format.itag, format.bitrate)
            sources.add(
                Streamable.Source.Http(
                    request = createStreamingRequest(format.url, isMusicOrigin = isMusicOrigin),
                    type = Streamable.SourceType.Progressive,
                    quality = kbps,
                    title = null,
                    isVideo = false
                )
            )
        }

        // Also add progressive muxed format 18 (AAC 128kbps) if playable and not already added
        if (muxedAudio != null && sources.none { it.quality == 128 }) {
            sources.add(
                Streamable.Source.Http(
                    request = createStreamingRequest(muxedAudio.url, isMusicOrigin = isMusicOrigin),
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
