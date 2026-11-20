package dev.brahmkshatriya.echo.extension.providers.playback

import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.brahmkshatriya.echo.extension.streaming.StreamPrefetcher
import dev.brahmkshatriya.echo.extension.streaming.YouTubeStreamResolver
import dev.toastbits.ytmkt.model.external.ThumbnailProvider

class TrackLoader(
    private val enhancedSongEndpoint: EchoEnhancedSongEndpoint,
    private val streamResolver: YouTubeStreamResolver,
    private val prefetcher: StreamPrefetcher? = null
) {
    suspend fun loadTrackDetails(
        track: Track,
        thumbnailQuality: ThumbnailProvider.Quality,
        enableVideo: Boolean = false
    ): Track {
        val loaded = enhancedSongEndpoint.loadEnhancedTrack(track.id, track, thumbnailQuality, enableVideo)
        // Resolve the audio in the background (single-flight dedupes it with the real
        // play request) so the first tap on play is a cache hit.
        prefetcher?.prefetchTrack(loaded)
        return loaded
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
