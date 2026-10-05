package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.SettingsChangeListenerClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

private suspend fun <T : Any> PagedData<T>.loadFirst(): List<T> = loadPage(null).data

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = YoutubeExtension()
    private val searchQuery = "Skrillex"

    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    private val mockedSettings = MockedSettings()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(mockedSettings)
        runBlocking {
            extension.onExtensionSelected()
            (extension as? LoginClient)?.setLoginUser(null)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainThreadSurrogate.close()
    }

    private fun testIn(title: String, block: suspend CoroutineScope.() -> Unit) = runBlocking {
        println("\n-- $title --")
        block.invoke(this)
        println("\n")
    }

    @Test
    fun testHomeFeed() = testIn("Testing Home Feed") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val feed = extension.loadHomeFeed().pagedDataOfFirst().loadFirst()
        feed.forEach {
            println(it)
        }
    }

    @Test
    fun testHomeFeedWithTab() = testIn("Testing Home Feed with Tab") {
        if (extension !is HomeFeedClient) error("HomeFeedClient is not implemented")
        val homeFeed = extension.loadHomeFeed()
        val tab = homeFeed.tabs.firstOrNull()
        val feed = homeFeed.getPagedData(tab).pagedData.loadFirst()
        feed.forEach {
            println(it)
        }
    }

    @Test
    fun testNullQuickSearch() = testIn("Testing Null Quick Search") {
        if (extension !is QuickSearchClient) error("QuickSearchClient is not implemented")
        val search = extension.quickSearch("")
        search.forEach {
            println(it)
        }
    }

    @Test
    fun testQuickSearch() = testIn("Testing Quick Search") {
        if (extension !is QuickSearchClient) error("QuickSearchClient is not implemented")
        val search = extension.quickSearch(searchQuery)
        search.forEach {
            println(it)
        }
    }

    @Test
    fun testNullSearch() = testIn("Testing Null Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        val search = extension.loadSearchFeed("").pagedDataOfFirst().loadFirst()
        search.forEach {
            println(it)
        }
    }

    @Test
    fun testSearch() = testIn("Testing Search") {
        if (extension !is SearchFeedClient) error("SearchFeedClient is not implemented")
        println("Search Results")
        val search = extension.loadSearchFeed(searchQuery).pagedDataOfFirst().loadFirst()
        search.forEach {
            println(it)
        }
    }

    private suspend fun searchTrack(q: String? = null): Track {
        if (extension !is SearchFeedClient) error("SearchClient is not implemented")
        val query = q ?: searchQuery
        println("Searching  : $query")
        val items = extension.loadSearchFeed(query).pagedDataOfFirst().loadFirst()
        val track = items.firstNotNullOfOrNull { shelf ->
            when (shelf) {
                is Shelf.Lists.Tracks -> shelf.list.firstOrNull()
                is Shelf.Lists.Items -> shelf.list.firstNotNullOfOrNull { it as? Track }
                is Shelf.Item -> shelf.media as? Track
                else -> null
            }
        }
        return track ?: error("Track not found, try a different search query")
    }

    @Test
    fun testTrackGet() = testIn("Testing Track Get") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = Track("5XR7naZ_zZA", "")
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            println(track.extras["isLiked"])
        }.also { println("time : $it") }
    }

    @Test
    fun testTrackStream() = testIn("Testing Track Stream") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val search = Track("qeFt3fdsydA", "")
        
        // 1. Test YouTube Audio Server
        mockedSettings.putBoolean("enable_video", false)
        val track = extension.loadTrack(search, false)
        println("Normal track loaded: ${track.id}, streamables: ${track.streamables.map { "${it.title} (type=${it.extras["type"]})" }}")
        val audioStreamable = track.streamables.firstOrNull { it.extras["type"] == "audio" }
            ?: track.streamables.firstOrNull() ?: error("Track is not streamable")
        val audioMedia = extension.loadStreamableMedia(audioStreamable, false)
        assert(audioMedia is Streamable.Media.Server)
        val audioServer = audioMedia as Streamable.Media.Server
        println("Normal Song YouTube Server (Audio): ${audioServer.sources.size} qualities, merged=${audioServer.merged}")
        val audioQualities = audioServer.sources.map { "Quality ${it.quality} (isVideo=${it.isVideo})" }
        println("Normal Song Audio Qualities: $audioQualities")
        assert(audioServer.sources.isNotEmpty())
        assert(!audioServer.merged) { "Audio server must have merged = false for Echo quality selection" }

        // 2. Test YouTube Video Server
        mockedSettings.putBoolean("enable_video", true)
        val videoTrack = extension.loadTrack(search, false)
        val videoStreamable = videoTrack.streamables.firstOrNull { it.extras["type"] == "video" }
            ?: error("Video streamable not found for normal track")
        val videoMedia = extension.loadStreamableMedia(videoStreamable, false)
        assert(videoMedia is Streamable.Media.Server)
        val videoServer = videoMedia as Streamable.Media.Server
        println("Normal Song YouTube Video Server: ${videoServer.sources.size} source(s), merged=${videoServer.merged}")
        val videoTypes = videoServer.sources.map { "type=${it::class.simpleName}, quality=${it.quality}, isVideo=${it.isVideo}" }
        println("Normal Song Video Sources: $videoTypes")
        assert(videoServer.sources.isNotEmpty())
    }

    @Test
    fun testTrackRadio() = testIn("Testing Track Radio") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        if (extension !is RadioClient) error("RadioClient is not implemented")
        val track = extension.loadTrack(searchTrack(), false)
        val radio = extension.radio(track, null)
        val radioTracks = extension.loadTracks(radio).pagedDataOfFirst().loadFirst()
        radioTracks.forEach {
            println(it)
        }
    }

    @Test
    fun testTrackMediaItems() = testIn("Testing Track Media Items") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val track = extension.loadTrack(Track("iDkSRTBDxJY", ""), false)
        val mediaItems = extension.loadFeed(track)?.pagedDataOfFirst()?.loadFirst() ?: emptyList()
        mediaItems.forEach {
            println(it)
        }
    }

    @Test
    fun testAlbumGet() = testIn("Testing Album Get") {
        val small = Album("OLAK5uy_mxh0nd0ZvSxiDVWflNGUSWxGz0ju1OA-E", "")
        if (extension !is AlbumClient) error("AlbumClient is not implemented")
        val album = extension.loadAlbum(small)
        println(album)
    }

    @Test
    fun testPlaylistMediaItems() = testIn("Testing Playlist Media Items") {
        if (extension !is PlaylistClient) error("PlaylistClient is not implemented")
        val playlist = extension.loadPlaylist(
            Playlist("RDCLAK5uy_kPazyVLjdDuNJ_OxrjeXIyMYeKsz7vVAU", "", false)
        )
        println(playlist)
        val tracks = extension.loadTracks(playlist).loadAll()
        println(tracks.size)
    }

    @Test
    fun testArtistMediaItems() = testIn("Testing Artist Media Items") {
        val small = Artist("UCySqAU8DY0BnB2j5uYdCbLA", "")
        if (extension !is ArtistClient) error("ArtistClient is not implemented")
        val artist = extension.loadArtist(small)
        println(artist)
        val mediaItems = extension.loadFeed(artist)?.pagedDataOfFirst()?.loadFirst() ?: emptyList()
        mediaItems.forEach { shelf ->
            println(shelf.title)
        }
    }

    @Test
    fun testLyrics() = testIn("Lyrics") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        val small = Track("iDkSRTBDxJY", "")
        val track = extension.loadTrack(small, false)
        println(track)
        if (extension !is LyricsClient) error("LyricsClient is not implemented")
        val lyricsItems = extension.searchTrackLyrics("", track).pagedDataOfFirst().loadFirst()
        val lyricsItem = lyricsItems.firstOrNull()
        if (lyricsItem == null) {
            println("Lyrics not found")
            return@testIn
        }
        val loaded = extension.loadLyrics(lyricsItem)
        (loaded.lyrics as? Lyrics.Timed)?.list?.forEach {
            println(it)
        }
    }

    @Test
    fun testRestrictedTrackStream() = testIn("Testing Restricted Track Stream with Cookies") {
        if (extension !is TrackClient) error("TrackClient is not implemented")
        if (extension !is LoginClient) error("LoginClient is not implemented")

        val cookieFile = File("../cookise.txt").takeIf { it.exists() }
            ?: File("cookise.txt").takeIf { it.exists() }
            ?: File("../cookies.txt").takeIf { it.exists() }
            ?: File("cookies.txt").takeIf { it.exists() }
        val cookieString = System.getenv("YOUTUBE_COOKIE")
            ?: cookieFile?.readText()?.trim()
        if (cookieString.isNullOrBlank()) {
            println("Skipping testRestrictedTrackStream: No cookie found in env or cookise.txt.")
            return@testIn
        }
        val fakeUser = User(
            id = "test_user",
            name = "Test User",
            cover = null,
            subtitle = "test@example.com",
            extras = mapOf("cookie" to cookieString)
        )
        (extension as LoginClient).setLoginUser(fakeUser)

        val targetTrack = Track("hc0ZDaAZQT0", "")
        val track = extension.loadTrack(targetTrack, false)
        println("Track loaded: ${track.id}, streamables: ${track.streamables.size}")
        val streamable = track.streamables.firstOrNull() ?: error("Track has no streamable")
        val streamMedia = extension.loadStreamableMedia(streamable, false)
        assert(streamMedia is Streamable.Media.Server)
        val serverMedia = streamMedia as Streamable.Media.Server
        val source = serverMedia.sources.first() as Streamable.Source.Http
        println("Testing HTTP connection to stream URL: ${source.request.url.take(100)}...")

        val testClient = OkHttpClient()
        val httpReqBuilder = Request.Builder()
            .url(source.request.url)
            .header("Range", "bytes=0-512")
        source.request.headers.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true)) {
                httpReqBuilder.header(k, v)
            }
        }
        val httpResp = testClient.newCall(httpReqBuilder.build()).execute()
        println("Streaming HTTP response code: ${httpResp.code}, contentType: ${httpResp.header("Content-Type")}, length: ${httpResp.body?.contentLength()}")
        assert(httpResp.code in 200..206) { "Expected HTTP 200/206 but got ${httpResp.code}" }
        val bytes = httpResp.body?.byteStream()?.readNBytes(512) ?: ByteArray(0)
        println("Successfully downloaded ${bytes.size} bytes of audio stream header!")
        assert(bytes.isNotEmpty())
        val audioQualities = serverMedia.sources.map { "Quality ${it.quality} (isVideo=${it.isVideo})" }
        println("Restricted Song YouTube Server (Audio): ${serverMedia.sources.size} qualities, merged=${serverMedia.merged}")
        assert(serverMedia.sources.isNotEmpty()) { "Expected audio sources under YouTube server" }
        assert(!serverMedia.merged) { "Audio server must have merged = false for Echo quality selection" }

        println("\n--- Testing Restricted Track Video with All Qualities ---")
        mockedSettings.putBoolean("enable_video", true)
        val videoTrack = extension.loadTrack(targetTrack, false)
        val videoStreamable = videoTrack.streamables.firstOrNull { it.extras["type"] == "video" }
            ?: error("Video streamable not found in streamables: ${videoTrack.streamables.map { it.id to it.extras }}")
        val videoMedia = extension.loadStreamableMedia(videoStreamable, false)
        assert(videoMedia is Streamable.Media.Server)
        val serverVideo = videoMedia as Streamable.Media.Server
        val videoSources = serverVideo.sources.filter { it.isVideo }
        println("Restricted Song YouTube Video Server: ${videoSources.size} video sources, merged=${serverVideo.merged}")
        assert(videoSources.isNotEmpty()) { "Expected a video source in video server" }

        val vHttp = videoSources.first() as Streamable.Source.Http
        println("  Playing video: ${vHttp.title} (quality=${vHttp.quality})")
        val reqBuilder = Request.Builder()
            .url(vHttp.request.url)
            .header("Range", "bytes=0-512")
        vHttp.request.headers.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true)) reqBuilder.header(k, v)
        }
        val resp = testClient.newCall(reqBuilder.build()).execute()
        val readBytes = resp.body?.byteStream()?.readNBytes(512) ?: ByteArray(0)
        println("  Resolution ${vHttp.title}: HTTP ${resp.code}, read=${readBytes.size} bytes")
        assert(resp.code in 200..206) { "Expected HTTP 200/206 for ${vHttp.title} but got ${resp.code}" }
        assert(readBytes.isNotEmpty()) { "Expected non-empty payload for ${vHttp.title}" }

        // Test with custom height requests (e.g. 1080p, 480p, 360p)
        listOf(1080, 480, 360).forEach { testHeight ->
            val customStreamable = videoStreamable.copy(
                extras = videoStreamable.extras + ("height" to testHeight.toString())
            )
            val customMedia = extension.loadStreamableMedia(customStreamable, false) as Streamable.Media.Server
            val customVideo = customMedia.sources.first { it.isVideo } as Streamable.Source.Http
            println("  Requested ${testHeight}p -> Received ${customVideo.title} (quality=${customVideo.quality}, merged=${customMedia.merged})")
            assert(customVideo.quality == testHeight || customVideo.quality > 0)
        }
    }
}