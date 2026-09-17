package dev.brahmkshatriya.echo.extension.providers.social

import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.SongLikedStatus
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class LikeManager(
    override val api: YoutubeiApi,
    private val authManager: YouTubeAuthManager,
    private val json: Json
) : ApiEndpoint() {

    private val likedStatusCache = ConcurrentHashMap<String, Boolean>()

    fun markLiked(songId: String, isLiked: Boolean = true) {
        likedStatusCache[songId] = isLiked
    }

    fun isCachedLiked(songId: String): Boolean? {
        return likedStatusCache[songId]
    }

    suspend fun setLiked(item: EchoMediaItem, shouldLike: Boolean) {
        val track = item as? Track 
            ?: throw Exception("Only tracks can be liked")
        
        // Optimistically update cache immediately
        likedStatusCache[track.id] = shouldLike
        
        val auth = authManager.requireAuth()
        val likeStatus = if (shouldLike) SongLikedStatus.LIKED else SongLikedStatus.NEUTRAL
        try {
            auth.SetSongLiked.setSongLiked(track.id, likeStatus).getOrThrow()
        } catch (e: Exception) {
            likedStatusCache.remove(track.id)
            throw e
        }
    }

    suspend fun isLiked(item: EchoMediaItem): Boolean {
        val trackId = (item as? Track)?.id ?: item.id

        // 1. Check in-memory cache first
        likedStatusCache[trackId]?.let { return it }

        // 2. Check item extras if marked
        val extraLiked = item.extras["isLiked"]?.toBooleanStrictOrNull()
        if (extraLiked != null) {
            likedStatusCache[trackId] = extraLiked
            return extraLiked
        }

        // 3. If authenticated, query YouTube Music server for likeStatus
        if (authManager.isAuthenticated()) {
            try {
                authManager.requireAuth()
                val isLiked = fetchSongLikedStatus(trackId)
                likedStatusCache[trackId] = isLiked
                return isLiked
            } catch (e: Exception) {
                println("LikeManager: Failed to fetch like status for $trackId: ${e.message}")
            }
        }

        return false
    }

    private suspend fun fetchSongLikedStatus(songId: String): Boolean {
        val response: HttpResponse = api.client.request {
            endpointPath("next")
            addApiHeadersWithAuthenticated()
            postWithBody {
                put("videoId", songId)
            }
        }

        val responseText = response.bodyAsText()
        val root = json.parseToJsonElement(responseText).jsonObject

        val playerOverlays = root["playerOverlays"]?.jsonObject
        val actions = playerOverlays?.get("playerOverlayRenderer")?.jsonObject
            ?.get("actions")?.jsonArray

        val likeStatus = actions?.firstNotNullOfOrNull { action ->
            action.jsonObject["likeButtonRenderer"]?.jsonObject
                ?.get("likeStatus")?.jsonPrimitive?.contentOrNull
        } ?: findLikeStatusRecursively(root)

        return likeStatus == "LIKE"
    }

    private fun findLikeStatusRecursively(element: JsonElement): String? {
        if (element is JsonObject) {
            element["likeButtonRenderer"]?.jsonObject?.get("likeStatus")?.jsonPrimitive?.contentOrNull?.let {
                return it
            }
            for (child in element.values) {
                findLikeStatusRecursively(child)?.let { return it }
            }
        } else if (element is JsonArray) {
            for (child in element) {
                findLikeStatusRecursively(child)?.let { return it }
            }
        }
        return null
    }
}
