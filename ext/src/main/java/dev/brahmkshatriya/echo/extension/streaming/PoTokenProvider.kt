package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.utils.stringSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Supplies the PO Token that web clients need on direct googlevideo URLs.
 *
 * YouTube binds GVS tokens to the **video ID** (since the 2026 enforcement
 * change), so a token minted for one video cannot be used for another. Callers
 * must pass the video ID of the request that produced the URLs.
 */
interface PoTokenProvider {
    /** Shown in logs and used in cache keys: `off`, `android` or `external`. */
    val kind: String

    /**
     * Returns a token for [contentBinding] (a video ID), or `null` when no token
     * can be minted. Implementations must never throw: a missing token only means
     * the token-free clients should be used instead.
     */
    suspend fun token(contentBinding: String?): String?

    /** Drops every cached token. Called on logout, account switch and 403. */
    fun invalidate()
}

/** No provider configured: token-dependent clients are skipped. */
object OffPoTokenProvider : PoTokenProvider {
    override val kind: String = "off"
    override suspend fun token(contentBinding: String?): String? = null
    override fun invalidate() = Unit
}

/**
 * Reads the provider mode from settings on every call, so toggling the setting
 * does not require rebuilding the extension components.
 *
 * Modes:
 *  - `off`      — no provider (default; token-free clients only).
 *  - `android`  — mint on-device via [AndroidPoTokenProvider] (headless WebView,
 *                 no server), falling back to the external provider when one is
 *                 configured.
 *  - `external` — the user-hosted bgutil server only (previous behaviour).
 *
 * For older installs a configured server URL is enough to enable `external`,
 * even when the mode entry is missing.
 */
class SettingsPoTokenProvider(
    private val settings: Settings,
    /** Injectable for tests; the real value boots the WebView engine lazily. */
    androidProviderFactory: () -> PoTokenProvider = { AndroidPoTokenProvider() }
) : PoTokenProvider {

    private val androidProvider: PoTokenProvider by lazy { androidProviderFactory() }
    private val external = ExternalPoTokenProvider { baseUrl() }

    /**
     * Availability as the resolver sees it: the WEB_REMIX lane is only spent when
     * some source can actually produce a token. An on-device provider that has
     * permanently failed (no WebView, renderer gone) degrades the kind so the
     * lane is skipped instead of burning a request.
     */
    override val kind: String
        get() = when (mode()) {
            MODE_ANDROID -> {
                val androidUsable = (androidProvider as? AndroidPoTokenProvider)?.permanentlyBroken != true
                when {
                    androidUsable -> "android"
                    baseUrl() != null -> "external"
                    else -> "off"
                }
            }
            MODE_EXTERNAL -> if (baseUrl() != null) "external" else "off"
            else -> if (baseUrl() != null) "external" else "off"
        }

    override suspend fun token(contentBinding: String?): String? {
        return when (mode()) {
            MODE_ANDROID ->
                androidProvider.token(contentBinding)
                    ?: if (baseUrl() != null) external.token(contentBinding) else null
            MODE_EXTERNAL -> external.token(contentBinding)
            else ->
                // Back-compat: a configured URL enables the external provider even
                // when the mode entry is absent or "off".
                if (baseUrl() != null) external.token(contentBinding) else null
        }
    }

    override fun invalidate() {
        androidProvider.invalidate()
        external.invalidate()
    }

    /** Drops tokens *and* the on-device engine; used on settings changes. */
    fun invalidateAll() {
        (androidProvider as? AndroidPoTokenProvider)?.invalidateAll()
            ?: androidProvider.invalidate()
        external.invalidate()
    }

    private fun mode(): String =
        settings.stringSetting(KEY_MODE, valid = VALID_MODES)?.trim()?.lowercase().orEmpty()

    private fun baseUrl(): String? =
        settings.stringSetting(KEY_URL)?.normalizedPoTokenBaseUrl()

    /** One-line state for logs — never includes the URL value or any token. */
    fun describeState(): String {
        val mode = mode()
        val urlConfigured = baseUrl() != null
        return buildString {
            append("potoken: mode=").append(mode.ifEmpty { "<unset>" })
            append(" urlConfigured=").append(urlConfigured)
            append(" kind=").append(kind)
            if (mode == MODE_ANDROID) {
                append(" onDeviceBroken=").append(
                    (androidProvider as? AndroidPoTokenProvider)?.permanentlyBroken ?: false
                )
            }
        }
    }

    companion object {
        const val KEY_MODE = "potoken_mode"
        const val KEY_URL = "potoken_url"
        const val MODE_OFF = "off"
        const val MODE_ANDROID = "android"
        const val MODE_EXTERNAL = "external"

        /**
         * `potoken_mode` shipped as a `SettingMultipleChoice` in an older
         * experimental build, so some installs hold a `Set<String>` here;
         * [dev.brahmkshatriya.echo.extension.utils.stringSetting] heals that.
         */
        val VALID_MODES = setOf(MODE_OFF, MODE_ANDROID, MODE_EXTERNAL)
    }
}

/**
 * Talks to a `bgutil-ytdlp-pot-provider` compatible HTTP server.
 *
 * ```
 * POST {base}/get_pot  {"content_binding": "<videoId>"}
 *   -> {"poToken": "...", "contentBinding": "...", "expiresAt": "..."}
 * ```
 *
 * The server rejects requests that carry an `Origin` header, so none is sent.
 * Tokens live in memory only and are keyed by their content binding.
 */
class ExternalPoTokenProvider(
    private val baseUrl: () -> String?
) : PoTokenProvider {

    private data class Cached(val token: String, val expiresAt: Long)

    override val kind: String = "external"

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private val lock = Mutex()

    override suspend fun token(contentBinding: String?): String? = withContext(Dispatchers.IO) {
        val binding = contentBinding?.trim().orEmpty()
        if (binding.isEmpty()) return@withContext null

        val base = baseUrl()?.trim()?.trimEnd('/')
        if (base.isNullOrEmpty()) return@withContext null

        lock.withLock {
            val now = System.currentTimeMillis()
            val hit = cache[binding]
            if (hit != null) {
                if (hit.expiresAt > now) return@withLock hit.token
                cache.remove(binding)
            }

            val fetched = runCatching { fetch(base, binding) }.getOrNull()
                ?: return@withLock null
            cache[binding] = fetched
            fetched.token
        }
    }

    override fun invalidate() {
        cache.clear()
    }

    private fun fetch(base: String, binding: String): Cached? {
        val payload = buildJsonObject { put("content_binding", binding) }
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$base/get_pot")
            .post(payload)
            .header("Content-Type", "application/json")
            .build()

        StreamHttpClient.token.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return null

            val element = json.parseToJsonElement(text).jsonObject
            val token = element["poToken"]?.jsonPrimitive?.contentOrNull
                ?: element["po_token"]?.jsonPrimitive?.contentOrNull
                ?: return null
            if (token.isBlank()) return null

            val expiresAt = element["expiresAt"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: (System.currentTimeMillis() + DEFAULT_TTL_MS)

            val now = System.currentTimeMillis()
            return Cached(token, expiresAt.coerceIn(now + MIN_TTL_MS, now + MAX_TTL_MS))
        }
    }

    private companion object {
        const val DEFAULT_TTL_MS = 25 * 60 * 1000L
        const val MIN_TTL_MS = 60 * 1000L
        const val MAX_TTL_MS = 30 * 60 * 1000L
    }
}
