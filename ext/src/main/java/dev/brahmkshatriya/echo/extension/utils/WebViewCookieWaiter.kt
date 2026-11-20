package dev.brahmkshatriya.echo.extension.utils

import android.webkit.CookieManager

/**
 * The login WebView fires its stop hook on the first request that lands on
 * music.youtube.com — which can be a redirect or a sub-resource that arrives
 * *before* Google has committed the session cookies. Failing immediately on
 * that snapshot turns a successful sign-in into
 * "Login Failed SAPISID cookie not found".
 *
 * [waitUntilSapisid] re-reads the WebView's global cookie store for a grace
 * period, merging every observed cookie string, until a SAPISID-capable session
 * (SAPISID / __Secure-3PAPISID / __Secure-1PAPISID) shows up or the budget is
 * spent. Only cookie *names* are ever logged by callers.
 */
object WebViewCookieWaiter {

    fun interface CookieSource {
        fun cookieFor(url: String): String?
    }

    private val androidSource = CookieSource { url ->
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
    }

    data class Result(
        /** Every cookie observed, merged; last writer wins. */
        val cookie: String,
        val hasSapisid: Boolean,
        /** How many extra polls were needed (0 = the first snapshot already had it). */
        val polls: Int
    )

    suspend fun waitUntilSapisid(
        initialCookie: String,
        url: String = DEFAULT_LOGIN_URL,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        pollMs: Long = DEFAULT_POLL_MS,
        source: CookieSource = androidSource,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
    ): Result {
        var merged = initialCookie
        var map = CookieParser.parse(merged)
        var polls = 0
        val deadline = System.currentTimeMillis() + timeoutMs

        while (CookieParser.getSapisid(map) == null && System.currentTimeMillis() < deadline) {
            sleep(pollMs)
            polls++
            val fresh = runCatching { source.cookieFor(url) }.getOrNull()
            if (!fresh.isNullOrBlank()) {
                // Merge, so cookies the stop snapshot saw are never lost to a
                // poll that returned only a subset (or vice versa).
                val combined = CookieParser.parse(merged) + CookieParser.parse(fresh)
                merged = CookieParser.serialize(combined)
                map = combined
            }
        }

        return Result(merged, CookieParser.getSapisid(map) != null, polls)
    }

    const val DEFAULT_LOGIN_URL = "https://music.youtube.com/"
    const val DEFAULT_TIMEOUT_MS = 8_000L
    const val DEFAULT_POLL_MS = 250L
}
