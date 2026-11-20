package dev.brahmkshatriya.echo.extension.streaming

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A live BotGuard minting engine: ready to mint video-ID-bound tokens until its
 * integrity token expires. Implemented by [WebViewMintEngine] on Android; faked
 * in unit tests.
 */
interface PoTokenMintEngine {
    /** True once the integrity token lifetime has passed; the engine must be rebuilt. */
    val isExpired: Boolean

    /** Mints a token bound to [contentBinding] (a video ID), or throws. */
    suspend fun mint(contentBinding: String): String

    /** Tears down the underlying WebView. Idempotent. */
    fun close()
}

/**
 * Generates YouTube PO Tokens **on the device**, with no external server, by
 * running the BotGuard attestation inside a headless WebView
 * ([WebViewMintEngine]). Protocol, evidence and security notes:
 * `docs/PO_TOKEN_SERVERLESS_RESEARCH.md`.
 *
 * Every failure path returns `null` — a missing token must only mean "use the
 * token-free clients", never an exception in the extraction cascade. On JVM
 * (unit tests) there is no Android runtime, so the engine factory resolves no
 * context and the provider permanently reports itself unavailable.
 */
class AndroidPoTokenProvider(
    /** Builds a minting engine, or `null` when Android/WebView is unavailable. */
    private val engineFactory: suspend (context: Any?) -> PoTokenMintEngine? =
        AndroidPoTokenEngineFactory::create,
    /** Injectable clock for expiry tests. */
    private val now: () -> Long = System::currentTimeMillis
) : PoTokenProvider {

    override val kind: String = "android"

    /**
     * Set when the WebView itself proved unusable (renderer gone, engine boot
     * failed twice). The provider stops retrying per track; settings chain this
     * into its availability decision.
     */
    @Volatile
    var permanentlyBroken: Boolean = false
        private set

    private data class Cached(val token: String, val expiresAt: Long)

    private val mintMutex = Mutex()
    private val tokenCache = HashMap<String, Cached>()

    @Volatile
    private var engine: PoTokenMintEngine? = null

    override suspend fun token(contentBinding: String?): String? {
        val binding = contentBinding?.trim().orEmpty()
        if (binding.isEmpty()) return null
        if (permanentlyBroken) return null

        return try {
            mintMutex.withLock {
                now().let { t ->
                    tokenCache[binding]?.let { cached ->
                        if (cached.expiresAt > t) return cached.token
                        tokenCache.remove(binding)
                    }
                }

                val mintStarted = now()
                val token = mintWithFreshEngineIfNeeded(binding)
                if (token.isNullOrBlank()) return null
                tokenCache[binding] = Cached(token, now() + TOKEN_TTL_MS)
                PlaybackBench.mark("tokenReady", "kind=android binding=$binding took=${now() - mintStarted}ms")
                println("AndroidPoTokenProvider: minted PO token (binding=$binding, len=${token.length})")
                token
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            println("AndroidPoTokenProvider: minting failed (${t.javaClass.simpleName}: ${t.message})")
            null
        }
    }

    /** Drops every cached token. The engine survives: a rejected token does not mean a broken minter. */
    override fun invalidate() {
        synchronized(tokenCache) { tokenCache.clear() }
    }

    /** Drops the engine as well; used on settings changes and account switches. */
    fun invalidateAll() {
        invalidate()
        destroyEngine()
    }

    /**
     * Boots the WebView minting engine ahead of time. The engine boot (WebView +
     * BotGuard VM + attestation) is the slow half of the first mint, so doing it
     * while the user is browsing keeps the first play fast.
     */
    override suspend fun warmUp() {
        if (permanentlyBroken) return
        runCatching { mintMutex.withLock { ensureEngine() } }
    }

    private suspend fun mintWithFreshEngineIfNeeded(binding: String): String? {
        val current = ensureEngine() ?: return null
        val minted = try {
            withTimeoutOrNull(WARM_TIMEOUT_MS) { current.mint(binding) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
        if (!minted.isNullOrBlank()) return minted

        // Warm mint failed (stale engine, JS error, timeout): rebuild once before
        // giving up, mirroring the resolver's single-retry contract.
        println("AndroidPoTokenProvider: warm mint failed; rebuilding engine once")
        destroyEngine()
        val fresh = ensureEngine() ?: return null
        return try {
            withTimeoutOrNull(COLD_TIMEOUT_MS) { fresh.mint(binding) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun ensureEngine(): PoTokenMintEngine? {
        engine?.let { current ->
            if (!current.isExpired) return current
            println("AndroidPoTokenProvider: integrity token expired; rebuilding engine")
            destroyEngine()
        }

        val created = try {
            withContext(Dispatchers.IO) {
                withTimeout(COLD_TIMEOUT_MS) { engineFactory(null) }
            }
        } catch (e: TimeoutCancellationException) {
            // Boot is slow (cold WebView + VM + attestation round-trips): transient.
            // Retry on the next track instead of latching off for the session.
            println("AndroidPoTokenProvider: engine boot timed out after ${COLD_TIMEOUT_MS}ms; will retry")
            return null
        } ?: run {
            // The factory deterministically found no Android runtime / usable WebView.
            if (engine == null) permanentlyBroken = true
            return null
        }
        if (created.isExpired) {
            // Pathological factory handed us a dead engine; treat as a transient boot failure.
            created.close()
            return null
        }
        engine = created
        return created
    }

    private fun destroyEngine() {
        engine?.let { current ->
            engine = null
            try {
                current.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        /** Conservative token lifetime; the attestation itself allows ~12 h. */
        const val TOKEN_TTL_MS = 6 * 60 * 60 * 1000L
        const val WARM_TIMEOUT_MS = 4_000L

        /** Cold boot: WebView + BotGuard VM + two attestation round-trips. */
        const val COLD_TIMEOUT_MS = 12_000L
    }
}

/**
 * Resolves the host app context (the extension runs inside Echo's process, but
 * the echo.common API exposes no `Context`) and boots the WebView engine. This
 * object deliberately avoids android.* imports in its own signature so the
 * provider class loads cleanly on the JVM; [WebViewMintEngine] is only loaded
 * when an Android context actually exists.
 */
internal object AndroidPoTokenEngineFactory {

    suspend fun create(contextHint: Any?): PoTokenMintEngine? {
        val context = contextHint ?: findAppContext() ?: run {
            println("AndroidPoTokenProvider: no Android application context found - on-device minting unavailable in this process")
            return null
        }
        return runCatching { WebViewMintEngine.create(context) }
            .onFailure {
                println(
                    "AndroidPoTokenProvider: WebView engine unavailable " +
                        "(${it.javaClass.simpleName}: ${it.message})"
                )
            }
            .getOrNull()
    }

    /**
     * Locates the host application context by reflection. Both candidates are
     * standard framework internals present on every Android release; on a plain
     * JVM both lookups fail and `null` is returned.
     */
    private fun findAppContext(): Any? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            atClass.getMethod("currentApplication").invoke(null)
        } catch (_: Throwable) {
            try {
                val agClass = Class.forName("android.app.AppGlobals")
                agClass.getMethod("getInitialApplication").invoke(null)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/** Thrown by a mint engine only when the underlying renderer is unusable for the rest of the session. */
internal class BrokenMintEngineException(message: String) : RuntimeException(message)
