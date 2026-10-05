package dev.brahmkshatriya.echo.extension.endpoints

import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse

class EchoAccountEndpoint(override val api: YoutubeiApi) : ApiEndpoint() {
    suspend fun getAccountMenu(): HttpResponse {
        return api.client.request {
            endpointPath("account/account_menu")
            addApiHeadersWithAuthenticated()
            postWithBody()
        }
    }
}
