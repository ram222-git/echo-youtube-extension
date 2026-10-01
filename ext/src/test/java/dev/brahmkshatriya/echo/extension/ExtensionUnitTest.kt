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
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

private suspend fun <T : Any> PagedData<T>.loadFirst(): List<T> = loadPage(null).data

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
class ExtensionUnitTest {
    private val extension: ExtensionClient = YoutubeExtension()
    private val searchQuery = "Skrillex"

    private val mainThreadSurrogate = newSingleThreadContext("UI thread")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
        extension.setSettings(MockedSettings())
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
        measureTimeMillis {
            val track = extension.loadTrack(search, false)
            println(track.streamables)
            val streamable = track.streamables.firstOrNull()
                ?: error("Track is not streamable")
            val stream = extension.loadStreamableMedia(streamable, false)
            println(stream)
        }.also { println("time : $it") }
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

        val cookieString = System.getenv("YOUTUBE_COOKIE")
        if (cookieString.isNullOrBlank()) {
            println("Skipping testRestrictedTrackStream: YOUTUBE_COOKIE environment variable not set.")
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

        val testClient = okhttp3.OkHttpClient()
        val httpReqBuilder = okhttp3.Request.Builder()
            .url(source.request.url)
        source.request.headers.forEach { (k, v) ->
            httpReqBuilder.header(k, v)
        }
        val httpResp = testClient.newCall(httpReqBuilder.build()).execute()
        println("Streaming HTTP response code: ${httpResp.code}, contentType: ${httpResp.header("Content-Type")}, length: ${httpResp.body?.contentLength()}")
        assert(httpResp.code in 200..206) { "Expected HTTP 200/206 but got ${httpResp.code}" }
        val bytes = httpResp.body?.byteStream()?.readNBytes(512) ?: ByteArray(0)
        println("Successfully downloaded ${bytes.size} bytes of audio stream header!")
        assert(bytes.isNotEmpty())
    }
}