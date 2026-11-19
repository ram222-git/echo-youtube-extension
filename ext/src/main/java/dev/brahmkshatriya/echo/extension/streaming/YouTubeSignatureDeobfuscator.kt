package dev.brahmkshatriya.echo.extension.streaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class DecipherResult(val sig: String?, val n: String?)

/**
 * Solves YouTube's `s` (signature) and `n` (throttling) URL parameters with Rhino.
 *
 * Solving `n` is only a throughput optimisation: an unsolved `n` still plays, just
 * throttled. Because the solver needs to download and evaluate a multi-megabyte
 * `base.js` (seconds on a phone), it must never block the first playback request.
 * [deobfuscateStreamUrl] therefore only blocks when a real signature has to be
 * deciphered; otherwise it warms the solver up in the background and returns the
 * URL untouched. Results are cached, and a failed initialisation is not retried
 * for [INIT_RETRY_MS] so a stale `base.js` cannot re-download once per format.
 */
object YouTubeSignatureDeobfuscator {
    private val client by lazy { StreamHttpClient.solver }

    @Volatile
    private var isInitialized = false

    @Volatile
    private var initInProgress = false

    @Volatile
    private var initFailedAt = 0L

    @Volatile
    private var playerJsUrl: String? = null

    /** The `base.js` the currently evaluated Rhino scope was built from. */
    @Volatile
    private var loadedPlayerJsUrl: String? = null

    @Volatile
    private var discoveredPlayerJsUrl: String? = null

    @Volatile
    private var discoveredPlayerJsAt = 0L

    private var rhinoScope: Scriptable? = null
    private var solveBothFunction: Function? = null

    /** Guards Rhino access (init + solving). */
    private val lock = Any()

    /** Guards the init flags without blocking behind a long Rhino evaluation. */
    private val initStateLock = Any()

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val solvedCache = ConcurrentHashMap<String, DecipherResult>()

    private const val INIT_RETRY_MS = 10 * 60 * 1000L
    private const val PLAYER_JS_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_SOLVED_CACHE = 256
    private const val PLAYER_JS_ORIGIN = "https://www.youtube.com"
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    private const val FALLBACK_BASE_JS = "https://www.youtube.com/s/player/57bae81f/player_ias.vflset/en_GB/base.js"

    private val PLAYER_ID_PATTERN = Pattern.compile("/s/player/([A-Za-z0-9_-]{8,})/")
    private val JS_URL_PATTERN = Pattern.compile(
        "\"PLAYER_JS_URL\"\\s*:\\s*\"([^\"]+)\"|\"jsUrl\"\\s*:\\s*\"([^\"]+)\""
    )

    private val ALR_PATTERN = Pattern.compile(
        "([a-zA-Z0-9_$]+)=function\\([a-zA-Z0-9_$]+,[a-zA-Z0-9_$]+,[a-zA-Z0-9_$]+\\)\\{[^}]*?new [a-zA-Z0-9_$.]+\\([^,]+,!0\\);[a-zA-Z0-9_$]+\\.set\\(\"alr\",\\s*\"yes\"\\);"
    )
    private val N_PARAM_PATTERN = Pattern.compile("(?:[?&])n=([^&]+)")

    /**
     * Tells the solver which `base.js` the current player response was built with.
     * Passing the real one avoids deciphering against a fixed, stale player hash.
     *
     * A `null` [url] is ignored on purpose: some clients (notably `WEB_REMIX`) no
     * longer return an `assets.js`, and that must not erase a player URL that was
     * already resolved or discovered for this session.
     */
    fun setPlayerJsUrl(url: String?) {
        val normalized = normalizePlayerJsUrl(url) ?: return
        if (normalized == playerJsUrl) return
        playerJsUrl = normalized
        synchronized(lock) {
            if (loadedPlayerJsUrl != null && loadedPlayerJsUrl != normalized) {
                resetSolverLocked()
            }
        }
    }

