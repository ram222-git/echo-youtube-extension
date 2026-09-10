package dev.brahmkshatriya.echo.extension

import dev.toastbits.ytmkt.formats.MultipleVideoFormatsEndpoint
import dev.toastbits.ytmkt.formats.VideoFormatsEndpoint
import dev.toastbits.ytmkt.model.YtmApi
import dev.toastbits.ytmkt.model.external.YoutubeVideoFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class YtmKtVideoFormatsEndpoint(
    private val api: YtmApi
) {
    private val videoFormatsEndpoint: VideoFormatsEndpoint by lazy {
        api.VideoFormats
    }

    suspend fun getSeparateStreams(
        videoId: String,
        maxQuality: Int? = null
    ): Result<Pair<List<YoutubeVideoFormat>, List<YoutubeVideoFormat>>> = runCatching {
        println("Extracting separate streams for $videoId (maxQuality=$maxQuality)")

        val allFormats = withContext(Dispatchers.IO) {
            videoFormatsEndpoint.getVideoFormats(
                id = videoId,
                include_non_default = true,
                filter = null
            ).getOrThrow()
        }

        val audioFormats = allFormats.filter { format ->
            format.isAudioOnly()
        }.sortedWith(
            compareByDescending<YoutubeVideoFormat> { it.mimeType.contains("opus", ignoreCase = true) }
                .thenByDescending { it.bitrate }
        )

        val videoFormats = allFormats.filter { format ->
            !format.isAudioOnly() && 
            !format.mimeType.contains("audio", ignoreCase = true)
        }.filter { format ->
            if (maxQuality != null) {
                val heightRegex = """(\d+)p""".toRegex()
                val match = heightRegex.find(format.mimeType)
                val height = match?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
                height <= maxQuality
            } else {
                true
            }
        }.sortedByDescending { format ->
            val heightRegex = """(\d+)p""".toRegex()
            val match = heightRegex.find(format.mimeType)
            match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        println("Returning ${videoFormats.size} video formats and ${audioFormats.size} audio formats")

        if (videoFormats.isEmpty() && audioFormats.isEmpty()) {
            throw Exception("No valid streams found for $videoId")
        }

        Pair(videoFormats, audioFormats)
    }

    suspend fun getMuxedVideoStreams(
        videoId: String
    ): Result<List<YoutubeVideoFormat>> = runCatching {
        println("Extracting muxed (video+audio) streams for $videoId")

        val allFormats = withContext(Dispatchers.IO) {
            videoFormatsEndpoint.getVideoFormats(
                id = videoId,
                include_non_default = false, 
                filter = null
            ).getOrThrow()
        }

        val muxedFormats = allFormats.filter { format ->
            !format.isAudioOnly() &&
            format.mimeType.contains("video", ignoreCase = true)
        }.sortedByDescending { format ->
            val heightRegex = """(\d+)p""".toRegex()
            val match = heightRegex.find(format.mimeType)
            match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        println("Returning ${muxedFormats.size} muxed video+audio formats")

        if (muxedFormats.isEmpty()) {
            throw Exception("No muxed video+audio streams found for $videoId")
        }

        muxedFormats
    }

    suspend fun getAudioFormats(
        videoId: String
    ): Result<List<YoutubeVideoFormat>> = runCatching {
        println("Extracting audio-only streams for $videoId")

        val allFormats = withContext(Dispatchers.IO) {
            videoFormatsEndpoint.getVideoFormats(
                id = videoId,
                include_non_default = true,
                filter = { it.isAudioOnly() }
            ).getOrThrow()
        }

        val audioFormats = allFormats.filter { it.isAudioOnly() }
            .sortedWith(
                compareByDescending<YoutubeVideoFormat> { it.mimeType.contains("opus", ignoreCase = true) }
                    .thenByDescending { it.bitrate }
            )

        println("Returning ${audioFormats.size} audio formats")

        if (audioFormats.isEmpty()) {
            throw Exception("No audio streams found for $videoId")
        }

        audioFormats
    }

    suspend fun getAllFormats(
        videoId: String,
        includeNonDefault: Boolean = true
    ): Result<List<YoutubeVideoFormat>> = runCatching {
        println("Extracting all formats for $videoId")

        val formats = withContext(Dispatchers.IO) {
            videoFormatsEndpoint.getVideoFormats(
                id = videoId,
                include_non_default = includeNonDefault,
                filter = null
            ).getOrThrow()
        }

        println("Returning ${formats.size} total formats")

        if (formats.isEmpty()) {
            throw Exception("No formats found for $videoId")
        }

        formats
    }
}
