package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.YtmKtVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.math.abs

class YouTubeStreamResolver(
    private val api: YoutubeiApi,
    @Suppress("unused") private val settings: Settings,
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
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
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

        // 2. Fallback to YtmKt
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
        
        if (isVideoRequest) {
            // For Video: Tier 1 YouTube Music API (iOS client) provides the HLS master playlist
            // with all video qualities (1080p, 720p, etc.) + synchronized audio!
            println("YouTube Music API extractor for video ID: $videoId (Video)")
            val youtubeMusicResult = tryYouTubeMusicApi(videoId, isVideoRequest = true, targetHeight)
            if (youtubeMusicResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using YouTube Music API")
                return youtubeMusicResult.media
            } else if (youtubeMusicResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
            }

            // Fallback 1: RealNewPipe
            println("RealNewPipe extractor for video ID: $videoId (Video)")
            val realNewPipeResult = tryRealNewPipeExtractor(videoId, isVideoRequest = true, targetHeight)
            if (realNewPipeResult is ExtractionResult.Success) {
                println("Successfully loaded video streamable using RealNewPipe extractor")
                return realNewPipeResult.media
            } else if (realNewPipeResult is ExtractionResult.Error) {
                errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
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
            // For Audio: RealNewPipe extracts OPUS (160kbps, 70kbps, 50kbps) + AAC (128kbps, 48kbps)
            println("RealNewPipe extractor for video ID: $videoId (Audio - Opus/AAC)")
            val realNewPipeResult = tryRealNewPipeExtractor(videoId, isVideoRequest = false, targetHeight)
            if (realNewPipeResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using RealNewPipe (Opus/AAC)")
                return realNewPipeResult.media
            } else if (realNewPipeResult is ExtractionResult.Error) {
                errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
            }

            // Fallback 1: YtmKt (also supports Opus)
            println("YtmKt extractor for video ID: $videoId (Audio)")
            val ytmKtResult = tryYtmKtEndpoint(videoId, isVideoRequest = false, targetHeight)
            if (ytmKtResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using YtmKt")
                return ytmKtResult.media
            } else if (ytmKtResult is ExtractionResult.Error) {
                errorReasons.add("YtmKt: ${ytmKtResult.reason}")
            }

            // Fallback 2: YouTube Music API (AAC)
            println("YouTube Music API extractor for video ID: $videoId (Audio - AAC fallback)")
            val youtubeMusicResult = tryYouTubeMusicApi(videoId, isVideoRequest = false, targetHeight)
            if (youtubeMusicResult is ExtractionResult.Success) {
                println("Successfully loaded audio streamable using YouTube Music API")
                return youtubeMusicResult.media
            } else if (youtubeMusicResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
            }

            // Fallback 3: Restricted / Age-Restricted Video Extractor
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

                    // 1. Try HLS manifest if available from NewPipe
                    val hlsUrl = streams.hlsUrl
                    if (!hlsUrl.isNullOrEmpty()) {
                        println("RealNewPipe: Using HLS master playlist for video")
                        return ExtractionResult.Success(
                            Streamable.Media.Server(listOf(createHlsSource(hlsUrl)), merged = false)
                        )
                    }

                    // 2. Fallback: Progressive video + audio merged
                    val reqHeight = targetHeight ?: 720
                    val selectedVideo = streams.videoOnly.minByOrNull { format ->
                        val h = HEIGHT_REGEX.find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        abs(h - reqHeight)
                    } ?: streams.videoOnly.firstOrNull() ?: streams.muxed.firstOrNull()
                    val bestAudio = streams.audio.firstOrNull()

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
            val streamingData = video.streamingData ?: return ExtractionResult.Error("No streaming data")

            if (isVideoRequest) {
                // 1. Try HLS manifest (Auto master playlist enables all resolutions with audio in Echo's Video Selection)
                val hlsUrl = streamingData.hlsManifestUrl
                if (!hlsUrl.isNullOrEmpty()) {
                    println("YouTube Music API: Using HLS master playlist for video")
                    return ExtractionResult.Success(
                        Streamable.Media.Server(listOf(createHlsSource(hlsUrl)), merged = false)
                    )
                }

                // 2. Fallback: Progressive video + audio merged
                val videoFormats = streamingData.adaptiveFormats.filter {
                    it.mimeType.lowercase().contains("video/") && it.url != null
                }
                val audioFormats = streamingData.adaptiveFormats.filter {
                    it.mimeType.lowercase().contains("audio/") && it.url != null
                }
                val reqHeight = targetHeight ?: 720
                val selectedVideo = videoFormats.minByOrNull {
                    abs((it.height?.toInt() ?: 0) - reqHeight)
                } ?: videoFormats.firstOrNull()
                val bestAudio = audioFormats.maxByOrNull { it.bitrate } ?: audioFormats.firstOrNull()

                if (selectedVideo?.url != null && bestAudio?.url != null) {
                    val h = selectedVideo.height?.toInt() ?: reqHeight
                    val videoSource = Streamable.Source.Http(
                        request = createStreamingRequest(selectedVideo.url!!),
                        type = Streamable.SourceType.Progressive,
                        quality = h,
                        title = "${h}p",
                        isVideo = true
                    )
                    val audioSource = Streamable.Source.Http(
                        request = createStreamingRequest(bestAudio.url!!),
                        type = Streamable.SourceType.Progressive,
                        quality = if (bestAudio.bitrate > 0) bestAudio.bitrate / 1000 else 128
                    )
                    return ExtractionResult.Success(Streamable.Media.Server(listOf(videoSource, audioSource), merged = true))
                }
            }

            // Audio extraction (or fallback if video not available)
            // Return ALL audio qualities so the user can choose in the Source panel
            val audioFormats = streamingData.adaptiveFormats
                .filter { it.mimeType.lowercase().contains("audio/") && it.url != null }
                .sortedByDescending { it.bitrate }

            if (audioFormats.isEmpty()) {
                return ExtractionResult.Error("No audio formats")
            }

            val allSources = audioFormats.map { format ->
                Streamable.Source.Http(
                    request = createStreamingRequest(format.url!!),
                    type = Streamable.SourceType.Progressive,
                    quality = if (format.bitrate > 0) format.bitrate / 1000 else 128
                )
            }

            return ExtractionResult.Success(Streamable.Media.Server(allSources, merged = false))
        } catch (e: Exception) {
            return ExtractionResult.Error(e.message ?: "Unknown error")
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
            val streamingData = videoResponse.streamingData
                ?: return ExtractionResult.Error("Restricted player returned no streaming data")

            val allFormats = streamingData.formats + streamingData.adaptiveFormats
            if (allFormats.isEmpty()) {
                return ExtractionResult.Error("No formats in restricted player response")
            }

            // Resolve direct URLs by deciphering signatures if necessary
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

            println("RestrictedVideoExtractor: Deciphered ${resolved.size} of ${allFormats.size} formats:")
            resolved.forEach {
                println("  itag=${it.itag}, height=${it.height}p, isVideo=${it.isVideo}, isAudio=${it.isAudio}, bitrate=${it.bitrate / 1000}k, mime=${it.mimeType}")
            }

            // 1. Try HLS / DASH manifest if present
            val hlsUrl = streamingData.hlsManifestUrl ?: streamingData.dashManifestUrl
            if (isVideoRequest && !hlsUrl.isNullOrEmpty()) {
                println("RestrictedVideoExtractor: Using HLS/DASH master playlist for video")
                return ExtractionResult.Success(
                    Streamable.Media.Server(listOf(createHlsSource(hlsUrl, isMusicOrigin = false)), merged = false)
                )
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

                val muxedFormats = resolved.filter { (it.itag == 18L || it.itag == 22L) || (it.isVideo && it.mimeType.contains("mp4a")) }
                val preferMuxed = (targetHeight == null || targetHeight <= 360) && muxedFormats.isNotEmpty()

                if (preferMuxed) {
                    val muxed = muxedFormats.first()
                    val h = muxed.height?.toInt() ?: if (muxed.itag == 18L) 360 else 720
                    val muxedSource = Streamable.Source.Http(
                        request = createStreamingRequest(muxed.url, isMusicOrigin = false),
                        type = Streamable.SourceType.Progressive,
                        quality = h,
                        title = "${h}p",
                        isVideo = true
                    )
                    println("RestrictedVideoExtractor: Video request (targetHeight=$targetHeight). Selected progressive muxed: ${muxedSource.title} (itag=${muxed.itag}). Returning single stream.")
                    return ExtractionResult.Success(Streamable.Media.Server(listOf(muxedSource), merged = false))
                }

                if (distinctVideoFormats.isNotEmpty() && bestAudio != null) {
                    val reqHeight = targetHeight ?: 720
                    val selectedVideo = distinctVideoFormats.minByOrNull {
                        abs(it.height!!.toInt() - reqHeight)
                    } ?: distinctVideoFormats.first()

                    val h = selectedVideo.height!!.toInt()
                    val videoSource = Streamable.Source.Http(
                        request = createStreamingRequest(selectedVideo.url, isMusicOrigin = false),
                        type = Streamable.SourceType.Progressive,
                        quality = h,
                        title = "${h}p",
                        isVideo = true
                    )
                    val audioBitrate = normalizeAudioBitrate(bestAudio.itag, bestAudio.bitrate)
                    val audioSource = Streamable.Source.Http(
                        request = createStreamingRequest(bestAudio.url, isMusicOrigin = false),
                        type = Streamable.SourceType.Progressive,
                        quality = audioBitrate,
                        title = null,
                        isVideo = false
                    )
                    println("RestrictedVideoExtractor: Video request (targetHeight=$targetHeight). Selected: ${videoSource.title} (itag=${selectedVideo.itag}), audio: itag=${bestAudio.itag} (${audioBitrate}kbps). Returning merged video+audio.")
                    return ExtractionResult.Success(Streamable.Media.Server(listOf(videoSource, audioSource), merged = true))
                }

                // Fallback: Muxed formats if separate streams unavailable
                if (muxedFormats.isNotEmpty()) {
                    val muxed = muxedFormats.first()
                    val h = muxed.height?.toInt() ?: if (muxed.itag == 18L) 360 else 720
                    val muxedSource = Streamable.Source.Http(
                        request = createStreamingRequest(muxed.url, isMusicOrigin = false),
                        type = Streamable.SourceType.Progressive,
                        quality = h,
                        title = "${h}p",
                        isVideo = true
                    )
                    println("RestrictedVideoExtractor: Returning muxed video source: ${muxedSource.title} (itag=${muxed.itag})")
                    return ExtractionResult.Success(Streamable.Media.Server(listOf(muxedSource), merged = false))
                }
            }

            // Audio extraction: include ALL audio qualities so the user can choose in the Source panel.
            // Standardize nominal bitrates: itag 251 -> 160k, itag 140 -> 128k, itag 250 -> 70k, itag 249 -> 50k.
            val adaptiveAudio = resolved.filter { it.isAudio && it.itag != 18L }.sortedByDescending {
                normalizeAudioBitrate(it.itag, it.bitrate)
            }
            val muxedAudio = resolved.firstOrNull { it.itag == 18L }
            println("RestrictedVideoExtractor: Audio extraction for $videoId: ${adaptiveAudio.size} adaptive audio formats, muxed format 18 present: ${muxedAudio != null}")

            val sources = mutableListOf<Streamable.Source.Http>()

            // Progressive muxed format 18 (AAC 128kbps) is the only format that Google's CDN allows
            // open-ended streaming for on restricted web endpoints without returning 403 Forbidden.
            if (muxedAudio != null) {
                sources.add(
                    Streamable.Source.Http(
                        request = createStreamingRequest(muxedAudio.url, isMusicOrigin = false),
                        type = Streamable.SourceType.Progressive,
                        quality = 128,
                        title = null,
                        isVideo = false
                    )
                )
            } else {
                // If muxed format is not available, provide adaptive options
                adaptiveAudio.forEach { format ->
                    val kbps = normalizeAudioBitrate(format.itag, format.bitrate)
                    sources.add(
                        Streamable.Source.Http(
                            request = createStreamingRequest(format.url, isMusicOrigin = false),
                            type = Streamable.SourceType.Progressive,
                            quality = kbps,
                            title = null,
                            isVideo = false
                        )
                    )
                }
            }

            if (sources.isEmpty() && resolved.isNotEmpty()) {
                val fallback = resolved.first()
                sources.add(
                    Streamable.Source.Http(
                        request = createStreamingRequest(fallback.url, isMusicOrigin = false),
                        type = Streamable.SourceType.Progressive,
                        quality = 128,
                        title = null,
                        isVideo = false
                    )
                )
            }

            println("RestrictedVideoExtractor: Returning ${sources.size} audio qualities: ${sources.map { "Quality ${it.quality}" }}")
            return ExtractionResult.Success(Streamable.Media.Server(sources, merged = false))
        } catch (e: Exception) {
            println("RestrictedVideoExtractor Exception: ${e.message}")
            ExtractionResult.Error(e.message ?: "Restricted extraction failed")
        }
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
