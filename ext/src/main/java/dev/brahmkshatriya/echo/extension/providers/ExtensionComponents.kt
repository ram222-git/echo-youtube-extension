package dev.brahmkshatriya.echo.extension.providers

import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.auth.YouTubeAuthManager
import dev.brahmkshatriya.echo.extension.endpoints.EchoAccountEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoArtistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoArtistMoreEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoEditPlaylistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoLibraryEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoLyricsEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoPlaylistEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSearchEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSearchSuggestionsEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongEndPoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongFeedEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoSongRelatedEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint
import dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
import dev.brahmkshatriya.echo.extension.providers.feeds.LibraryFeedProvider
import dev.brahmkshatriya.echo.extension.providers.feeds.SearchFeedProvider
import dev.brahmkshatriya.echo.extension.providers.playback.PlaybackTracker
import dev.brahmkshatriya.echo.extension.providers.playback.RadioGenerator
import dev.brahmkshatriya.echo.extension.providers.playback.TrackLoader
import dev.brahmkshatriya.echo.extension.providers.playlists.PlaylistManager
import dev.brahmkshatriya.echo.extension.providers.social.FollowManager
import dev.brahmkshatriya.echo.extension.providers.social.LikeManager
import dev.brahmkshatriya.echo.extension.providers.social.ShareManager
import dev.brahmkshatriya.echo.extension.search.YouTubeSearchService
import dev.brahmkshatriya.echo.extension.streaming.PoTokenProvider
import dev.brahmkshatriya.echo.extension.streaming.SettingsPoTokenProvider
import dev.brahmkshatriya.echo.extension.streaming.StreamMediaCache
import dev.brahmkshatriya.echo.extension.streaming.YouTubeStreamResolver
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

//Central hub for creating and managing all extension components.

class ExtensionComponents(
    private val api: YoutubeiApi,
    private val settings: Settings,
    private val json: Json
) {
    //Core Settings
    
    val thumbnailQuality: ThumbnailProvider.Quality
        get() = if (settings.getBoolean("high_quality") == true) {
            ThumbnailProvider.Quality.HIGH
        } else {
            ThumbnailProvider.Quality.LOW
        }

    val enableVideo: Boolean
        get() = settings.getBoolean("enable_video") ?: false

    val sendBackToGoogle: Boolean
        get() = settings.getBoolean("send_back_to_google") ?: true

    val preloadStream: Boolean
        get() = settings.getBoolean("preload_stream") ?: true

    //Endpoints (API Communication)

    val visitorEndpoint = EchoVisitorEndpoint(api)

    /**
     * The video endpoint consults the PO Token provider so web-client **player
     * requests** carry `serviceIntegrityDimensions.poToken` (video-bound) — the
     * restricted-song unlock used by Metrolist/ArchiveTune/LastWave. The lambda
     * resolves lazily, so construction order does not matter.
     */
    val videoEndpoint = EchoVideoEndpoint(api, playerPoToken = { videoId ->
        poTokenProvider.token(videoId)
    })
    val songFeedEndpoint = EchoSongFeedEndpoint(api)
    val artistEndpoint = EchoArtistEndpoint(api)
    val artistMoreEndpoint = EchoArtistMoreEndpoint(api)
    val libraryEndpoint = EchoLibraryEndPoint(api)
    val songEndpoint = EchoSongEndPoint(api)
    val songRelatedEndpoint = EchoSongRelatedEndpoint(api)
    val playlistEndpoint by lazy {
        EchoPlaylistEndpoint(api) { likeManager }
    }
    val lyricsEndpoint = EchoLyricsEndPoint(api)
    val searchSuggestionsEndpoint = EchoSearchSuggestionsEndpoint(api)
    val searchEndpoint = EchoSearchEndpoint(api)
    val editorEndpoint = EchoEditPlaylistEndpoint(api)
    val accountEndpoint = EchoAccountEndpoint(api)
    
    val enhancedSongEndpoint by lazy {
        EchoEnhancedSongEndpoint(api, songEndpoint) { likeManager }
    }

    //Services
    
    val authManager by lazy {
        YouTubeAuthManager(api, visitorEndpoint, accountEndpoint)
    }

    val streamCache = StreamMediaCache()

    /**
     * `off` unless the user configures an external minter. The provider reads the
     * settings on every call, so toggling it does not require new components.
     */
    val poTokenProvider: PoTokenProvider by lazy {
        SettingsPoTokenProvider(settings)
    }

    val streamResolver by lazy {
        YouTubeStreamResolver(
            api = api,
            settings = settings,
            videoEndpoint = videoEndpoint,
            visitorEndpoint = visitorEndpoint,
            cache = streamCache,
            poTokenProvider = poTokenProvider,
            sessionKey = {
                "${api.visitor_id ?: "anon"}|${authManager.getCurrentUserId() ?: "anon"}"
            }
        )
    }

    /** Long-lived scope for speculative work (stream preloading, solver warm-up). */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val searchService by lazy {
        YouTubeSearchService(api)
    }

    //providers

    val libraryFeedProvider by lazy {
        LibraryFeedProvider(api, authManager, libraryEndpoint)
    }

    val searchFeedProvider by lazy {
        SearchFeedProvider(api, searchService, songFeedEndpoint)
    }

    val trackLoader by lazy {
        TrackLoader(authManager, enhancedSongEndpoint, streamResolver, ioScope) { preloadStream }
    }

    val radioGenerator by lazy {
        RadioGenerator(api, json, thumbnailQuality, trackCache)
    }

    val playbackTracker by lazy {
        PlaybackTracker(api, authManager, json)
    }

    val playlistManager by lazy {
        PlaylistManager(authManager, editorEndpoint, thumbnailQuality)
    }

    val likeManager by lazy {
        LikeManager(api, authManager)
    }

    val followManager by lazy {
        FollowManager(authManager)
    }

    val shareManager = ShareManager()
    
    // Track cache for album/playlist tracks
    val trackCache = mutableMapOf<String, PagedData<Track>>()
}
