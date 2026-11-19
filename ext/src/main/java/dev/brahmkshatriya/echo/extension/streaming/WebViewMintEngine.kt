package dev.brahmkshatriya.echo.extension.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Android half of [AndroidPoTokenProvider]: a headless WebView that runs the
 * BotGuard attestation and mints video-ID-bound PO Tokens. Kotlin drives every
 * HTTP request through a cookie-less OkHttp client and injects the interpreter
 * into the page with `evaluateJavascript`, so the WebView itself performs **no
 * network I/O** and Google session cookies never touch minting traffic
 * (`docs/PO_TOKEN_SERVERLESS_RESEARCH.md` §5).
 *
 * Protocol (validated on device by the `test` branch, still current per
 * `scratch/pot-provider/mint.mjs`, 2026-11-19):
 *
 *  1. Boot: headless WebView loads [POT_BOOTSTRAP_HTML] (baseURL youtube.com).
 *  2. `POST api/jnn/v1/Create [requestKey]` → descramble (+97) →
 *     `evaluateJavascript(runBotGuard(challenge))` → bridge response.
 *  3. `POST api/jnn/v1/GenerateIT [requestKey, response]` → integrity token +
 *     lifetime → `evaluateJavascript(createPoTokenMinter(...))`.
 *  4. `mint(videoId)` → `evaluateJavascript(obtainPoToken(bytes))` → websafe
 *     base64 token via the bridge.
 *
 * This class is only loaded when an Android context exists
 * ([AndroidPoTokenEngineFactory]); plain-JVM unit tests never touch it.
 */
