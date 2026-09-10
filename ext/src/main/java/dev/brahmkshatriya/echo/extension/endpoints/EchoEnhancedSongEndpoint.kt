package dev.brahmkshatriya.echo.extension.endpoints

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
        enableVideo: Boolean = true,
        preferVideos: Boolean = false
    ): Track {
        println("EchoEnhancedSongEndpoint: Loading track $trackId, fallback isVideo=${fallbackTrack.extras["isVideo"]}, enableVideo=$enableVideo, preferVideos=$preferVideos")
        
        // Try ytm-kt first (faster, better quality data)
        val ytmTrack = runCatching {
            api.LoadSong.loadSong(trackId).getOrThrow()
        }.map { it.toTrack(thumbnailQuality) }.getOrNull()
        
        if (ytmTrack != null) {
            // Check if we need legacy data for missing extras (lyricsId, relatedId, isLiked)
            val needsLegacyExtras = ytmTrack.extras["lyricsId"] == null || 
                                     ytmTrack.extras["relatedId"] == null ||
                                     ytmTrack.extras["isLiked"] == null
            
            if (needsLegacyExtras) {
                println("ytm-kt track missing extras, fetching from legacy endpoint")
                val legacyTrack = runCatching {
                    echoSongEndpoint.loadSong(trackId).getOrThrow()
                }.getOrNull()
                
                val mergedExtras = buildMergedExtras(ytmTrack, legacyTrack, trackId, fallbackTrack)
                return mergeWithYtmPriority(ytmTrack, legacyTrack, fallbackTrack, mergedExtras, enableVideo, preferVideos)
            } else {
                println("ytm-kt track has all required extras, skipping legacy fetch")
                val mergedExtras = buildMergedExtras(ytmTrack, null, trackId, fallbackTrack)
                return mergeWithYtmPriority(ytmTrack, null, fallbackTrack, mergedExtras, enableVideo, preferVideos)
            }
        }
        
        // Fallback to legacy if ytm-kt failed
        println("ytm-kt failed, trying legacy endpoint")
        val legacyTrack = runCatching {
            echoSongEndpoint.loadSong(trackId).getOrThrow()
        }.getOrNull()
        
        val mergedExtras = buildMergedExtras(null, legacyTrack, trackId, fallbackTrack)
        
        return when {
            legacyTrack != null -> mergeWithLegacyPriority(legacyTrack, fallbackTrack, mergedExtras, enableVideo, preferVideos)
            else -> createFallbackTrack(fallbackTrack, mergedExtras, trackId, enableVideo, preferVideos)
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
        enableVideo: Boolean = true,
        preferVideos: Boolean = false
    ): Track {
        val streamables = createDefaultStreamables(mergedExtras["videoId"] ?: ytmTrack.id, enableVideo, preferVideos)
        
        return ytmTrack.copy(
            cover = ytmTrack.cover ?: fallbackTrack.cover ?: legacyTrack?.cover,
            album = ytmTrack.album ?: legacyTrack?.album,
            artists = run {
                val ytmValid = ytmTrack.artists.filter { it.name.isNotBlank() && it.name != "Unknown" && it.name != "•" }
                val fallbackValid = fallbackTrack.artists.filter { it.name.isNotBlank() && it.name != "Unknown" && it.name != "•" }
                val legacyValid = legacyTrack?.artists?.filter { it.name.isNotBlank() && it.name != "Unknown" && it.name != "•" } ?: emptyList()

                when {
                    ytmValid.isNotEmpty() -> ytmValid
                    fallbackValid.isNotEmpty() -> fallbackValid
                    legacyValid.isNotEmpty() -> legacyValid
                    else -> fallbackTrack.artists.ifEmpty { legacyTrack?.artists ?: ytmTrack.artists }
                }
            },
            streamables = streamables,
            extras = mergedExtras
        )
    }

    private fun mergeWithLegacyPriority(
        legacyTrack: Track,
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        enableVideo: Boolean = true,
        preferVideos: Boolean = false
    ): Track {
        val legacyValid = legacyTrack.artists.filter { it.name.isNotBlank() && it.name != "Unknown" && it.name != "•" }
        val fallbackValid = fallbackTrack.artists.filter { it.name.isNotBlank() && it.name != "Unknown" && it.name != "•" }
        val finalArtists = if (legacyValid.isNotEmpty()) legacyValid else fallbackValid.ifEmpty { legacyTrack.artists }

        return legacyTrack.copy(
            artists = finalArtists,
            extras = mergedExtras,
            streamables = createDefaultStreamables(mergedExtras["videoId"] ?: legacyTrack.id, enableVideo, preferVideos)
        )
    }

    private fun createFallbackTrack(
        fallbackTrack: Track,
        mergedExtras: Map<String, String>,
        trackId: String,
        enableVideo: Boolean = true,
        preferVideos: Boolean = false
    ): Track {
        return fallbackTrack.copy(
            extras = mergedExtras,
            streamables = createDefaultStreamables(trackId, enableVideo, preferVideos)
        )
    }
    
    companion object {
        /**
         * Create comprehensive streamable configuration with Audio & optional Video choices in Sources.
         * All qualities are created as Streamable.server so they appear under Sources/Servers with RESIZE_MODE_FIT.
         */
        fun createDefaultStreamables(
            videoId: String,
            enableVideo: Boolean = true,
            preferVideos: Boolean = false
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

            return if (preferVideos) {
                videoStreamables + audioStreamables
            } else {
                audioStreamables + videoStreamables
            }
        }
    }
}
