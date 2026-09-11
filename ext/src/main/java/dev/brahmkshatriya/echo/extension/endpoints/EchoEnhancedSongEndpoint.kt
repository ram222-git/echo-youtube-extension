package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

/**
 * Enhanced song endpoint that intelligently combines data from multiple sources.
 * Optimized to try ytm-kt first, then conditionally fetch legacy if needed.
 */
class EchoEnhancedSongEndpoint(
    private val api: YoutubeiApi,
    private val echoSongEndpoint: EchoSongEndPoint
) {
    /**
     * Load track data by combining ytm-kt LoadSong and custom EchoSongEndpoint.
     * Optimized to try ytm-kt first, then conditionally fetch legacy only if needed.
     * 
     * @param trackId YouTube video/song ID
     * @param fallbackTrack Original track for fallback data
     * @param thumbnailQuality Quality for thumbnail images
     * @return Enhanced Track with merged data from both sources
     */
    suspend fun loadEnhancedTrack(
        trackId: String, 
        fallbackTrack: Track,
        thumbnailQuality: ThumbnailProvider.Quality,
        enableVideo: Boolean = false
    ): Track {
        println("EchoEnhancedSongEndpoint: Loading track $trackId, title='${fallbackTrack.title}'")

        // Fast-path: When clicking a track that already has metadata (e.g. from Quick picks, feed, search, playlist),
        // return immediately with streamables so playback begins with ZERO delay (0ms).
        if (fallbackTrack.title.isNotBlank() && fallbackTrack.artists.isNotEmpty()) {
            println("EchoEnhancedSongEndpoint: Fast-path returning track without blocking network calls")
            val mergedExtras = buildMergedExtras(null, null, trackId, fallbackTrack)
            return fallbackTrack.copy(
                extras = mergedExtras,
                streamables = createDefaultStreamables(trackId, enableVideo)
            )
        }

        // Only fetch from network if metadata is missing (e.g. clicked an ID link)
        println("EchoEnhancedSongEndpoint: Metadata missing, fetching from endpoint")
        val loadedTrack = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }.getOrNull()

        val mergedExtras = buildMergedExtras(null, loadedTrack, trackId, fallbackTrack)
        return when {
            loadedTrack != null -> mergeWithLegacyPriority(loadedTrack, fallbackTrack, mergedExtras, enableVideo)
            else -> createFallbackTrack(fallbackTrack, mergedExtras, trackId, enableVideo)
        }
    }
    
    /**
     * Build merged extras map from all available sources.
     */
    private fun buildMergedExtras(
        ytmTrack: Track?,
        legacyTrack: Track?,
        trackId: String,
        fallbackTrack: Track
    ): Map<String, String> {
        return buildMap {
            put("videoId", trackId)
            
            // Prefer ytm-kt extras, then legacy, then fallback
            fallbackTrack.extras.forEach { (k, v) -> put(k, v) }
            legacyTrack?.extras?.forEach { (k, v) -> put(k, v) }
            ytmTrack?.extras?.forEach { (k, v) -> put(k, v) }
            
            // Explicitly set isVideo flag if available
            val isVideo = fallbackTrack.extras["isVideo"] 
                ?: legacyTrack?.extras?.get("isVideo")
                ?: ytmTrack?.extras?.get("isVideo")
                ?: "false"
            put("isVideo", isVideo)
            
            // Ensure availability is set
            if (!containsKey("availability")) {
                put("availability", "public")
            }
            
            println("  final merged isVideo=${get("isVideo")}")
        }
    }
    
    /**
     * Merge strategy when ytm-kt track is available (preferred source).
     * Falls back to legacy/original track for missing fields.
     */
    private fun mergeWithYtmPriority(
        ytmTrack: Track,
        legacyTrack: Track?,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        enableVideo: Boolean = false
    ): Track {
        val streamables = createDefaultStreamables(mergedExtras["videoId"] ?: ytmTrack.id, enableVideo)
        
        return ytmTrack.copy(
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,
            album = ytmTrack.album ?: legacyTrack?.album,
            artists = run {
                val ytmValid = sanitizeArtists(ytmTrack.artists, ytmTrack.title)
                val legacyValid = sanitizeArtists(legacyTrack?.artists.orEmpty(), ytmTrack.title)
                val fallbackValid = sanitizeArtists(fallbackTrack.artists, ytmTrack.title)

                when {
                    ytmValid.isNotEmpty() -> ytmValid
                    legacyValid.isNotEmpty() -> legacyValid
                    fallbackValid.isNotEmpty() -> fallbackValid
                    else -> sanitizeArtists(fallbackTrack.artists.ifEmpty { legacyTrack?.artists ?: ytmTrack.artists }, ytmTrack.title).ifEmpty {
                        listOf(Artist(id = "", name = "Unknown"))
                    }
                }
            },
            streamables = streamables,
            extras = mergedExtras
        )
    }

    private fun sanitizeArtists(artists: List<Artist>, trackTitle: String): List<Artist> {
        return artists.filter {
            it.name.isNotBlank() &&
            it.name != "Unknown" &&
            it.name != "•" &&
            !it.id.startsWith("MPREb_") &&
            !it.id.startsWith("OLAK5uy_") &&
            !it.id.startsWith("VL") &&
            !it.id.startsWith("PL")
        }.let { list ->
            if (list.size > 1) {
                list.filterNot { it.name.equals(trackTitle, ignoreCase = true) && !it.id.startsWith("UC") }
            } else list
        }.distinctBy { it.id.ifEmpty { it.name } }
    }

    private fun mergeWithLegacyPriority(
        legacyTrack: Track,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        enableVideo: Boolean = false
    ): Track {
        val legacyValid = sanitizeArtists(legacyTrack.artists, legacyTrack.title)
        val fallbackValid = sanitizeArtists(fallbackTrack.artists, legacyTrack.title)
        val finalArtists = if (legacyValid.isNotEmpty()) legacyValid else fallbackValid.ifEmpty { legacyTrack.artists }

        return legacyTrack.copy(
            artists = finalArtists,
            extras = mergedExtras,
            streamables = createDefaultStreamables(mergedExtras["videoId"] ?: legacyTrack.id, enableVideo)
        )
    }

    private fun createFallbackTrack(
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        trackId: String,
        enableVideo: Boolean = false
    ): Track {
        return fallbackTrack.copy(
            extras = mergedExtras,
            streamables = createDefaultStreamables(trackId, enableVideo)
        )
    }
    
    companion object {
        /**
         * Create comprehensive streamable configuration with Audio & optional Video choices in Sources.
         * All qualities are created as Streamable.server so they appear under Sources/Servers with RESIZE_MODE_FIT.
         */
        fun createDefaultStreamables(
            videoId: String,
            enableVideo: Boolean = false
        ): List<Streamable> {
            val audioStreamables = listOf(
                // Audio Qualities
                Streamable.server(
                    id = "yt_audio_opus_160_$videoId",
                    quality = 160,
                    title = "OPUS 160 kbps",
                    extras = mapOf("videoId" to videoId, "type" to "audio", "codec" to "opus", "bitrate" to "160")
                ),
                Streamable.server(
                    id = "yt_audio_aac_128_$videoId",
                    quality = 128,
                    title = "AAC 128 kbps",
                    extras = mapOf("videoId" to videoId, "type" to "audio", "codec" to "aac", "bitrate" to "128")
                ),
                Streamable.server(
                    id = "yt_audio_opus_70_$videoId",
                    quality = 70,
                    title = "OPUS 70 kbps",
                    extras = mapOf("videoId" to videoId, "type" to "audio", "codec" to "opus", "bitrate" to "70")
                ),
                Streamable.server(
                    id = "yt_audio_aac_48_$videoId",
                    quality = 48,
                    title = "AAC 48 kbps",
                    extras = mapOf("videoId" to videoId, "type" to "audio", "codec" to "aac", "bitrate" to "48")
                )
            )

            if (!enableVideo) {
                return audioStreamables
            }

            val videoStreamables = listOf(
                // Video Qualities as Servers so they show in Sources with no zoom (FIT)
                Streamable.server(
                    id = "yt_video_1080p_$videoId",
                    quality = 1080,
                    title = "VIDEO 1080p",
                    extras = mapOf("videoId" to videoId, "type" to "video", "height" to "1080")
                ),
                Streamable.server(
                    id = "yt_video_720p_$videoId",
                    quality = 720,
                    title = "VIDEO 720p",
                    extras = mapOf("videoId" to videoId, "type" to "video", "height" to "720")
                ),
                Streamable.server(
                    id = "yt_video_480p_$videoId",
                    quality = 480,
                    title = "VIDEO 480p",
                    extras = mapOf("videoId" to videoId, "type" to "video", "height" to "480")
                ),
                Streamable.server(
                    id = "yt_video_360p_$videoId",
                    quality = 360,
                    title = "VIDEO 360p",
                    extras = mapOf("videoId" to videoId, "type" to "video", "height" to "360")
                )
            )

            return audioStreamables + videoStreamables
        }
    }
}