internal class WebViewMintEngine private constructor(
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val readyContinuation: kotlinx.coroutines.CancellableContinuation<WebViewMintEngine>
) : PoTokenMintEngine {

    private val exceptionHandler = CoroutineExceptionHandler { _, t -> signalError(t) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val readyDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pageLoadedHandled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingMints =
        Collections.synchronizedMap(LinkedHashMap<String, kotlinx.coroutines.CancellableContinuation<String>>())

    @Volatile
    private var expiryMs = 0L

    override val isExpired: Boolean
        get() = System.currentTimeMillis() > expiryMs

    // ------------------------------------------------------------------
    // PoTokenMintEngine
    // ------------------------------------------------------------------

    override suspend fun mint(contentBinding: String): String = suspendCancellableCoroutine { cont ->
        synchronized(pendingMints) { pendingMints[contentBinding] = cont }
        cont.invokeOnCancellation {
            synchronized(pendingMints) { pendingMints.remove(contentBinding) }
        }
        val bindingArg = jsUint8ArrayFromString(contentBinding)
        val bindingLiteral = jsString(contentBinding)
        val script = """
            (function() {
                try {
                    obtainPoToken($bindingArg).then(function(u8) {
                        var bin = '';
                        for (var i = 0; i < u8.length; i++) bin += String.fromCharCode(u8[i]);
                        PotBridge.onMintOk($bindingLiteral, btoa(bin));
                    }, function(e) {
                        PotBridge.onMintErr($bindingLiteral, 'mint: ' + (e && e.stack || e));
                    });
                } catch (e) { PotBridge.onMintErr($bindingLiteral, 'mint-throw: ' + (e && e.stack || e)); }
            })();
            """.trimIndent()
        mainHandler.post {
            if (closed.get()) {
                synchronized(pendingMints) { pendingMints.remove(contentBinding) }
                cont.resumeWithException(IllegalStateException("mint engine closed"))
                return@post
            }
            try {
                webView.evaluateJavascript(script, null)
            } catch (t: Throwable) {
                synchronized(pendingMints) { pendingMints.remove(contentBinding) }
                cont.resumeWithException(t)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        runOnMain {
            try {
                webView.clearHistory()
                webView.clearCache(true)
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.removeAllViews()
                webView.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Boot + state machine
    // ------------------------------------------------------------------

    fun startBootstrap() {
        runOnMain {
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                POT_BOOTSTRAP_HTML,
                "text/html",
                "utf-8",
                null
            )
        }
    }

    @JavascriptInterface
    fun onPageLoaded() {
        if (!pageLoadedHandled.compareAndSet(false, true)) return
        dispatch {
            log("page loaded - requesting challenge")
            val body = waaPost(CREATE_URL, """[ "$REQUEST_KEY" ]""")
            val challengeJson = parseCreateChallengeToJson(body)
            eval(
                """
                (function() {
                    try {
                        var data = $challengeJson;
                        runBotGuard(data).then(function(r) {
                            window.__potWebPoSignalOutput = r.webPoSignalOutput;
                            PotBridge.onBotGuardReady(r.botguardResponse);
                        }, function(e) {
                            PotBridge.onFatalError('botguard: ' + (e && e.stack || e));
                        });
                    } catch (e) { PotBridge.onFatalError('boot: ' + (e && e.stack || e)); }
                })();
                """.trimIndent()
            )
        }
    }

    @JavascriptInterface
    fun onBotGuardReady(botguardResponse: String) {
        dispatch {
            log("botguard executed - requesting integrity token")
            val body = waaPost(GENERATE_IT_URL, """[ "$REQUEST_KEY", "$botguardResponse" ]""")
            val (tokenJs, lifetimeSec) = parseIntegrityToken(body)
            expiryMs = System.currentTimeMillis() + lifetimeSec * 1000 - 10 * 60 * 1000
            eval(
                """
                (function() {
                    try {
                        window.__potIntegrityToken = $tokenJs;
                        createPoTokenMinter(window.__potWebPoSignalOutput, window.__potIntegrityToken).then(function() {
                            PotBridge.onMinterReady();
                        }, function(e) {
                            PotBridge.onFatalError('minter: ' + (e && e.stack || e));
                        });
                    } catch (e) { PotBridge.onFatalError('minter-setup: ' + (e && e.stack || e)); }
                })();
                """.trimIndent()
            )
        }
    }

    @JavascriptInterface
    fun onMinterReady() {
        dispatch {
            log("minter ready")
            if (readyDone.compareAndSet(false, true)) {
                readyContinuation.resume(this@WebViewMintEngine)
            }
        }
    }

    @JavascriptInterface
    fun onFatalError(error: String) {
        dispatch {
            System.err.println("$TAG: fatal JS error: $error")
            signalError(RuntimeException(error))
        }
    }

    @JavascriptInterface
    fun onMintOk(identifier: String, base64Token: String) {
        dispatch {
            val webSafe = base64Token.replace('+', '-').replace('/', '_').trimEnd('=')
            val cont = synchronized(pendingMints) { pendingMints.remove(identifier) }
            cont?.resume(webSafe)
        }
    }

    @JavascriptInterface
    fun onMintErr(identifier: String, error: String) {
        dispatch {
            println("$TAG: mint failed: $error")
            val cont = synchronized(pendingMints) { pendingMints.remove(identifier) }
            cont?.resumeWithException(RuntimeException(error))
        }
    }

    private fun signalError(error: Throwable) {
        if (readyDone.compareAndSet(false, true)) {
            readyContinuation.resumeWithException(
                if (error.message?.contains("renderer") == true) {
                    BrokenMintEngineException(error.message ?: "renderer gone")
                } else {
                    error
                }
            )
        }
        val continuations = synchronized(pendingMints) {
            val list = ArrayList(pendingMints.values)
            pendingMints.clear()
            list
        }
        continuations.forEach { it.resumeWithException(error) }
        close()
    }

    /** Bridge callbacks arrive on a WebView worker thread; work continues off-main, WebView calls hop to main. */
    private fun dispatch(action: suspend () -> Unit) {
        scope.launch(exceptionHandler) {
            if (!closed.get()) action()
        }
    }

    private suspend fun eval(script: String) {
        withMain { webView.evaluateJavascript(script, null) }
    }

    private fun runOnMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    /** Runs [block] on the main thread without needing the coroutines-android `Dispatchers.Main`. */
    private suspend fun <T> withMain(block: () -> T): T = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            if (cont.isActive) {
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "WebViewMintEngine"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
        private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
        private const val GOOG_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Cookie-less by design: minting traffic must never carry session cookies. */
        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Builds and boots an engine; suspends until the minter is ready or boot
         * fails. The WebView (and every later call into it) MUST run on the main
         * thread, so construction is posted to the main looper — creating it on
         * the caller's (IO) thread throws and would disable minting.
         */
        @SuppressLint("SetJavaScriptEnabled")
        suspend fun create(context: Any): WebViewMintEngine = suspendCancellableCoroutine { cont ->
            val appContext = (context as Context).applicationContext
            val engineRef = java.util.concurrent.atomic.AtomicReference<WebViewMintEngine?>()
            cont.invokeOnCancellation { engineRef.get()?.close() }
            mainHandler.post {
                val engine: WebViewMintEngine
                try {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val pageLoadedFired = java.util.concurrent.atomic.AtomicBoolean(false)
                    lateinit var we: WebViewMintEngine
                    val webView = WebView(appContext).apply {
                        settings.javaScriptEnabled = true
                        settings.userAgentString = BROWSER_UA
                        settings.blockNetworkLoads = false // no remote page is ever loaded; TODO(device-verify): try true
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                if (!we.closed.get() && message.message()?.contains("Uncaught") == true) {
                                    we.signalError(
                                        RuntimeException("console: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                                    )
                                }
                                return super.onConsoleMessage(message)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != "about:blank" && pageLoadedFired.compareAndSet(false, true)) {
                                    log("WebView onPageFinished")
                                    we.onPageLoaded()
                                }
                            }

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: android.webkit.RenderProcessGoneDetail
                            ): Boolean {
                                log("WebView renderer process gone")
                                we.signalError(BrokenMintEngineException("WebView renderer process gone"))
                                return true
                            }
                        }
                    }
                    we = WebViewMintEngine(webView, scope, cont)
                    engine = we
                    engineRef.set(engine)
                    webView.addJavascriptInterface(engine, "PotBridge")
                    engine.startBootstrap()
                } catch (t: Throwable) {
                    log("engine boot failed on main thread: ${t.javaClass.simpleName}: ${t.message}")
                    cont.resumeWithException(t)
                }
            }
        }

        private fun log(message: String) = println("$TAG: $message")

        // ------------------------------------------------------------------
        // JNN HTTP (no cookies, no Origin; token material never logged)
        // ------------------------------------------------------------------

        private suspend fun waaPost(url: String, bodyJson: String): String =
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json+protobuf")
                    .header("x-goog-api-key", GOOG_API_KEY)
                    .header("x-user-agent", "grpc-web-javascript/0.1")
                    .header("User-Agent", BROWSER_UA)
                    .post(bodyJson.toRequestBody("application/json+protobuf".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw RuntimeException("BotGuard HTTP ${response.code}")
                    body
                }
            }

        /**
         * Create response → `{program, globalName, interpreterJavascript:{privateDoNotAccessOrElseSafeScriptWrappedValue}}`
         * as a JS object literal. The scrambled variant carries the interpreter JS inline (+97 offset).
         */
        private fun parseCreateChallengeToJson(rawResponse: String): String {
            val arr = json.parseToJsonElement(rawResponse) as JsonArray
            val challenge: JsonArray = if (arr.size > 1 && arr[1] is JsonPrimitive) {
                json.parseToJsonElement(descramble(arr[1].jsonPrimitive.content)) as JsonArray
            } else {
                arr[0] as JsonArray
            }

            val program = (challenge[4] as JsonPrimitive).content
            val globalName = (challenge[5] as JsonPrimitive).content
            val interpreterJs = (challenge.getOrNull(1) as? JsonArray)
                ?.firstOrNull { it is JsonPrimitive && (it as JsonPrimitive).isString }
                ?.let { (it as JsonPrimitive).contentOrNull }

            return buildString {
                append("{")
                append("\"program\":").append(jsString(program)).append(",")
                append("\"globalName\":").append(jsString(globalName)).append(",")
                append("\"interpreterJavascript\":{\"privateDoNotAccessOrElseSafeScriptWrappedValue\":")
                if (interpreterJs != null) append(jsString(interpreterJs)) else append("null")
                append("}}")
            }
        }

        /** GenerateIT response → (JS `Uint8Array` literal of the integrity token, lifetime seconds). */
        private fun parseIntegrityToken(rawResponse: String): Pair<String, Long> {
            val arr = json.parseToJsonElement(rawResponse) as JsonArray
            val tokenB64 = (arr[0] as JsonPrimitive).content
            val lifetimeSec = (arr[1] as JsonPrimitive).content.toLongOrNull() ?: 21600L
            return jsUint8ArrayFromBase64(tokenB64) to lifetimeSec
        }

        private fun jsString(value: String): String =
            "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029") + "\""

        private fun jsUint8ArrayFromString(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"
        }

        private fun jsUint8ArrayFromBase64(base64: String): String {
            val clean = base64.trim().replace('-', '+').replace('_', '/').replace(".", "")
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            val bytes = Base64.decode(padded, Base64.DEFAULT)
            return "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"
        }

        private fun descramble(base64Payload: String): String {
            val clean = base64Payload.trim().replace('-', '+').replace('_', '/').replace(".", "")
            val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
            val bytes = Base64.decode(padded, Base64.DEFAULT)
            val out = ByteArray(bytes.size)
            for (i in bytes.indices) out[i] = (bytes[i] + 97).toByte()
            return String(out, Charsets.UTF_8)
        }
    }

    // ------------------------------------------------------------------
    // Bootstrap HTML: BotGuard client JS (protocol per bgutils-js / ArchiveTune;
    // device-validated on the test branch). Runs in the WebView only.
    // ------------------------------------------------------------------

    private val POT_BOOTSTRAP_HTML = """<!DOCTYPE html>
<html><head><title></title><script>
var bgVm = null;
var bgProgram = null;
var bgVmFunctions = null;
var poTokenMinter = null;

function loadBotGuard(challengeData) {
    bgVm = window[challengeData.globalName];
    bgProgram = challengeData.program;
    bgVmFunctions = null;
    if (!bgVm) throw new Error('VM not found in global object');
    if (!bgVm.a) throw new Error('Could not load program');

    var vmFunctionsCallback = function (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) {
        bgVmFunctions = {
            asyncSnapshotFunction: asyncSnapshotFunction,
            shutdownFunction: shutdownFunction,
            passEventFunction: passEventFunction,
            checkCameraFunction: checkCameraFunction
        };
    };

    bgVm.a(bgProgram, vmFunctionsCallback, true, undefined, function () {}, [ [], [] ]);

    return new Promise(function (resolve, reject) {
        var attempts = 0;
        var maxAttempts = 10000;
        var checkInterval = setInterval(function () {
            if (bgVmFunctions && bgVmFunctions.asyncSnapshotFunction) {
                clearInterval(checkInterval);
                resolve({ vmFunctions: bgVmFunctions, vm: bgVm, program: bgProgram });
            } else if (attempts >= maxAttempts) {
                clearInterval(checkInterval);
                reject(new Error('Timeout waiting for asyncSnapshotFunction'));
            }
            attempts++;
        }, 1);
    });
}

function snapshot(botguard, args) {
    return new Promise(function (resolve, reject) {
        try {
            botguard.vmFunctions.asyncSnapshotFunction(
                function (response) { resolve(response); },
                [args.contentBinding, args.signedTimestamp, args.webPoSignalOutput, args.skipPrivacyBuffer]
            );
        } catch (e) {
            reject(new Error('Snapshot failed: ' + e.message));
        }
    });
}

function b64ToU8(b64) {
    var norm = b64.replace(/-/g, '+').replace(/_/g, '/').replace(/\./g, '=');
    while (norm.length % 4 !== 0) norm += '=';
    var bin = atob(norm);
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
}

function runBotGuard(challengeData) {
    var interpreterJavascript = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
    if (interpreterJavascript) {
        new Function(interpreterJavascript)();
    } else {
        throw new Error('Could not load VM - no interpreter JavaScript');
    }
    var webPoSignalOutput = [];
    return loadBotGuard({ globalName: challengeData.globalName, program: challengeData.program })
        .then(function (botguard) {
            return snapshot(botguard, { contentBinding: undefined, signedTimestamp: undefined, webPoSignalOutput: webPoSignalOutput, skipPrivacyBuffer: false });
        })
        .then(function (botguardResponse) {
            return { webPoSignalOutput: webPoSignalOutput, botguardResponse: botguardResponse };
        });
}

function createPoTokenMinter(webPoSignalOutput, integrityTokenBytes) {
    return Promise.resolve().then(function () {
        var getMinter = webPoSignalOutput[0];
        if (!getMinter) throw new Error('PMD:Undefined - webPoSignalOutput[0] missing');
        return Promise.resolve(getMinter(integrityTokenBytes)).then(function (mintCallback) {
            if (typeof mintCallback !== 'function') throw new Error('APF:Failed');
            poTokenMinter = mintCallback;
        });
    });
}

function obtainPoToken(identifier) {
    return Promise.resolve().then(function () {
        if (!poTokenMinter) throw new Error('MNT:NotInit');
        return Promise.resolve(poTokenMinter(identifier)).then(function (result) {
            if (!result) throw new Error('YNJ:Undefined');
            return result;
        });
    });
}

if (document.readyState === 'complete' || document.readyState === 'interactive') {
    setTimeout(function() { try { if (window.PotBridge && window.PotBridge.onPageLoaded) PotBridge.onPageLoaded(); } catch(e){} }, 10);
} else {
    window.addEventListener('DOMContentLoaded', function() {
        try { if (window.PotBridge && window.PotBridge.onPageLoaded) PotBridge.onPageLoaded(); } catch(e){}
    });
}
</script></head><body></body></html>
"""
}
