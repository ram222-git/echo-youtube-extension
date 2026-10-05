package dev.brahmkshatriya.echo.extension.auth

import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.createFallbackUsers
import dev.brahmkshatriya.echo.extension.endpoints.EchoAccountEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.brahmkshatriya.echo.extension.getUsers
import dev.brahmkshatriya.echo.extension.utils.CookieParser
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiAuthenticationState
import io.ktor.client.request.request
import io.ktor.http.headers

class YouTubeAuthManager(
    private val api: YoutubeiApi,
    private val visitorEndpoint: EchoVisitorEndpoint,
    private val accountEndpoint: EchoAccountEndpoint? = null
) {
    @Volatile
    private var activeCookie: String? = null
    
    @Volatile
    private var currentUserId: String? = null

    @Volatile
    private var authState: YoutubeiAuthenticationState? = null

    @Volatile
    private var pendingCredentials: PendingCredentials? = null
    
    @Volatile
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

        getCurrentUser().getOrNull() ?: createFallbackUsers(cookie, dynamicAuth).first()
    }

    fun logout() {
        authState = null
        pendingCredentials = null
        activeCookie = null
        currentUserId = null
        api.user_auth_state = null
        println("Logged out and cleared credentials")
    }

    private fun User.withUserExtras(cookie: String): User {
        return copy(
            subtitle = "YouTube Music",
            extras = extras.toMutableMap().apply {
                put("isLoggedIn", "true")
                put("userService", "youtube_music")
                put("accountType", "google")
                if (cookie.isNotEmpty()) put("cookie", cookie)
                put("lastUpdated", System.currentTimeMillis().toString())
            }
        )
    }

    suspend fun getCurrentUser(): Result<User?> = runCatching {
        val state = authState ?: api.user_auth_state ?: return@runCatching null
        val currentCookie = activeCookie ?: ""
        val cookieMap = CookieParser.parse(currentCookie)
        val dynamicAuth = CookieParser.generateDynamicAuthHeader(cookieMap)

        // 1. Try InnerTube account/account_menu via accountEndpoint
        val accountMenuUsers: List<User>? = runCatching {
            val response = accountEndpoint?.getAccountMenu()
            response?.getUsers(currentCookie, dynamicAuth)
        }.getOrNull()

        val foundFromMenu = accountMenuUsers?.firstOrNull { it.name != "YouTube Music User" }
            ?: accountMenuUsers?.firstOrNull()

        if (foundFromMenu != null && foundFromMenu.name != "YouTube Music User") {
            return@runCatching foundFromMenu.withUserExtras(currentCookie)
        }

        // 2. Try getAccountSwitcherEndpoint
        val switcherUsers: List<User>? = runCatching {
            val response = api.client.request("https://music.youtube.com/getAccountSwitcherEndpoint") {
                headers {
                    append("origin", "https://music.youtube.com")
                    append("referer", "https://music.youtube.com/")
                    append("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    append("accept", "*/*")
                    append("x-origin", "https://music.youtube.com")
                    state.headers.forEach { key, values ->
                        values.forEach { value -> append(key, value) }
                    }
                }
            }
            response.getUsers(currentCookie, dynamicAuth)
        }.getOrNull()

        val foundFromSwitcher = switcherUsers?.firstOrNull { it.name != "YouTube Music User" }
            ?: switcherUsers?.firstOrNull()

        if (foundFromSwitcher != null && foundFromSwitcher.name != "YouTube Music User") {
            return@runCatching foundFromSwitcher.withUserExtras(currentCookie)
        }

        val fallback = foundFromMenu ?: foundFromSwitcher ?: createFallbackUsers(currentCookie, dynamicAuth).firstOrNull()
        fallback?.copy(subtitle = "YouTube Music")
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
