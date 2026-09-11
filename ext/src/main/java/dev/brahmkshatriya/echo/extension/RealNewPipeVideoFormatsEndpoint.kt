package dev.brahmkshatriya.echo.extension

import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit


class RealNewPipeVideoFormatsEndpoint {

    companion object {
        @Volatile
        private var initialized = false
        
        private const val OPUS_DEFAULT_BITRATE = 160000  
        private const val AAC_DEFAULT_BITRATE = 128000  
        private const val FALLBACK_BITRATE = 128000      
        
        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun ensureInitialized() {
            if (initialized) return
            synchronized(this) {
                if (initialized) return

                NewPipe.init(object : Downloader() {
                    override fun execute(request: Request): Response {
                        val builder = okhttp3.Request.Builder().url(request.url())
                        request.headers().forEach { (name, values) ->
                            values.forEach { value -> builder.addHeader(name, value) }
                        }
                        request.dataToSend()?.let { data ->
                            builder.post(data.toRequestBody(null))
                        }

                        val response = httpClient.newCall(builder.build()).execute()
                        val body = response.body?.string()
                        return Response(
                            response.code,
                            response.message,
                            response.headers.toMultimap(),
                            body,
                            response.request.url.toString()
                        )
                    }
                })

                initialized = true
                println("Initialized with OkHttp downloader")
            }
        }
    }

    /**
     * Single-shot fetch: returns (muxedFormats, videoOnlyFormats, audioFormats) in one StreamInfo.getInfo call.
     * All other public methods delegate to this to avoid duplicate network round-trips.
     */
    private suspend fun fetchAllStreams(
        videoId: String,
        maxQuality: Int? = null
    ): Triple<List<YoutubeVideoFormat>, List<YoutubeVideoFormat>, List<YoutubeVideoFormat>> {
        ensureInitialized()

        val url = "https://www.youtube.com/watch?v=$videoId"
        println("Fetching all streams (single call) for $videoId")

        val streamInfo = try {
            withContext(Dispatchers.IO) {
                StreamInfo.getInfo(ServiceList.YouTube, url)
            }
        } catch (e: ContentNotAvailableException) {
            println("Video unavailable - ${e.message}")
            throw Exception("Video unavailable: ${e.message}", e)
        } catch (e: ParsingException) {
            println("Parsing failed (possible API change) - ${e.message}")
            throw Exception("Failed to parse video data: ${e.message}", e)
        } catch (e: ExtractionException) {
            println("Extraction failed - ${e.message}")
            throw Exception("Extraction failed: ${e.message}", e)
        } catch (e: Exception) {
            println("Unexpected error - ${e.javaClass.simpleName}: ${e.message}")
            throw Exception("Failed to get video info: ${e.message}", e)
        }

        kotlin.coroutines.coroutineContext.ensureActive()

        // Muxed (video + audio together)
        val muxedFormats = streamInfo.videoStreams
            .filter { it.isUrl && !it.content.isNullOrEmpty() && it.itag > 0 }
            .mapNotNull { videoStream ->
                val urlStr = videoStream.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val height = videoStream.height ?: 0
                val fps = videoStream.fps
                val mimeType = videoStream.format?.mimeType ?: "video/unknown"
                YoutubeVideoFormat(
                    itag = videoStream.itag,
                    mimeType = "Muxed-$mimeType-${height}p${fps}fps",
                    bitrate = height * fps * 1000,
                    url = urlStr,
                    loudness_db = null
                ) to height
            }
            .sortedByDescending { it.second }
            .map { it.first }

        // Video-only
        val videoOnlyFormats = streamInfo.videoOnlyStreams
            .filter { it.isUrl && !it.content.isNullOrEmpty() && it.itag > 0 &&
                (maxQuality == null || (it.height ?: 0) <= maxQuality) }
            .mapNotNull { videoStream ->
                val urlStr = videoStream.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val height = videoStream.height ?: 0
                val fps = videoStream.fps
                val mimeType = videoStream.format?.mimeType ?: "video/unknown"
                YoutubeVideoFormat(
                    itag = videoStream.itag,
                    mimeType = "Video-$mimeType-${height}p${fps}fps",
                    bitrate = height * fps * 1000,
                    url = urlStr,
                    loudness_db = null
                ) to height
            }
            .sortedByDescending { it.second }
            .map { it.first }

        // Audio-only
        val audioFormats = streamInfo.audioStreams
            .distinctBy { it.itag }
            .filter { it.isUrl && !it.content.isNullOrEmpty() && it.itag > 0 }
            .mapNotNull { audioStream ->
                val urlStr = audioStream.content?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val mimeType = audioStream.format?.mimeType ?: "audio/unknown"
                val formatName = audioStream.format?.name ?: "unknown"
                val avg = audioStream.averageBitrate
                val direct = audioStream.bitrate
                val bitrate = when {
                    avg != AudioStream.UNKNOWN_BITRATE -> avg * 1000
                    direct != AudioStream.UNKNOWN_BITRATE -> direct
                    else -> when {
                        mimeType.contains("opus", true) || formatName.contains("opus", true) -> OPUS_DEFAULT_BITRATE
                        mimeType.contains("aac", true) || mimeType.contains("mp4a", true) -> AAC_DEFAULT_BITRATE
                        formatName.contains("m4a", true) -> AAC_DEFAULT_BITRATE
                        else -> FALLBACK_BITRATE
                    }
                }
                YoutubeVideoFormat(
                    itag = audioStream.itag,
                    mimeType = "Audio-$mimeType-$formatName",
                    bitrate = bitrate,
                    url = urlStr,
                    loudness_db = null
                )
            }
            .sortedWith(
                compareByDescending<YoutubeVideoFormat> { it.mimeType.contains("opus", true) }
                    .thenByDescending { it.bitrate }
            )

        println("Fetched: ${muxedFormats.size} muxed, ${videoOnlyFormats.size} video-only, ${audioFormats.size} audio")
        return Triple(muxedFormats, videoOnlyFormats, audioFormats)
    }

