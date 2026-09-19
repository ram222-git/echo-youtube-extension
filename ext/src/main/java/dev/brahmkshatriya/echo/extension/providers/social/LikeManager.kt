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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class LikeManager(
    override val api: YoutubeiApi,
    private val authManager: YouTubeAuthManager
) : ApiEndpoint() {
    private val likedCache = ConcurrentHashMap<String, Boolean>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun markLiked(trackId: String, isLiked: Boolean) {
        likedCache[trackId] = isLiked
    }

    fun getCachedLiked(trackId: String): Boolean? {
        return likedCache[trackId]
    }

    fun clearCache() {
        likedCache.clear()
    }

    suspend fun setLiked(item: EchoMediaItem, shouldLike: Boolean) {
        val track = item as? Track 
            ?: throw Exception("Only tracks can be liked")
        
        val auth = authManager.requireAuth()
        val likeStatus = if (shouldLike) SongLikedStatus.LIKED else SongLikedStatus.NEUTRAL
        auth.SetSongLiked.setSongLiked(track.id, likeStatus).getOrThrow()
        likedCache[track.id] = shouldLike
    }

    suspend fun isLiked(item: EchoMediaItem): Boolean {
        val trackId = item.id

        // 1. Check in-memory cache first (instant response)
        likedCache[trackId]?.let { return it }

        // 2. Check item extras if already populated (e.g. from Liked Music playlist)
        val fromExtras = item.extras["isLiked"]?.toBooleanStrictOrNull()
        if (fromExtras != null) {
            likedCache[trackId] = fromExtras
            return fromExtras
        }

        // 3. If user is not authenticated, cannot be liked on YouTube Music
        if (!authManager.isAuthenticated()) {
            return false
        }

        // 4. Dynamically fetch like status from YouTube Music
        return try {
            val isLiked = fetchSongLikedStatus(trackId)
            likedCache[trackId] = isLiked
            isLiked
        } catch (e: Exception) {
            println("LikeManager: failed to fetch like status for $trackId: ${e.message}")
            false
        }
    }

    private suspend fun fetchSongLikedStatus(trackId: String): Boolean {
        return runCatching {
            val response: HttpResponse = api.client.request {
                endpointPath("next")
                addApiHeadersWithAuthenticated()
                postWithBody {
                    put("videoId", trackId)
                    put("isAudioOnly", true)
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
            } ?: Regex("\"likeStatus\"\\s*:\\s*\"([A-Z]+)\"").find(responseText)?.groupValues?.get(1)

            likeStatus == "LIKE"
        }.getOrDefault(false)
    }
}
