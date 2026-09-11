package dev.brahmkshatriya.echo.extension.providers.playback

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.brahmkshatriya.echo.extension.streaming.YouTubeStreamResolver
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

class TrackLoader(
    private val authManager: YouTubeAuthManager,
    private val enhancedSongEndpoint: EchoEnhancedSongEndpoint,
    private val streamResolver: YouTubeStreamResolver
) {
    suspend fun loadTrackDetails(
        track: Track,
        thumbnailQuality: ThumbnailProvider.Quality,
        enableVideo: Boolean = false
    ): Track {
        try {
            authManager.ensureVisitorId().getOrNull()
        } catch (e: Exception) {
            println("Failed to ensure visitor ID in loadTrack: ${e.message}")
        }

        return enhancedSongEndpoint.loadEnhancedTrack(track.id, track, thumbnailQuality, enableVideo)
    }

    suspend fun loadStreamableMedia(
        streamable: Streamable
    ): Streamable.Media {
        val videoId = streamable.extras["videoId"]
            ?: streamable.id.substringAfterLast("_").takeIf { it.isNotBlank() }
            ?: throw Exception("No video ID found. This track may not be playable.")

        return when (streamable.type) {
            Streamable.MediaType.Server -> {
                streamResolver.resolveStreamable(streamable, videoId)
            }
            Streamable.MediaType.Background -> {
                streamResolver.resolveBackground(streamable, videoId)
            }
            Streamable.MediaType.Subtitle -> {
                throw Exception("Subtitles not supported")
            }
        }
    }
}
