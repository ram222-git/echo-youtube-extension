package dev.brahmkshatriya.echo.extension.auth

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.GoogleAccountResponse
import dev.brahmkshatriya.echo.extension.json
import dev.brahmkshatriya.echo.extension.utils.CookieParser
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.headers

class YouTubeAuthManager(
    private val api: YoutubeiApi,
    private val visitorEndpoint: EchoVisitorEndpoint
) {
    @Volatile
    private var activeCookie: String? = null
    
    @Volatile
    private var currentUserId: String? = null

    private var authState: YoutubeiAuthenticationState? = null
    private var pendingCredentials: PendingCredentials? = null
    
    var onCookieUpdate: ((String) -> Unit)? = null

    private data class PendingCredentials(
        val cookie: String,
        val auth: String,
        val userId: String
    )

    suspend fun ensureVisitorId(): Result<String> = runCatching {
        api.visitor_id ?: run {
            val visitorId = visitorEndpoint.getVisitorId()
            api.visitor_id = visitorId
            visitorId
        }
    }

    fun getActiveCookie(): String? = activeCookie

    fun getCurrentUserId(): String? = currentUserId

    /**
     * Generates fresh HTTP headers with dynamic SAPISIDHASH and current cookies.
     * Prevents authorization timeout on long-running sessions.
     */
    fun getFreshAuthHeaders(origin: String = "https://music.youtube.com"): Map<String, String> {
        val cookie = activeCookie ?: return emptyMap()
        val cookieMap = CookieParser.parse(cookie)
        val auth = CookieParser.generateDynamicAuthHeader(cookieMap, origin)
        return buildMap {
            put("Cookie", cookie)
            if (auth.isNotEmpty()) {
                put("Authorization", auth)
            }
            put("Origin", origin)
            put("X-Origin", origin)
        }
    }

    /**
     * Updates the stored cookies, refreshing api.user_auth_state and notifying listeners.
     */
    fun updateCookies(newCookie: String) {
        if (newCookie.isBlank()) return
        activeCookie = newCookie
        val userId = currentUserId
        
        val cookieMap = CookieParser.parse(newCookie)
        val auth = CookieParser.generateDynamicAuthHeader(cookieMap)
        
        val authHeaders = headers {
            append("cookie", newCookie)
            if (auth.isNotEmpty()) {
                append("authorization", auth)
            }
        }
        
        val state = YoutubeiAuthenticationState(api, authHeaders, userId?.ifEmpty { null })
        authState = state
        api.user_auth_state = state
        
        onCookieUpdate?.invoke(newCookie)
        println("YouTubeAuthManager: Updated active cookies (${cookieMap.size} cookies present)")
    }

    /**
     * Handles Set-Cookie response headers from YouTube/Google, merging updated cookies into the session.
     */
    fun handleSetCookieHeaders(headers: List<String>) {
        val current = activeCookie ?: return
        val updated = CookieParser.mergeCookies(current, headers)
        if (updated != current) {
            updateCookies(updated)
        }
    }

    fun storeCredentialsForLazyInit(cookie: String, auth: String, userId: String) {
        pendingCredentials = PendingCredentials(cookie, auth, userId)
        activeCookie = cookie
        currentUserId = userId
        println("Stored credentials for lazy authentication")
    }

    suspend fun login(cookie: String, auth: String, userId: String): Result<User> = runCatching {
        activeCookie = cookie
        currentUserId = userId
        
        val cookieMap = CookieParser.parse(cookie)
        val dynamicAuth = CookieParser.generateDynamicAuthHeader(cookieMap).ifEmpty { auth }

        val authHeaders = headers {
            append("cookie", cookie)
            append("authorization", dynamicAuth)
        }

        authState = YoutubeiAuthenticationState(api, authHeaders, userId.ifEmpty { null })
        api.user_auth_state = authState

        ensureVisitorId().getOrNull()

        getCurrentUser().getOrThrow() ?: throw Exception("Failed to retrieve user information after login")
    }

    fun logout() {
        authState = null
        pendingCredentials = null
        activeCookie = null
        currentUserId = null
        api.user_auth_state = null
        println("Logged out and cleared credentials")
    }

    suspend fun getCurrentUser(): Result<User?> = runCatching {
        val state = authState ?: api.user_auth_state ?: return@runCatching null

        val response = api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
            headers {
                append("referer", "https://music.youtube.com/")
                state.headers.forEach { key, values ->
                    values.forEach { value -> append(key, value) }
                }
            }
        }
        
        val responseText = response.bodyAsText()
        val jsonText = if (responseText.startsWith(")]}'")) {
            responseText.substringAfter(")]}'")
        } else {
            responseText
        }
        
        val accountResponse = json.decodeFromString<GoogleAccountResponse>(jsonText)
        val userResponse = accountResponse.getUsers(activeCookie ?: "", "").firstOrNull()

        userResponse?.copy(
            subtitle = userResponse.extras["email"]?.takeIf { it.isNotEmpty() }
                ?: userResponse.extras["channelHandle"]?.takeIf { it.isNotEmpty() }
                ?: "YouTube Music User",
            extras = userResponse.extras.toMutableMap().apply {
                put("isLoggedIn", "true")
                put("userService", "youtube_music")
                put("accountType", "google")
                activeCookie?.let { put("cookie", it) }
                put("lastUpdated", System.currentTimeMillis().toString())
            }
        )
    }

    suspend fun requireAuth(): YoutubeiAuthenticationState {
        api.user_auth_state?.let { return it }
        authState?.let { return it }
        
        pendingCredentials?.let { creds ->
            println("Performing lazy authentication initialization")
            return login(creds.cookie, creds.auth, creds.userId)
                .getOrThrow()
                .let { 
                    pendingCredentials = null 
                    authState ?: api.user_auth_state ?: throw ClientException.LoginRequired()
                }
        }
        
        throw ClientException.LoginRequired()
    }

    fun isAuthenticated(): Boolean = api.user_auth_state != null || authState != null || pendingCredentials != null || activeCookie != null
}
