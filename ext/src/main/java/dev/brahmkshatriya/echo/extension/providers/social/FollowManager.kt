package dev.brahmkshatriya.echo.extension.providers.social

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager


class FollowManager(
    private val authManager: YouTubeAuthManager
) {
    suspend fun setFollowing(item: EchoMediaItem, shouldFollow: Boolean) {
        when (item) {
            is Artist -> {
                val auth = authManager.requireAuth()
                val subId = item.extras["subId"]
                auth.SetSubscribedToArtist.setSubscribedToArtist(
                    item.id,
                    shouldFollow,
                    subId
                )
            }
            else -> throw ClientException.NotSupported(
                "Follow not supported for ${item::class.simpleName}"
            )
        }
    }

    suspend fun isFollowing(item: EchoMediaItem): Boolean {
        return when (item) {
            is Artist -> {
                try {
                    val auth = authManager.requireAuth()
                    val result = auth.SubscribedToArtist.isSubscribedToArtist(item.id)
                    result.getOrNull() ?: false
                } catch (e: Exception) {
                    println("Failed to check if artist is followed: ${e.message}")
                    false
                }
            }
            else -> false
        }
    }

    fun getFollowerCount(item: EchoMediaItem): Long? {
        return item.extras["followerCount"]?.toLongOrNull()
    }
}