    /**
     * Normalises any player URL the API hands us to the current `player_ias`
     * variant of the same player build.
     *
     * All variants of one build (`player_es6`, `player_es6_tcc`, `player_ias`, ...)
     * share the same signature algorithm, but the regex that locates the
     * decipher function is only stable on `player_ias`. yt-dlp makes the same
     * choice, and the watch page now advertises `player_es6` instead.
     */
    private fun normalizePlayerJsUrl(url: String?): String? {
        val raw = url?.replace("\\/", "/")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val absolute = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$PLAYER_JS_ORIGIN$raw"
            else -> raw
        }
        if (absolute.contains("tv-player")) return absolute
        val matcher = PLAYER_ID_PATTERN.matcher(absolute)
        if (!matcher.find()) return absolute
        return "$PLAYER_JS_ORIGIN/s/player/${matcher.group(1)}/player_ias.vflset/en_US/base.js"
    }

    /**
     * Resolves the player build currently served by YouTube. The player response
     * no longer carries `assets.js`, so the build hash is read from the public
     * homepage once and cached. Without this the solver would keep deciphering
     * against [FALLBACK_BASE_JS] and produce signatures the CDN rejects.
     */
    private fun resolveCurrentPlayerJsUrl(): String? {
        val cached = discoveredPlayerJsUrl
        if (cached != null && System.currentTimeMillis() - discoveredPlayerJsAt < PLAYER_JS_TTL_MS) {
            return cached
        }
        return runCatching {
            val request = Request.Builder()
                .url("$PLAYER_JS_ORIGIN/")
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Failed to download the YouTube homepage: HTTP ${response.code}")
                response.body?.string() ?: error("Empty YouTube homepage")
            }
            val matcher = JS_URL_PATTERN.matcher(html)
            val found = if (matcher.find()) matcher.group(1) ?: matcher.group(2) else null
            normalizePlayerJsUrl(found)?.also { resolved ->
                discoveredPlayerJsUrl = resolved
                discoveredPlayerJsAt = System.currentTimeMillis()
                println("YouTubeSignatureDeobfuscator: Discovered current player JS: $resolved")
            }
        }.getOrNull()
    }

    private fun resetSolverLocked() {
        isInitialized = false
        rhinoScope = null
        solveBothFunction = null
        loadedPlayerJsUrl = null
        solvedCache.clear()
        initFailedAt = 0L
        println("YouTubeSignatureDeobfuscator: Player JS changed, solver will be rebuilt")
    }

    fun isReady(): Boolean = isInitialized

    /** Initialises the solver off the playback path. Safe to call repeatedly. */
    fun warmUpAsync() {
        if (isInitialized) return
        synchronized(initStateLock) {
            if (isInitialized || initInProgress || isRateLimited()) return
            initInProgress = true
        }
        initScope.launch {
            try {
                initSolver()
                println("YouTubeSignatureDeobfuscator: Solver warmed up in background")
            } catch (t: Throwable) {
                println("YouTubeSignatureDeobfuscator: Background warm-up failed: ${t.message}")
            } finally {
                initInProgress = false
            }
        }
    }

    private fun isRateLimited(): Boolean =
        initFailedAt != 0L && System.currentTimeMillis() - initFailedAt < INIT_RETRY_MS

    private data class PlayerJsSource(val url: String, val code: String)

    private fun fetchBaseJsCode(): PlayerJsSource {
        val candidates = listOfNotNull(
            playerJsUrl,
            resolveCurrentPlayerJsUrl(),
            FALLBACK_BASE_JS
        ).distinct()

        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .build()
                val code = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Failed to download YouTube base.js: HTTP ${response.code}")
                    response.body?.string() ?: error("Failed to download YouTube base.js")
                }
                return PlayerJsSource(url, code)
            } catch (t: Throwable) {
                lastError = t
                println("YouTubeSignatureDeobfuscator: Could not load $url (${t.message})")
            }
        }
        throw lastError ?: IllegalStateException("No YouTube base.js source available")
    }

    private fun initSolver() {
        if (isInitialized) return
        if (isRateLimited()) {
            error("Signature solver unavailable after a previous failure")
        }
        synchronized(lock) {
            if (isInitialized) return
            try {
                println("YouTubeSignatureDeobfuscator: Initializing signature solver...")
                val playerJs = fetchBaseJsCode()
                val baseJs = playerJs.code

                val matcher = ALR_PATTERN.matcher(baseJs)
                if (!matcher.find()) {
                    error("Could not find ALR decipher function in base.js")
                }
                val alrFn = matcher.group(1)
                println("YouTubeSignatureDeobfuscator: Found ALR decipher function: $alrFn")

                val injectCode = """
                    globalThis._solveBoth = function(sig, n) {
                        var u = $alrFn("https://youtube.com/watch?v=yt-dlp-wins", "s", sig ? encodeURIComponent(sig) : undefined);
                        if (n) u.set("n", n);
                        var s = u.get("s");
                        return {
                            sig: s ? decodeURIComponent(s) : null,
                            n: u.get("n") || null
                        };
                    };
                """.trimIndent()

                val closeIdx = baseJs.lastIndexOf("})(_yt_player);")
                if (closeIdx == -1) {
                    error("Could not find closure termination in base.js")
                }

                val patchedJs = buildString {
                    append("""
                        var globalThis = this;
                        globalThis.window = globalThis;
                        globalThis.self = globalThis;
                        globalThis.document = {};
                        globalThis.navigator = {};
                        globalThis.XMLHttpRequest = { prototype: {} };
                        globalThis.location = {
                            hash: "",
                            host: "www.youtube.com",
                            hostname: "www.youtube.com",
                            href: "https://www.youtube.com/watch?v=yt-dlp-wins",
                            pathname: "/watch",
                            protocol: "https:",
                            search: "?v=yt-dlp-wins"
                        };
                    """.trimIndent())
                    append("\n")
                    append(baseJs.substring(0, closeIdx))
                    append("\n")
                    append(injectCode)
                    append("\n")
                    append(baseJs.substring(closeIdx))
                }

                val cx = Context.enter()
                try {
                    // Interpreted mode: Android-safe (no runtime bytecode generation).
                    // Init now runs off the playback path, so this is acceptable.
                    cx.optimizationLevel = -1
                    val scope = cx.initSafeStandardObjects()
                    cx.evaluateString(scope, patchedJs, "base.js", 1, null)
                    val fn = scope.get("_solveBoth", scope) as? Function
                        ?: error("Could not find _solveBoth in scope")
                    rhinoScope = scope
                    solveBothFunction = fn
                    loadedPlayerJsUrl = playerJs.url
                    initFailedAt = 0
                    isInitialized = true
                    println("YouTubeSignatureDeobfuscator: Solver initialized successfully!")
                } finally {
                    Context.exit()
                }
            } catch (t: Throwable) {
                initFailedAt = System.currentTimeMillis()
                println("YouTubeSignatureDeobfuscator: Initialization failed, skipping solver for 10 minutes: ${t.message}")
                throw t
            }
        }
    }

    fun deobfuscateBoth(videoId: String, signature: String?, n: String?): DecipherResult {
        if (!isInitialized) initSolver()

        val cacheKey = "${signature ?: ""}|${n ?: ""}"
        solvedCache[cacheKey]?.let { return it }

        synchronized(lock) {
            solvedCache[cacheKey]?.let { return it }
            val scope = rhinoScope ?: error("Rhino scope not initialized")
            val fn = solveBothFunction ?: error("Solve function not initialized")
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                val res = fn.call(cx, scope, scope, arrayOf(signature, n)) as? NativeObject
                    ?: error("Deobfuscation returned null or invalid object")
                val sig = res.get("sig", res)?.toString()?.takeIf { it != "null" && it != "undefined" }
                val solvedN = res.get("n", res)?.toString()?.takeIf { it != "null" && it != "undefined" }
                val result = DecipherResult(sig, solvedN)
                if (solvedCache.size > MAX_SOLVED_CACHE) solvedCache.clear()
                solvedCache[cacheKey] = result
                return result
            } finally {
                Context.exit()
            }
        }
    }

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$key=$value"
    }

    fun deobfuscateStreamUrl(
        videoId: String,
        rawUrl: String,
        signature: String?,
        signatureParam: String = "sig"
    ): String {
        val nMatcher = N_PARAM_PATTERN.matcher(rawUrl)
        val origN = if (nMatcher.find()) nMatcher.group(1) else null

        if (signature.isNullOrEmpty()) {
            // Direct URL: nothing to decipher at all.
            if (origN == null) return rawUrl
            // Only the `n` throttling parameter is present. Solve it when the solver
            // is already warm (2 ms), otherwise return the URL as-is and warm up
            // in the background instead of stalling playback for seconds.
            if (!isInitialized) {
                warmUpAsync()
                return rawUrl
            }
        }

        return runCatching {
            val decipherResult = deobfuscateBoth(videoId, signature, origN)

            var resultUrl = rawUrl
            if (!decipherResult.n.isNullOrEmpty() && !origN.isNullOrEmpty()) {
                resultUrl = resultUrl.replaceFirst("n=$origN", "n=${decipherResult.n}")
            }
            if (!decipherResult.sig.isNullOrEmpty()) {
                resultUrl = appendQueryParam(resultUrl, signatureParam, decipherResult.sig)
            }
            resultUrl
        }.getOrElse {
            var fallback = rawUrl
            if (!signature.isNullOrEmpty()) {
                fallback = appendQueryParam(fallback, signatureParam, signature)
            }
            fallback
        }
    }
}
