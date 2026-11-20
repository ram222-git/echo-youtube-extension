package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.utils.WebViewCookieWaiter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewCookieWaiterTest {

    private val noSleep: suspend (Long) -> Unit = {}

    @Test
    fun `returns immediately when the initial snapshot already has a SAPISID`() = runTest {
        var polls = 0
        val result = WebViewCookieWaiter.waitUntilSapisid(
            initialCookie = "SAPISID=abc; SID=xyz",
            source = { polls++; null },
            sleep = noSleep
        )

        assertEquals(0, polls)
        assertTrue(result.hasSapisid)
        assertTrue(result.cookie.contains("SAPISID=abc"))
    }

    @Test
    fun `accepts the secure 3P APISID variant`() = runTest {
        val result = WebViewCookieWaiter.waitUntilSapisid(
            initialCookie = "__Secure-3PAPISID=abc",
            source = { null },
            sleep = noSleep
        )
        assertTrue(result.hasSapisid)
    }

    @Test
    fun `polls until freshly committed cookies carry the SAPISID`() = runTest {
        var attempts = 0
        val result = WebViewCookieWaiter.waitUntilSapisid(
            initialCookie = "SID=xyz",
            timeoutMs = 5_000,
            source = {
                attempts++
                if (attempts >= 3) "SAPISID=late; __Secure-1PSID=token" else "SID=xyz"
            },
            sleep = noSleep
        )

        assertEquals(3, attempts)
        assertTrue(result.hasSapisid)
        assertTrue(result.polls == 3)
        // The early snapshot survives the merge.
        assertTrue(result.cookie.contains("SID=xyz"))
        assertTrue(result.cookie.contains("SAPISID=late"))
    }

    @Test
    fun `gives up after the timeout without fabricating a SAPISID`() = runTest {
        val result = WebViewCookieWaiter.waitUntilSapisid(
            initialCookie = "SID=xyz; HSID=abc",
            timeoutMs = 0, // deadline already passed on the first check
            source = { "nothing=useful" },
            sleep = noSleep
        )

        assertFalse(result.hasSapisid)
        assertTrue(result.cookie.contains("HSID=abc"))
    }

    @Test
    fun `a failing or blank cookie source keeps polling`() = runTest {
        var attempts = 0
        val result = WebViewCookieWaiter.waitUntilSapisid(
            initialCookie = "SID=xyz",
            timeoutMs = 5_000,
            source = {
                attempts++
                if (attempts == 1) null else if (attempts == 2) "" else "SAPISID=fine"
            },
            sleep = noSleep
        )

        assertEquals(3, attempts)
        assertTrue(result.hasSapisid)
    }
}
