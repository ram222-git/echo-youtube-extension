package dev.brahmkshatriya.echo.extension.providers.playback

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.brahmkshatriya.echo.extension.streaming.YouTubeStreamResolver
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TrackLoader(
    private val authManager: YouTubeAuthManager,
    private val enhancedSongEndpoint: EchoEnhancedSongEndpoint,
    private val streamResolver: YouTubeStreamResolver,
    private val prefetchScope: CoroutineScope? = null,
    private val prefetchEnabled: () -> Boolean = { false }
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

        val loaded = enhancedSongEndpoint.loadEnhancedTrack(track.id, track, thumbnailQuality, enableVideo)
        prefetchAudio(loaded)
        return loaded
    }

    /**
     * Resolves the audio stream in the background as soon as a track is opened, so a
     * later play request is served from [dev.brahmkshatriya.echo.extension.streaming.StreamMediaCache]
     * (single-flight dedupes it with the real request). Best-effort: never throws.
     */
    private fun prefetchAudio(track: Track) {
        val scope = prefetchScope ?: return
        if (!prefetchEnabled()) return
        val streamable = track.streamables.firstOrNull {
            it.type == Streamable.MediaType.Server && it.extras["type"] != "video"
        } ?: return
        val videoId = streamable.extras["videoId"]
            ?: track.id.takeIf { it.isNotBlank() }
            ?: return

        scope.launch {
            runCatching { streamResolver.resolveStreamable(streamable, videoId) }
                .onFailure { println("TrackLoader: stream prefetch failed: ${it.message}") }
                .onSuccess { println("TrackLoader: stream prefetched for $videoId") }
        }
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
