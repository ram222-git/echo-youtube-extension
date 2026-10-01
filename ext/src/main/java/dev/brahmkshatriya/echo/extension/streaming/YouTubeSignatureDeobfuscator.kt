package dev.brahmkshatriya.echo.extension.streaming

import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern

data class DecipherResult(val sig: String?, val n: String?)

object YouTubeSignatureDeobfuscator {
    private val client by lazy { OkHttpClient() }

    @Volatile
    private var isInitialized = false

    private var rhinoScope: Scriptable? = null
    private var solveBothFunction: Function? = null

    private val lock = Any()

    private const val FALLBACK_BASE_JS = "https://www.youtube.com/s/player/57bae81f/player_ias.vflset/en_GB/base.js"

    private fun fetchBaseJsUrl(videoId: String): String {
        return runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/iframe_api")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val resp = client.newCall(req).execute().body?.string().orEmpty()
            val matcher = Pattern.compile("player\\\\/([a-z0-9]{8})\\\\/").matcher(resp)
            if (matcher.find()) {
                val hash = matcher.group(1)
                "https://www.youtube.com/s/player/$hash/player_ias.vflset/en_GB/base.js"
            } else {
                FALLBACK_BASE_JS
            }
        }.getOrDefault(FALLBACK_BASE_JS)
    }

    private fun fetchBaseJsCode(videoId: String): String {
        val url = FALLBACK_BASE_JS
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        val response = client.newCall(req).execute()
        return response.body?.string() ?: error("Failed to download YouTube base.js")
    }

    private fun initSolver(videoId: String) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return

            println("YouTubeSignatureDeobfuscator: Initializing signature solver...")
            val baseJs = fetchBaseJsCode(videoId)

            val pattern = Pattern.compile("([a-zA-Z0-9_$]+)=function\\([a-zA-Z0-9_$]+,[a-zA-Z0-9_$]+,[a-zA-Z0-9_$]+\\)\\{[^}]*?new [a-zA-Z0-9_$.]+\\([^,]+,!0\\);[a-zA-Z0-9_$]+\\.set\\(\"alr\",\\s*\"yes\"\\);")
            val matcher = pattern.matcher(baseJs)
            if (!matcher.find()) {
                error("Could not find ALR decipher function in base.js")
            }
            val alrFn = matcher.group(1)
            println("YouTubeSignatureDeobfuscator: Found ALR decipher function: $alrFn")

            val injectCode = """
                globalThis._solveBoth = function(sig, n) {
                    var u = $alrFn("https://youtube.com/watch?v=yt-dlp-wins", "s", sig ? encodeURIComponent(sig) : undefined);
                    if (n) u.set("n", n);
                    var proto = Object.getPrototypeOf(u);
                    var keys = Object.keys(proto).concat(Object.getOwnPropertyNames(proto));
                    for (var i = 0; i < keys.length; i++) {
                        var key = keys[i];
                        if (!["constructor", "set", "get", "clone"].includes(key)) {
                            u[key]();
                            break;
                        }
                    }
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
                cx.optimizationLevel = -1
                val scope = cx.initSafeStandardObjects()
                cx.evaluateString(scope, patchedJs, "base.js", 1, null)
                val fn = scope.get("_solveBoth", scope) as? Function
                    ?: error("Could not find _solveBoth in scope")
                rhinoScope = scope
                solveBothFunction = fn
                isInitialized = true
                println("YouTubeSignatureDeobfuscator: Solver initialized successfully!")
            } finally {
                Context.exit()
            }
        }
    }

    fun deobfuscateBoth(videoId: String, signature: String?, n: String?): DecipherResult {
        initSolver(videoId)
        synchronized(lock) {
            val scope = rhinoScope ?: error("Rhino scope not initialized")
            val fn = solveBothFunction ?: error("Solve function not initialized")
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                val res = fn.call(cx, scope, scope, arrayOf(signature, n)) as? NativeObject
                    ?: error("Deobfuscation returned null or invalid object")
                val sig = res.get("sig", res)?.toString()?.takeIf { it != "null" && it != "undefined" }
                val solvedN = res.get("n", res)?.toString()?.takeIf { it != "null" && it != "undefined" }
                return DecipherResult(sig, solvedN)
            } finally {
                Context.exit()
            }
        }
    }

    fun deobfuscateStreamUrl(
        videoId: String,
        rawUrl: String,
        signature: String?,
        signatureParam: String = "sig"
    ): String {
        return runCatching {
            val nMatcher = Pattern.compile("(?:[?&])n=([^&]+)").matcher(rawUrl)
            val origN = if (nMatcher.find()) nMatcher.group(1) else null

            val decipherResult = deobfuscateBoth(videoId, signature, origN)

            var resultUrl = rawUrl
            if (!decipherResult.n.isNullOrEmpty() && !origN.isNullOrEmpty()) {
                resultUrl = resultUrl.replaceFirst("n=$origN", "n=${decipherResult.n}")
            }
            if (!decipherResult.sig.isNullOrEmpty()) {
                val sep = if (resultUrl.contains("?")) "&" else "?"
                resultUrl = "$resultUrl$sep$signatureParam=${decipherResult.sig}"
            }
            resultUrl
        }.getOrElse {
            var fallback = rawUrl
            if (!signature.isNullOrEmpty()) {
                val sep = if (fallback.contains("?")) "&" else "?"
                fallback = "$fallback$sep$signatureParam=$signature"
            }
            fallback
        }
    }
}
