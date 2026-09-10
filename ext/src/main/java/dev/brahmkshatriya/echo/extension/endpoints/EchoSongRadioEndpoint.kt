package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EchoSongRadioEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {

    suspend fun getSongRadioNextResponse(videoId: String, continuation: String?): HttpResponse {
        val clientContext = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20220606.03.00")
                    api.visitor_id?.let { put("visitorData", it) }
                })
            })
        }

        return api.client.request {
            endpointPath("next")
            addApiHeadersWithAuthenticated()
            postWithBody(clientContext) {
                put("videoId", videoId)
                put("playlistId", "RDAMVM$videoId")
                put("isAudioOnly", true)
                if (continuation != null) {
                    put("continuation", continuation)
                }
            }
        }
    }
}
