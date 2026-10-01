package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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

    private val hlsHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun createHlsSource(masterUrl: String): Streamable.Source.Http {
        return Streamable.Source.Http(
            request = createStreamingRequest(masterUrl),
            type = Streamable.SourceType.HLS,
            quality = 1080,
            title = "Auto",
            isVideo = true
        )
    }
    

    private fun createStreamingRequest(url: String): NetworkRequest {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Range" to "bytes=0-",
            "Origin" to "https://music.youtube.com",
            "Referer" to "https://music.youtube.com/"
        )
        val cookie = RealNewPipeVideoFormatsEndpoint.globalCookie
        if (!cookie.isNullOrBlank()) {
            headers["Cookie"] = cookie
        }
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
        object Failed : ExtractionResult()
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
                        val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        Math.abs(h - reqHeight)
                    } ?: streams.videoOnly.firstOrNull() ?: streams.muxed.firstOrNull()
                    val bestAudio = streams.audio.firstOrNull()

                    if (selectedVideo?.url != null && bestAudio?.url != null) {
                        val actualHeight = Regex("(\\d+)p").find(selectedVideo.mimeType)
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
                        val h = Regex("(\\d+)p").find(format.mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        Math.abs(h - reqHeight)
                    } ?: videoFormats.firstOrNull()
                    val bestAudio = audioFormats.firstOrNull()

                    if (selectedVideo?.url != null && bestAudio?.url != null) {
                        val actualHeight = Regex("(\\d+)p").find(selectedVideo.mimeType)
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
                    Math.abs((it.height?.toInt() ?: 0) - reqHeight)
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

            if (isVideoRequest) {
                val reqHeight = targetHeight ?: 720
                // Check for muxed formats (like format 18: video/mp4)
                val muxed = resolved.firstOrNull { it.isVideo && !it.isAudio && it.mimeType.contains("mp4a") }
                    ?: resolved.firstOrNull { it.isVideo && it.itag == 18L }
                if (muxed != null) {
                    val h = muxed.height?.toInt() ?: reqHeight
                    val muxedSource = Streamable.Source.Http(
                        request = createStreamingRequest(muxed.url),
                        type = Streamable.SourceType.Progressive,
                        quality = h,
                        title = "${h}p",
                        isVideo = true
                    )
                    return ExtractionResult.Success(Streamable.Media.Server(listOf(muxedSource), merged = false))
                }

                val videos = resolved.filter { it.isVideo }
                val audios = resolved.filter { it.isAudio }
                val selectedVideo = videos.minByOrNull { Math.abs((it.height?.toInt() ?: 0) - reqHeight) }
                    ?: videos.firstOrNull()
                val bestAudio = audios.maxByOrNull { it.bitrate } ?: audios.firstOrNull()

                if (selectedVideo != null && bestAudio != null) {
                    val h = selectedVideo.height?.toInt() ?: reqHeight
                    val videoSource = Streamable.Source.Http(
                        request = createStreamingRequest(selectedVideo.url),
                        type = Streamable.SourceType.Progressive,
                        quality = h,
                        title = "${h}p",
                        isVideo = true
                    )
                    val audioSource = Streamable.Source.Http(
                        request = createStreamingRequest(bestAudio.url),
                        type = Streamable.SourceType.Progressive,
                        quality = if (bestAudio.bitrate > 0) bestAudio.bitrate / 1000 else 128
                    )
                    return ExtractionResult.Success(Streamable.Media.Server(listOf(videoSource, audioSource), merged = true))
                }
            }

            // Audio extraction: return all available audio formats so user can choose quality
            val audioFormats = resolved.filter { it.isAudio }.sortedByDescending { it.bitrate }
            val allSources = if (audioFormats.isNotEmpty()) {
                audioFormats.map { format ->
                    Streamable.Source.Http(
                        request = createStreamingRequest(format.url),
                        type = Streamable.SourceType.Progressive,
                        quality = if (format.bitrate > 0) format.bitrate / 1000 else 128
                    )
                }
            } else {
                val fallback = resolved.firstOrNull { it.itag == 18L } ?: resolved.first()
                listOf(
                    Streamable.Source.Http(
                        request = createStreamingRequest(fallback.url),
                        type = Streamable.SourceType.Progressive,
                        quality = if (fallback.bitrate > 0) fallback.bitrate / 1000 else 128
                    )
                )
            }

            ExtractionResult.Success(Streamable.Media.Server(allSources, merged = false))
        } catch (e: Exception) {
            println("RestrictedVideoExtractor Exception: ${e.message}")
            ExtractionResult.Error(e.message ?: "Restricted extraction failed")
        }
    }

    private fun deobfuscateCipherUrl(videoId: String, cipherString: String): String? {
        return runCatching {
            val params = cipherString.split('&').associate {
                val parts = it.split('=', limit = 2)
                parts[0] to (if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else "")
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
