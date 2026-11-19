package dev.brahmkshatriya.echo.extension.streaming

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP clients for the streaming layer.
 *
 * Previously every component built its own [OkHttpClient] with default timeouts,
 * which meant a fresh connection pool per call site and 10 s hangs on a single
 * bad edge node. Sharing the pool keeps TLS connections warm between the player
 * request, the HLS playlist fetch and the optional playability probe.
 */
internal object StreamHttpClient {

    val player: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    /**
     * Short budget client used for the single optional playability probe. These are
     * also the hard bound for [YouTubeStreamResolver]'s probe: a blocking
     * `execute()` is not cancellable, so the OkHttp call timeout is what actually
     * limits it, not the coroutine timeout.
     */
    val probe: OkHttpClient by lazy {
        player.newBuilder()
            .connectTimeout(700, TimeUnit.MILLISECONDS)
            .readTimeout(800, TimeUnit.MILLISECONDS)
            .callTimeout(800, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Fetches a small HLS master playlist; same 3 s worst case as before the refactor. */
    val hls: OkHttpClient by lazy {
        player.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Talks to an optional local PO Token provider. Short timeouts: a missing
     * provider must degrade to the token-free clients, never stall playback.
     */
    val token: OkHttpClient by lazy {
        player.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** Used by the signature solver, which downloads base.js once per player version. */
    val solver: OkHttpClient by lazy {
        player.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
