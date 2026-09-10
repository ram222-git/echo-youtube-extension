package dev.brahmkshatriya.echo.extension.providers.social

import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.toastbits.ytmkt.model.external.SongLikedStatus


class LikeManager(
    private val authManager: YouTubeAuthManager
) {
    suspend fun setLiked(item: EchoMediaItem, shouldLike: Boolean) {
        val track = item as? Track 
            ?: throw Exception("Only tracks can be liked")
        
        val auth = authManager.requireAuth()
        val likeStatus = if (shouldLike) SongLikedStatus.LIKED else SongLikedStatus.NEUTRAL
        auth.SetSongLiked.setSongLiked(track.id, likeStatus).getOrThrow()
    }

    fun isLiked(item: EchoMediaItem): Boolean {
        return item.extras["isLiked"]?.toBoolean() ?: false
    }
}
