package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.extension.streaming.AndroidPoTokenProvider
import dev.brahmkshatriya.echo.extension.streaming.PoTokenMintEngine
import dev.brahmkshatriya.echo.extension.streaming.PoTokenProvider
import dev.brahmkshatriya.echo.extension.streaming.SettingsPoTokenProvider
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * JVM tests for the on-device provider and the settings chain. The WebView
 * engine is faked: the real one ([WebViewMintEngine]) only loads on Android.
 *
 * Tokens in these tests are synthetic ("token-for-..."); no real minting and no
 * credential material is involved.
 */
class AndroidPoTokenProviderTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeEngine(
        var throwOnMint: Boolean = false,
        var mintResult: ((String) -> String)? = null
    ) : PoTokenMintEngine {
        val mintCalls = mutableListOf<String>()
        var closed = false
        override var isExpired: Boolean = false

        override suspend fun mint(contentBinding: String): String {
            mintCalls.add(contentBinding)
            if (throwOnMint) throw RuntimeException("mint boom")
            return mintResult?.invoke(contentBinding) ?: "token-for-$contentBinding"
        }

        override fun close() {
            closed = true
        }
    }

    /** Factory returning a queued engine per creation; `null` entries simulate boot failure. */
    private class FakeFactory(vararg engines: FakeEngine?) {
        private val queue = mutableListOf<FakeEngine?>(*engines)
        val created = mutableListOf<PoTokenMintEngine?>()
        var creations = 0
        var lastContextHint: Any? = "unset"

        suspend fun create(contextHint: Any?): PoTokenMintEngine? {
            creations++
            lastContextHint = contextHint
            val next = if (queue.isEmpty()) null else queue.removeAt(0)
            created.add(next)
            return next
        }
    }

    private class FakeProvider(
        private val result: String? = null
    ) : PoTokenProvider {
        override val kind = "android-fake"
        var invalidated = 0
        val requestedBindings = mutableListOf<String?>()
        override suspend fun token(contentBinding: String?): String? {
            requestedBindings.add(contentBinding)
            return result
        }
        override fun invalidate() {
            invalidated++
        }
    }

    // ------------------------------------------------------------------
    // AndroidPoTokenProvider lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `mints once per binding and caches`() = runTest {
        val factory = FakeFactory(FakeEngine())
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        val first = provider.token("abc123")
        val second = provider.token("abc123")

        assertEquals("token-for-abc123", first)
        assertEquals(first, second)
        assertEquals(1, factory.creations)
        assertEquals(listOf("abc123"), (factory.created[0] as FakeEngine).mintCalls)
    }

    @Test
    fun `never reuses a token across bindings`() = runTest {
        val factory = FakeFactory(FakeEngine())
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        val a = provider.token("videoA")
        val b = provider.token("videoB")

        assertEquals("token-for-videoA", a)
        assertEquals("token-for-videoB", b)
        assertNotEquals(a, b)
        assertEquals(listOf("videoA", "videoB"), (factory.created.last() as FakeEngine).mintCalls)
    }

    @Test
    fun `expired token is reminted`() = runTest {
        val factory = FakeFactory(FakeEngine())
        var clock = 0L
        val provider = AndroidPoTokenProvider(
            engineFactory = factory::create,
            now = { clock }
        )

        assertEquals("token-for-v", provider.token("v"))
        clock += AndroidPoTokenProvider.TOKEN_TTL_MS + 1
        assertEquals("token-for-v", provider.token("v"))

        assertEquals(2, (factory.created.last() as FakeEngine).mintCalls.size)
    }

    @Test
    fun `warm mint failure rebuilds the engine once`() = runTest {
        val broken = FakeEngine(throwOnMint = true)
        val healthy = FakeEngine()
        val factory = FakeFactory(broken, healthy)
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        assertEquals("token-for-v", provider.token("v"))
        assertEquals(2, factory.creations)
        assertTrue(broken.closed)
        assertFalse(healthy.closed)
    }

    @Test
    fun `expired engine is rebuilt`() = runTest {
        val old = FakeEngine()
        val fresh = FakeEngine()
        val factory = FakeFactory(old, fresh)
        var clock = 0L
        val provider = AndroidPoTokenProvider(
            engineFactory = factory::create,
            now = { clock }
        )

        assertEquals("token-for-v", provider.token("v"))
        assertEquals(1, factory.creations)

        // Force a cache miss (token TTL passed) while the engine went stale too.
        clock += AndroidPoTokenProvider.TOKEN_TTL_MS + 1
        old.isExpired = true
        assertEquals("token-for-v", provider.token("v"))

        assertEquals(2, factory.creations)
        assertTrue(old.closed)
        assertFalse((factory.created.last() as FakeEngine).closed)
    }

    @Test
    fun `unavailable engine is permanent and stops retrying`() = runTest {
        val factory = FakeFactory(null as FakeEngine?)
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        assertNull(provider.token("v"))
        assertNull(provider.token("v"))
        assertTrue(provider.permanentlyBroken)
        assertEquals(1, factory.creations)
    }

    @Test
    fun `second consecutive boot failure marks provider broken`() = runTest {
        // Engine boots but minting keeps failing: rebuild happens once per token()
        // call, and the failure is contained in a null token.
        val factory = FakeFactory(FakeEngine(throwOnMint = true), FakeEngine(throwOnMint = true))
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        assertNull(provider.token("v"))
        assertEquals(2, factory.creations)
        assertFalse(provider.permanentlyBroken) // recoverable on the next track
    }

    @Test
    fun `concurrent requests coalesce into one mint`() = runTest {
        val factory = FakeFactory(FakeEngine())
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        val a = async { provider.token("same") }
        val b = async { provider.token("same") }

        assertEquals(a.await(), b.await())
        assertEquals(1, (factory.created.last() as FakeEngine).mintCalls.size)
    }

    @Test
    fun `invalidate drops tokens but keeps the engine`() = runTest {
        val engine = FakeEngine()
        val factory = FakeFactory(engine)
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        provider.token("v")
        provider.invalidate()
        provider.token("v")

        assertEquals(2, engine.mintCalls.size)
        assertEquals(1, factory.creations)
        assertFalse(engine.closed)
    }

    @Test
    fun `invalidateAll also destroys the engine`() = runTest {
        val engine = FakeEngine()
        val factory = FakeFactory(engine)
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        provider.token("v")
        provider.invalidateAll()

        assertTrue(engine.closed)
    }

    @Test
    fun `blank binding never reaches the engine`() = runTest {
        val factory = FakeFactory(FakeEngine())
        val provider = AndroidPoTokenProvider(engineFactory = factory::create)

        assertNull(provider.token(null))
        assertNull(provider.token("  "))
        assertEquals(0, factory.creations)
    }

    // ------------------------------------------------------------------
    // Settings chain
    // ------------------------------------------------------------------

    private var httpServer: HttpServer? = null

    @After
    fun tearDown() {
        httpServer?.stop(0)
        httpServer = null
    }

    /** Minimal bgutil-compatible `/get_pot` stub; returns a synthetic token. */
    private fun startFakeExternalServer(): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/get_pot") { exchange ->
            val body = """{"poToken":"ext-token","contentBinding":"v"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        httpServer = server
        return "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun `off mode with no url stays off`() = runTest {
        val settings = MockedSettings()
        val chain = SettingsPoTokenProvider(settings) { FakeProvider("atok") }

        assertEquals("off", chain.kind)
        assertNull(chain.token("v"))
    }

    @Test
    fun `url without mode enables external provider`() = runTest {
        val url = startFakeExternalServer()
        val settings = MockedSettings().apply { putString("potoken_url", url) }
        val chain = SettingsPoTokenProvider(settings) { FakeProvider("atok") }

        assertEquals("external", chain.kind)
        assertEquals("ext-token", chain.token("v"))
    }

    @Test
    fun `external mode without url is off`() = runTest {
        val settings = MockedSettings().apply { putString("potoken_mode", "external") }
        val chain = SettingsPoTokenProvider(settings) { FakeProvider("atok") }

        assertEquals("off", chain.kind)
        assertNull(chain.token("v"))
    }

    @Test
    fun `android mode prefers the on-device provider`() = runTest {
        val settings = MockedSettings().apply { putString("potoken_mode", "android") }
        val fake = FakeProvider("atok")
        val chain = SettingsPoTokenProvider(settings) { fake }

        assertEquals("android", chain.kind)
        assertEquals("atok", chain.token("v"))
        assertEquals(listOf<String?>("v"), fake.requestedBindings)
    }

    @Test
    fun `android mode falls back to external when on-device returns null`() = runTest {
        val url = startFakeExternalServer()
        val settings = MockedSettings().apply {
            putString("potoken_mode", "android")
            putString("potoken_url", url)
        }
        val chain = SettingsPoTokenProvider(settings) { FakeProvider(result = null) }

        assertEquals("android", chain.kind)
        assertEquals("ext-token", chain.token("v"))
    }

    @Test
    fun `android mode degrades to off when on-device is permanently broken`() = runTest {
        val settings = MockedSettings().apply { putString("potoken_mode", "android") }
        val factory = FakeFactory(null as FakeEngine?)
        val chain = SettingsPoTokenProvider(settings) {
            AndroidPoTokenProvider(engineFactory = factory::create)
        }

        assertEquals("android", chain.kind)
        assertNull(chain.token("v")) // marks the provider permanently broken
        assertEquals("off", chain.kind)
        assertNull(chain.token("v"))
    }

    @Test
    fun `invalidate cascades to both providers`() = runTest {
        val settings = MockedSettings().apply { putString("potoken_mode", "android") }
        val fake = FakeProvider("atok")
        val chain = SettingsPoTokenProvider(settings) { fake }

        chain.invalidate()
        assertEquals(1, fake.invalidated)
    }

    @Test
    fun `android mode without external url returns null when minting fails`() = runTest {
        val settings = MockedSettings().apply { putString("potoken_mode", "android") }
        val chain = SettingsPoTokenProvider(settings) { FakeProvider(result = null) }

        assertNull(chain.token("v"))
        // Kind stays usable (transient failure): the resolver may retry the lane once.
        assertEquals("android", chain.kind)
    }
}
