package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat

class YouTubeStreamResolver(
    private val api: YoutubeiApi,
    private val settings: Settings,
    private val videoEndpoint: dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint,
    private val visitorEndpoint: dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
) {
    private fun getRealNewPipe() = RealNewPipeVideoFormatsEndpoint()
    
    private val ytmKtEndpoint by lazy {
        dev.brahmkshatriya.echo.extension.YtmKtVideoFormatsEndpoint(api)
    }
    

    private fun createStreamingRequest(url: String): NetworkRequest {
        return NetworkRequest(
            url = url,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*",
                "Range" to "bytes=0-"
            )
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
                val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                h == targetHeight
            }
            if (muxedMatch?.url != null) {
                muxedMatch.url
            } else {
                val separate = getRealNewPipe().getSeparateStreams(videoId, null).getOrNull()
                val videoMatch = separate?.first?.minByOrNull { format ->
                    val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    Math.abs(h - targetHeight)
                } ?: separate?.first?.firstOrNull()
                videoMatch?.url ?: muxedStreams.firstOrNull()?.url
            }
        }.getOrNull()

        // 2. Fallback to YtmKt
        val backgroundUrl = realNewPipeUrl ?: runCatching {
            val muxedStreams = ytmKtEndpoint.getMuxedVideoStreams(videoId).getOrNull() ?: emptyList()
            val muxedMatch = muxedStreams.firstOrNull { format ->
                val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                h == targetHeight
            }
            if (muxedMatch?.url != null) {
                muxedMatch.url
            } else {
                val separate = ytmKtEndpoint.getSeparateStreams(videoId, null).getOrNull()
                val videoMatch = separate?.first?.minByOrNull { format ->
                    val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    Math.abs(h - targetHeight)
                } ?: separate?.first?.firstOrNull()
                videoMatch?.url ?: muxedStreams.firstOrNull()?.url
            }
        }.getOrNull() ?: throw Exception("Failed to resolve background video for $videoId at ${targetHeight}p")

        return Streamable.Media.Background(createStreamingRequest(backgroundUrl))
    }
    
    private suspend fun resolveStreamableInternal(
        streamable: Streamable?,
        videoId: String
    ): Streamable.Media {
        val streamType = streamable?.extras?.get("type")
        val isVideoRequest = streamType == "video"
        val targetHeight = streamable?.extras?.get("height")?.toIntOrNull()
        val targetCodec = streamable?.extras?.get("codec")
        val targetBitrate = streamable?.extras?.get("bitrate")?.toIntOrNull()

        val errorReasons = mutableListOf<String>()
        
        // Tier 1: YouTube Music Native API (IOS client) - fastest and direct unthrottled streaming URLs (~250ms)
        println("YouTube Music API extractor for video ID: $videoId, isVideoRequest: $isVideoRequest")
        val youtubeMusicResult = tryYouTubeMusicApi(videoId, isVideoRequest, targetHeight)
        if (youtubeMusicResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using YouTube Music API in record time")
            return youtubeMusicResult.media
        } else if (youtubeMusicResult is ExtractionResult.Error) {
            errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
        }

        // Tier 2: RealNewPipe
        println("RealNewPipe extractor for video ID: $videoId, isVideoRequest: $isVideoRequest")
        val realNewPipeResult = tryRealNewPipeExtractor(videoId, isVideoRequest, targetHeight, targetCodec, targetBitrate)
        if (realNewPipeResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using RealNewPipe extractor")
            return realNewPipeResult.media
        } else if (realNewPipeResult is ExtractionResult.Error) {
            errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
        }
        
        // Tier 3: YtmKt MultipleVideoFormatsEndpoint
        println("RealNewPipe failed, trying YtmKt for video ID: $videoId")
        val ytmKtResult = tryYtmKtEndpoint(videoId, isVideoRequest, targetHeight, targetCodec, targetBitrate)
        if (ytmKtResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using YtmKt library")
            return ytmKtResult.media
        } else if (ytmKtResult is ExtractionResult.Error) {
            errorReasons.add("YtmKt: ${ytmKtResult.reason}")
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
        object Failed : ExtractionResult()
    }

    private fun cleanAudioTitle(format: YoutubeVideoFormat): String {
        val bitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
        val codec = when {
            format.mimeType.contains("opus", true) -> "Opus"
            format.mimeType.contains("mp4a", true) || format.mimeType.contains("aac", true) || format.mimeType.contains("m4a", true) -> "AAC"
            else -> "Audio"
        }
        return "$codec $bitrateKbps kbps"
    }

    private fun cleanVideoTitle(format: YoutubeVideoFormat, defaultHeight: Int): String {
        val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: defaultHeight
        return "Video ${h}p"
    }

    private suspend fun tryRealNewPipeExtractor(
        videoId: String,
        isVideoRequest: Boolean,
        targetHeight: Int?,
        targetCodec: String?,
        targetBitrate: Int?
    ): ExtractionResult {
        return try {
            if (isVideoRequest) {
                val reqHeight = targetHeight ?: 720

                // Single StreamInfo.getInfo call — get muxed + video-only + audio at once
                val allResult = getRealNewPipe().getAllStreams(videoId)
                if (allResult.isSuccess) {
                    val (allMuxed, videoFormats, audioFormats) = allResult.getOrThrow()

                    val matchingMuxed = allMuxed.firstOrNull { format ->
                        val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        h == reqHeight
                    } ?: if (targetHeight == null) allMuxed.firstOrNull() else null

                    if (matchingMuxed?.url != null) {
                        val muxedSource = Streamable.Source.Http(
                            request = createStreamingRequest(matchingMuxed.url!!),
                            type = Streamable.SourceType.Progressive,
                            quality = if (matchingMuxed.bitrate > 0) matchingMuxed.bitrate / 1000 else 0,
                            title = cleanVideoTitle(matchingMuxed, reqHeight)
                        )
                        return ExtractionResult.Success(Streamable.Media.Server(listOf(muxedSource), merged = false))
                    }

                    val selectedVideo = videoFormats.minByOrNull { format ->
                        val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        Math.abs(h - reqHeight)
                    } ?: videoFormats.firstOrNull()
                    val bestAudio = audioFormats.firstOrNull()

                    if (selectedVideo?.url != null && bestAudio?.url != null) {
                        val videoSource = Streamable.Source.Http(
                            request = createStreamingRequest(selectedVideo.url!!),
                            type = Streamable.SourceType.Progressive,
                            quality = if (selectedVideo.bitrate > 0) selectedVideo.bitrate / 1000 else 0,
                            title = cleanVideoTitle(selectedVideo, reqHeight)
                        )
                        val audioSource = Streamable.Source.Http(
                            request = createStreamingRequest(bestAudio.url!!),
                            type = Streamable.SourceType.Progressive,
                            quality = if (bestAudio.bitrate > 0) bestAudio.bitrate / 1000 else 0,
                            title = cleanAudioTitle(bestAudio)
                        )
                        return ExtractionResult.Success(Streamable.Media.Server(listOf(videoSource, audioSource), merged = true))
                    }
                }
            }

            // Audio extraction (or fallback if video failed)
            println("RealNewPipe: Extracting audio streams for $videoId")
            val audioResult = getRealNewPipe().getAudioFormats(videoId)
            if (audioResult.isFailure) {
                return ExtractionResult.Failed
            }

            val rawAudioFormats = audioResult.getOrThrow()
            if (rawAudioFormats.isEmpty()) {
                return ExtractionResult.Failed
            }

            // Sort so the selected codec & bitrate comes first if specified
            val sortedAudioFormats = rawAudioFormats.sortedWith(
                compareByDescending<YoutubeVideoFormat> { format ->
                    if (targetCodec != null && format.mimeType.contains(targetCodec, ignoreCase = true)) 100 else 0
                }.thenByDescending { format ->
                    val kbps = format.bitrate / 1000
                    if (targetBitrate != null) -Math.abs(kbps - targetBitrate) else kbps
                }
            )

            // When a specific audio quality is chosen (or default), return only that single stream
            // so the user does not get a cluttered list of redundant streams under "Sources"
            val selectedFormat = sortedAudioFormats.first()
            val singleSource = Streamable.Source.Http(
                request = createStreamingRequest(selectedFormat.url!!),
                type = Streamable.SourceType.Progressive,
                quality = if (selectedFormat.bitrate > 0) selectedFormat.bitrate / 1000 else 0,
                title = cleanAudioTitle(selectedFormat)
            )

            return ExtractionResult.Success(Streamable.Media.Server(listOf(singleSource), merged = false))
        } catch (e: Exception) {
            println("RealNewPipe Exception: ${e.message}")
            val errorMsg = e.message ?: "Unknown error"
            return ExtractionResult.Error(errorMsg)
        }
    }

    private suspend fun tryYtmKtEndpoint(
        videoId: String,
        isVideoRequest: Boolean,
        targetHeight: Int?,
        targetCodec: String?,
        targetBitrate: Int?
    ): ExtractionResult {
        return try {
            if (isVideoRequest) {
                val reqHeight = targetHeight ?: 720
                val muxedResult = ytmKtEndpoint.getMuxedVideoStreams(videoId)
                val allMuxed = muxedResult.getOrNull() ?: emptyList()

                val matchingMuxed = allMuxed.firstOrNull { format ->
                    val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    h == reqHeight
                } ?: if (targetHeight == null) allMuxed.firstOrNull() else null

                if (matchingMuxed?.url != null) {
                    val muxedSource = Streamable.Source.Http(
                        request = createStreamingRequest(matchingMuxed.url!!),
                        type = Streamable.SourceType.Progressive,
                        quality = if (matchingMuxed.bitrate > 0) matchingMuxed.bitrate / 1000 else 0,
                        title = cleanVideoTitle(matchingMuxed, reqHeight)
                    )
                    return ExtractionResult.Success(Streamable.Media.Server(listOf(muxedSource), merged = false))
                }

                val separateResult = ytmKtEndpoint.getSeparateStreams(videoId, maxQuality = null)
                if (separateResult.isSuccess) {
                    val (videoFormats, audioFormats) = separateResult.getOrThrow()
                    val selectedVideo = videoFormats.minByOrNull { format ->
                        val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        Math.abs(h - reqHeight)
                    } ?: videoFormats.firstOrNull()
                    val bestAudio = audioFormats.firstOrNull()

                    if (selectedVideo?.url != null && bestAudio?.url != null) {
                        val videoSource = Streamable.Source.Http(
                            request = createStreamingRequest(selectedVideo.url!!),
                            type = Streamable.SourceType.Progressive,
                            quality = if (selectedVideo.bitrate > 0) selectedVideo.bitrate / 1000 else 0,
                            title = cleanVideoTitle(selectedVideo, reqHeight)
                        )
                        val audioSource = Streamable.Source.Http(
                            request = createStreamingRequest(bestAudio.url!!),
                            type = Streamable.SourceType.Progressive,
                            quality = if (bestAudio.bitrate > 0) bestAudio.bitrate / 1000 else 0,
                            title = cleanAudioTitle(bestAudio)
                        )
                        return ExtractionResult.Success(Streamable.Media.Server(listOf(videoSource, audioSource), merged = true))
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

            val sortedAudio = rawAudio.sortedWith(
                compareByDescending<YoutubeVideoFormat> { format ->
                    if (targetCodec != null && format.mimeType.contains(targetCodec, ignoreCase = true)) 100 else 0
                }.thenByDescending { format ->
                    val kbps = format.bitrate / 1000
                    if (targetBitrate != null) -Math.abs(kbps - targetBitrate) else kbps
                }
            )

            val selectedFormat = sortedAudio.first()
            val singleSource = Streamable.Source.Http(
                request = createStreamingRequest(selectedFormat.url!!),
                type = Streamable.SourceType.Progressive,
                quality = if (selectedFormat.bitrate > 0) selectedFormat.bitrate / 1000 else 0,
                title = cleanAudioTitle(selectedFormat)
            )

            return ExtractionResult.Success(Streamable.Media.Server(listOf(singleSource), merged = false))
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
            val adaptiveFormats = streamingData.adaptiveFormats

            if (isVideoRequest) {
                val videoFormats = adaptiveFormats.filter {
                    it.mimeType.lowercase().contains("video/") && it.url != null
                }
                val audioFormats = adaptiveFormats.filter {
                    it.mimeType.lowercase().contains("audio/") && it.url != null
                }
                val reqHeight = targetHeight ?: 720
                val selectedVideo = videoFormats.minByOrNull {
                    Math.abs((it.height?.toInt() ?: 0) - reqHeight)
                } ?: videoFormats.firstOrNull()
                val bestAudio = audioFormats.maxByOrNull { it.bitrate } ?: audioFormats.firstOrNull()

                if (selectedVideo?.url != null && bestAudio?.url != null) {
                    val videoSource = Streamable.Source.Http(
                        request = createStreamingRequest(selectedVideo.url!!),
                        type = Streamable.SourceType.Progressive,
                        quality = (selectedVideo.height?.toInt() ?: reqHeight),
                        title = "Video ${selectedVideo.height ?: reqHeight}p"
                    )
                    val audioBitrate = if (bestAudio.bitrate > 0) bestAudio.bitrate / 1000 else 128
                    val audioSource = Streamable.Source.Http(
                        request = createStreamingRequest(bestAudio.url!!),
                        type = Streamable.SourceType.Progressive,
                        quality = audioBitrate,
                        title = "Audio $audioBitrate kbps"
                    )
                    return ExtractionResult.Success(Streamable.Media.Server(listOf(videoSource, audioSource), merged = true))
                }
            }

            // Audio extraction (or fallback if video not available)
            val audioFormats = adaptiveFormats.filter {
                it.mimeType.lowercase().contains("audio/") && it.url != null
            }
            if (audioFormats.isEmpty()) {
                return ExtractionResult.Error("No audio formats")
            }

            val selectedFormat = audioFormats.maxByOrNull { it.bitrate } ?: audioFormats.first()
            val bitrateKbps = if (selectedFormat.bitrate > 0) selectedFormat.bitrate / 1000 else 128
            val singleSource = Streamable.Source.Http(
                request = createStreamingRequest(selectedFormat.url!!),
                type = Streamable.SourceType.Progressive,
                quality = bitrateKbps,
                title = "Audio $bitrateKbps kbps"
            )

            return ExtractionResult.Success(Streamable.Media.Server(listOf(singleSource), merged = false))
        } catch (e: Exception) {
            return ExtractionResult.Error(e.message ?: "Unknown error")
        }
    }
}
