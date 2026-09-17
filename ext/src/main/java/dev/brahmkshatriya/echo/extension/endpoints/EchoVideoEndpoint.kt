package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EchoVideoEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    private suspend fun request(
        context: JsonObject,
        id: String,
        playlist: String? = null,
        authenticated: Boolean = false
    ): HttpResponse {
        return api.client.request {
            endpointPath("player")
            if (authenticated && api.user_auth_state != null) {
                addApiHeadersWithAuthenticated()
            } else {
                addApiHeadersWithoutAuthentication()
            }
            postWithBody(context) {
                put("videoId", id)
                put("playlistId", playlist)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", buildJsonObject {
                    put("contentPlaybackContext", buildJsonObject {
                        put("signatureTimestamp", 20711)
                    })
                })
            }
        }
    }


    suspend fun getVideo(resolve: Boolean, id: String, playlist: String? = null): Pair<YoutubeFormatResponse, String?> = coroutineScope {
        val isAuthenticated = api.user_auth_state != null

        // 1. Fast Tier: For normal songs, use the direct IOS client (~250ms unthrottled streaming URLs)
        val iosResponse = runCatching {
            request(iosContext(), id, playlist, authenticated = false).body<YoutubeFormatResponse>()
        }.getOrNull()

        if (iosResponse?.playabilityStatus?.status == "OK" && iosResponse.streamingData?.adaptiveFormats?.isNotEmpty() == true) {
            val web = async {
                if (resolve) runCatching {
                    request(webRemixContext(), id, playlist, authenticated = isAuthenticated)
                        .body<YoutubeFormatResponse>().videoDetails.musicVideoType
                }.getOrNull() else null
            }
            return@coroutineScope iosResponse to web.await()
        }

        // 2. Fallback Tier: If IOS failed (e.g. age-restricted track requiring login) and user is authenticated
        if (isAuthenticated) {
            val webResponse = runCatching {
                request(webRemixContext(), id, playlist, authenticated = true).body<YoutubeFormatResponse>()
            }.getOrNull()

            if (webResponse?.streamingData?.adaptiveFormats?.isNotEmpty() == true) {
                return@coroutineScope webResponse to webResponse.videoDetails.musicVideoType
            }
        }

        // Return whatever response was obtained
        (iosResponse ?: throw IllegalStateException("Failed to retrieve video formats for $id")) to null
    }


    private fun iosContext() = buildJsonObject {
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "IOS")
                put("clientVersion", "19.34.2")
                api.visitor_id?.let { put("visitorData", it) }
            })
        })
    }

    private fun webRemixContext() = buildJsonObject {
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20240901.01.00")
                api.visitor_id?.let { put("visitorData", it) }
            })
        })
    }
}


@Serializable
data class YoutubeFormatResponse(
    val playabilityStatus: PlayabilityStatus? = null,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails
)

@Serializable
data class PlayabilityStatus(
    val status: String,
    val reason: String? = null,
    val playableInEmbed: Boolean? = null
)

@Serializable
data class StreamingData(
    val expiresInSeconds: String? = null,
    val hlsManifestUrl: String? = null,
    val adaptiveFormats: List<AdaptiveFormat> = emptyList()
)

@Serializable
data class AdaptiveFormat(
    val itag: Long? = null,
    val url: String? = null,
    val signatureCipher: String? = null,
    val cipher: String? = null,
    val mimeType: String,
    val bitrate: Int,
    val width: Long? = null,
    val height: Long? = null,
    val initRange: Range? = null,
    val indexRange: Range? = null,
    val lastModified: String? = null,
    val contentLength: String? = null,
    val quality: String? = null,
    val fps: Long? = null,
    val qualityLabel: String? = null,
    val projectionType: String? = null,
    val averageBitrate: Long? = null,
    val approxDurationMs: String? = null,
    val colorInfo: ColorInfo? = null,
    val highReplication: Boolean? = null,
    val audioQuality: String? = null,
    val audioSampleRate: String? = null,
    val audioChannels: Long? = null,
    val loudnessDb: Double? = null
)

@Serializable
data class ColorInfo(
    val primaries: String? = null,
    val transferCharacteristics: String? = null,
    val matrixCoefficients: String? = null
)

@Serializable
data class Range(
    val start: String? = null,
    val end: String? = null
)

@Serializable
data class VideoDetails(
    val videoId: String, 
    val title: String? = null,
    val lengthSeconds: String? = null,
    val channelId: String? = null,
    val isOwnerViewing: Boolean? = null,
    val isCrawlable: Boolean? = null,
    val allowRatings: Boolean? = null,
    val viewCount: String? = null,
    val author: String? = null,
    val isPrivate: Boolean? = null,
    val isUnpluggedCorpus: Boolean? = null,
    val musicVideoType: String? = null,
    val isLiveContent: Boolean? = null,
    val shortDescription: String? = null,
)