    /**
     * Returns (videoOnlyFormats, audioFormats). Single StreamInfo.getInfo call.
     */
    suspend fun getSeparateStreams(
        videoId: String,
        maxQuality: Int? = null
    ): Result<Pair<List<YoutubeVideoFormat>, List<YoutubeVideoFormat>>> = runCatching {
        val (_, videoOnly, audio) = fetchAllStreams(videoId, maxQuality)
        if (videoOnly.isEmpty() && audio.isEmpty()) {
            throw Exception("No valid streams found for $videoId")
        }
        Pair(videoOnly, audio)
    }

    /**
     * Returns muxed (video+audio) streams. Single StreamInfo.getInfo call.
     */
    suspend fun getMuxedVideoStreams(
        videoId: String
    ): Result<List<YoutubeVideoFormat>> = runCatching {
        val (muxed, _, _) = fetchAllStreams(videoId)
        if (muxed.isEmpty()) throw Exception("No muxed video+audio streams found for $videoId")
        muxed
    }

    /**
     * Fast path for audio-only: single StreamInfo.getInfo call, returns only audio.
     */
    suspend fun getAudioFormats(
        videoId: String
    ): Result<List<YoutubeVideoFormat>> = runCatching {
        val (_, _, audio) = fetchAllStreams(videoId)
        if (audio.isEmpty()) throw Exception("No audio streams found for $videoId")
        audio
    }

    /**
     * Returns all three stream types in one call — use this in resolvers that need both muxed+separate
     * to avoid double network fetches.
     */
    suspend fun getAllStreams(
        videoId: String,
        maxQuality: Int? = null
    ): Result<Triple<List<YoutubeVideoFormat>, List<YoutubeVideoFormat>, List<YoutubeVideoFormat>>> =
        runCatching { fetchAllStreams(videoId, maxQuality) }
    
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }
}

