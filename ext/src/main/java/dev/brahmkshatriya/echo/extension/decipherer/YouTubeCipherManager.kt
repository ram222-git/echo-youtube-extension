package dev.brahmkshatriya.echo.extension.decipherer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object YouTubeCipherManager {
    private const val REMOTE_CONFIG_URL =
        "https://raw.githubusercontent.com/maxrave-dev/simpmusic-files/main/registry/player_configs.json"
    private const val DEFAULT_PLAYER_HASH = "4fd832e7"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    data class PlayerConfig(
        val sigJsExpression: String,
        val nClass: String,
        val signatureTimestamp: Int
    )

    // Hardcoded known fallbacks
    private val fallbackConfigs = mapOf(
        "4fd832e7" to PlayerConfig("\$P(88,5074,INPUT)", "VB", 20711),
        "270aba89" to PlayerConfig("lr(88,5074,INPUT)", "YU", 20711),
        "b3f4bc95" to PlayerConfig("lr(88,5074,INPUT)", "YU", 20711),
        "b7ed6f4b" to PlayerConfig("\$P(88,5074,INPUT)", "VB", 20711),
        "ce921c73" to PlayerConfig("\$P(88,5074,INPUT)", "VB", 20711),
        "5407e3d0" to PlayerConfig("mL(3,7009,INPUT)", "lx", 20712),
        "609426be" to PlayerConfig("Vp(3,7009,INPUT)", "MV", 20712),
        "6b77ebca" to PlayerConfig("DQ(3,7009,INPUT)", "dV", 20712),
        "9f972691" to PlayerConfig("DQ(3,7009,INPUT)", "dV", 20712)
    )


    @Volatile
    private var cachedConfigs: Map<String, PlayerConfig> = fallbackConfigs

    @Volatile
    private var lastConfigFetchTime = 0L

    // Cached Rhino runtime state per player hash
    private class RhinoPlayerContext(
        val playerHash: String,
        val scope: Scriptable
    )

    @Volatile
    private var activeRhinoContext: RhinoPlayerContext? = null

    suspend fun getPlayerConfig(playerHash: String): PlayerConfig? {
        val now = System.currentTimeMillis()
        if (now - lastConfigFetchTime > 6 * 3600 * 1000L || !cachedConfigs.containsKey(playerHash)) {
            refreshConfigs()
        }
        return cachedConfigs[playerHash] ?: fallbackConfigs[playerHash] ?: fallbackConfigs[DEFAULT_PLAYER_HASH]
    }

    private suspend fun refreshConfigs() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(REMOTE_CONFIG_URL)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string()
                if (!body.isNullOrBlank()) {
                    val root = json.parseToJsonElement(body).jsonObject
                    val players = root["players"]?.jsonObject
                    if (players != null) {
                        val parsed = mutableMapOf<String, PlayerConfig>()
                        for ((hash, elem) in players) {
                            val obj = elem.jsonObject
                            val sig = obj["sig"]?.jsonPrimitive?.content ?: continue
                            val nClass = obj["nClass"]?.jsonPrimitive?.content ?: continue
                            val sts = obj["sts"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20711
                            val config = PlayerConfig(sig, nClass, sts)
                            parsed[hash] = config
                            obj["aliases"]?.jsonArray?.forEach { aliasElem ->
                                val alias = aliasElem.jsonPrimitive.content
                                parsed[alias] = config
                            }
                        }
                        cachedConfigs = fallbackConfigs + parsed
                        lastConfigFetchTime = System.currentTimeMillis()
                        println("YouTubeCipherManager: loaded ${cachedConfigs.size} player configs")
                    }
                }
            }
        } catch (e: Exception) {
            println("YouTubeCipherManager: could not refresh configs (${e.message}), using fallbacks")
        }
    }

    private suspend fun getOrCreateRhinoContext(playerHash: String): RhinoPlayerContext = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = activeRhinoContext
            if (current != null && current.playerHash == playerHash) {
                return@withLock current
            }

            val config = getPlayerConfig(playerHash)
                ?: throw IllegalStateException("No player config found for $playerHash")

            val baseJsUrl = "https://www.youtube.com/s/player/$playerHash/player_ias.vflset/en_US/base.js"
            println("YouTubeCipherManager: Downloading player script from $baseJsUrl...")
            val req = Request.Builder()
                .url(baseJsUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to download player script: HTTP ${resp.code}")
            }
            val baseJs = resp.body?.string() ?: throw IllegalStateException("Empty player script")

            val sigExpression = config.sigJsExpression.replace("INPUT", "sig")
            val nClass = config.nClass
            val exports = "; window._cipherSigFunc=function(sig){try{return $sigExpression;}catch(e){return null;}}; " +
                    "window._nTransformFunc=function(n){try{var u=new g.$nClass('https://x.googlevideo.com/videoplayback?n='+n, true); var t=u.get('n'); return (t&&t!==n)?t:n;}catch(e){return n;}}; "

            val modifiedJs = if (baseJs.contains("})(_yt_player);")) {
                baseJs.replace("})(_yt_player);", "$exports })(_yt_player);")
            } else {
                "$baseJs\n$exports"
            }

            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1 // Interpret mode is fastest to initialize for large scripts
                val scope = cx.initSafeStandardObjects()

                val setupScript = """
                    var window = this;
                    var globalThis = this;
                    var self = this;
                    var XMLHttpRequest = function() {};
                    XMLHttpRequest.prototype = {};
                    var document = {};
                    var navigator = { userAgent: 'Mozilla/5.0' };
                    var location = {
                        href: 'https://www.youtube.com/watch?v=echo',
                        hostname: 'www.youtube.com',
                        host: 'www.youtube.com',
                        protocol: 'https:',
                        pathname: '/watch',
                        search: '?v=echo'
                    };
                    window.location = location;
                """.trimIndent()

                cx.evaluateString(scope, setupScript, "setup", 1, null)
                cx.evaluateString(scope, modifiedJs, "player_$playerHash.js", 1, null)

                val newContext = RhinoPlayerContext(playerHash, scope)
                activeRhinoContext = newContext
                println("YouTubeCipherManager: Successfully initialized Rhino context for $playerHash")
                return@withLock newContext
            } finally {
                Context.exit()
            }
        }
    }

    suspend fun deobfuscateUrl(
        cipherString: String,
        playerHash: String = DEFAULT_PLAYER_HASH
    ): String = withContext(Dispatchers.Default) {
        val params = parseQueryString(cipherString)
        val s = params["s"] ?: throw IllegalArgumentException("Cipher string missing 's' parameter")
        val sp = params["sp"] ?: "sig"
        val rawUrl = params["url"] ?: throw IllegalArgumentException("Cipher string missing 'url' parameter")

        val rhino = getOrCreateRhinoContext(playerHash)

        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            // Solve signature
            val solvedSig = cx.evaluateString(rhino.scope, "window._cipherSigFunc('$s')", "sig", 1, null)?.toString()
            if (solvedSig.isNullOrBlank() || solvedSig == "null") {
                throw IllegalStateException("Failed to decipher signature for player $playerHash")
            }

            // Check and solve n parameter if present in stream URL
            var finalUrl = rawUrl
            val nRegex = Regex("""([?&])n=([^&]+)""")
            val nMatch = nRegex.find(finalUrl)
            var solvedNResult: String? = null
            if (nMatch != null) {
                val nParam = nMatch.groupValues[2]
                val solvedN = cx.evaluateString(rhino.scope, "window._nTransformFunc('$nParam')", "n", 1, null)?.toString()
                solvedNResult = solvedN
                if (!solvedN.isNullOrBlank() && solvedN != "null" && solvedN != nParam) {
                    finalUrl = finalUrl.replace("${nMatch.groupValues[1]}n=$nParam", "${nMatch.groupValues[1]}n=$solvedN")
                }
            }

            println("YouTubeCipherManager: solvedSig=$solvedSig, originalN=${nMatch?.groupValues?.get(2)}, solvedN=$solvedNResult")

            val separator = if (finalUrl.contains("?")) "&" else "?"
            "$finalUrl$separator$sp=$solvedSig"

        } finally {
            Context.exit()
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (part in query.split("&")) {
            val idx = part.indexOf("=")
            if (idx != -1) {
                val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }
}
