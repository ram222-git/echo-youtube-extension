package dev.brahmkshatriya.echo.extension.providers.playback

import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.extension.ModelTypeHelper
import dev.brahmkshatriya.echo.extension.endpoints.EchoEnhancedSongEndpoint
import dev.brahmkshatriya.echo.extension.toTrack
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.uistrings.parseYoutubeDurationString
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RadioGenerator(
    override val api: YoutubeiApi,
    private val json: Json,
    private val thumbnailQuality: ThumbnailProvider.Quality,
    private val trackCache: MutableMap<String, PagedData<Track>>
) : ApiEndpoint() {
    private val radioFeedCache = mutableMapOf<String, PagedData<Track>>()

    suspend fun generateRadio(item: EchoMediaItem, context: EchoMediaItem? = null): Radio {
        return try {
            when (item) {
                is Track -> generateFromTrack(item, context)
                is Album -> generateFromAlbum(item)
                is Artist -> generateFromArtist(item)
                is Playlist -> generateFromPlaylist(item)
                else -> Radio(id = "radio_${item.id}", title = "Radio")
            }
        } catch (e: Exception) {
            println("generateRadio fallback error: ${e.message}")
            val fallbackTrack = item as? Track
            if (fallbackTrack != null) {
                val radioId = "radio_${fallbackTrack.id}"
                val paged = PagedData.Single { listOf(fallbackTrack) }
                radioFeedCache[radioId] = paged
                Radio(
                    id = radioId,
                    title = "${fallbackTrack.title} Radio",
                    cover = fallbackTrack.cover,
                    extras = mapOf("videoId" to fallbackTrack.id)
                )
            } else {
                Radio(id = "radio_${item.id}", title = "Radio")
            }
        }
    }

    private suspend fun generateFromTrack(track: Track, context: EchoMediaItem?): Radio {
        val radioId = "radio_${track.id}"
        val initialCont = context?.extras?.get("cont")

        val pagedData = PagedData.Continuous<Track> { token ->
            val cont = token ?: initialCont
            fetchRadioPage(track.id, cont)
        }

        radioFeedCache[radioId] = pagedData

        return Radio(
            id = radioId,
            title = "${track.title} Radio",
            cover = track.cover,
            extras = mapOf("videoId" to track.id)
        )
    }

    suspend fun getInitialRadioTracks(videoId: String): List<Track> {
        return runCatching {
            fetchRadioPage(videoId, null).data
        }.getOrNull().orEmpty()
    }

    private suspend fun fetchRadioPage(videoId: String, token: String?): Page<Track> {
        // 1. Try safe JSON parser on YouTube "next" endpoint first
        val customResult = fetchRadioTracksSafely(videoId, token)
        if (customResult != null && customResult.first.isNotEmpty()) {
            return Page(customResult.first, customResult.second)
        }

        // 2. Fallback to ytmkt built-in
        val ytmResult = runCatching {
            api.SongRadio.getSongRadio(videoId, token).getOrThrow()
        }.getOrNull()

        if (ytmResult != null && ytmResult.items.isNotEmpty()) {
            val tracks = ytmResult.items.map { 
                val tr = it.toTrack(thumbnailQuality)
                if (tr.streamables.isEmpty()) {
                    tr.copy(streamables = EchoEnhancedSongEndpoint.createDefaultStreamables(it.id))
                } else tr
            }
            return Page(tracks, ytmResult.continuation)
        }

        return Page(emptyList(), null)
    }

    private suspend fun fetchRadioTracksSafely(videoId: String, token: String?): Pair<List<Track>, String?>? {
        return try {
            val response: HttpResponse = api.client.request {
                endpointPath("next")
                addApiHeadersWithAuthenticated()
                postWithBody {
                    put("enablePersistentPlaylistPanel", true)
                    put("isAudioOnly", true)
                    if (token != null) {
                        put("continuation", token)
                    } else {
                        put("videoId", videoId)
                        put("playlistId", "RDAMVM$videoId")
                    }
                }
            }

            val responseText = response.bodyAsText()
            val root = json.parseToJsonElement(responseText).jsonObject

            val playlistPanelRenderer = if (token != null) {
                val contContents = root["continuationContents"]?.jsonObject
                contContents?.get("playlistPanelContinuation")?.jsonObject
                    ?: contContents?.get("sectionListContinuation")?.jsonObject
            } else {
                val contents = root["contents"]?.jsonObject
                val tabs = contents?.get("singleColumnMusicWatchNextResultsRenderer")
                    ?.jsonObject?.get("tabbedRenderer")
                    ?.jsonObject?.get("watchNextTabbedResultsRenderer")
                    ?.jsonObject?.get("tabs")?.jsonArray

                val musicQueueRenderer = tabs?.firstNotNullOfOrNull { tab ->
                    tab.jsonObject["tabRenderer"]
                        ?.jsonObject?.get("content")
                        ?.jsonObject?.get("musicQueueRenderer")
                        ?.jsonObject
                }
                musicQueueRenderer?.get("content")?.jsonObject?.get("playlistPanelRenderer")?.jsonObject
            }

            val contentsArray = playlistPanelRenderer?.get("contents")?.jsonArray ?: return null

            val parsedTracks = contentsArray.mapNotNull { item ->
                parsePlaylistPanelVideo(item.jsonObject)
            }

            if (parsedTracks.isEmpty()) return null

            val nextCont = playlistPanelRenderer["continuations"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.let { cont ->
                    cont["nextRadioContinuationData"]?.jsonObject?.get("continuation")?.jsonPrimitive?.contentOrNull
                        ?: cont["nextContinuationData"]?.jsonObject?.get("continuation")?.jsonPrimitive?.contentOrNull
                }

            parsedTracks to nextCont
        } catch (e: Exception) {
            println("fetchRadioTracksSafely failed: ${e.message}")
            null
        }
    }

    private fun parsePlaylistPanelVideo(item: JsonObject): Track? {
        val renderer = item["playlistPanelVideoRenderer"]?.jsonObject
            ?: item["playlistPanelVideoWrapperRenderer"]?.jsonObject?.get("primaryRenderer")?.jsonObject?.get("playlistPanelVideoRenderer")?.jsonObject
            ?: return null

        val videoId = renderer["videoId"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = renderer["title"]?.jsonObject?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: return null

        val longRuns = renderer["longBylineText"]?.jsonObject?.get("runs")?.jsonArray
        val shortRuns = renderer["shortBylineText"]?.jsonObject?.get("runs")?.jsonArray

        fun isAlbumOrPlaylist(pageType: String?, browseId: String?): Boolean {
            return pageType?.contains("ALBUM", ignoreCase = true) == true ||
                    pageType?.contains("PLAYLIST", ignoreCase = true) == true ||
                    browseId?.startsWith("MPREb_") == true ||
                    browseId?.startsWith("OLAK5uy_") == true ||
                    browseId?.startsWith("VL") == true ||
                    browseId?.startsWith("PL") == true
        }

        fun isArtistType(pageType: String?, browseId: String?): Boolean {
            return pageType?.contains("ARTIST", ignoreCase = true) == true ||
                    pageType?.contains("USER", ignoreCase = true) == true ||
                    pageType?.contains("CHANNEL", ignoreCase = true) == true ||
                    browseId?.startsWith("UC") == true ||
                    browseId?.startsWith("FE") == true
        }

        var artists = shortRuns?.mapNotNull { runElement ->
            val runObj = runElement.jsonObject
            val text = runObj["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            if (text.isEmpty() || text == "•" || text == "·" || text == "," || text == "&" || text.matches(Regex("""[•·,&/\s]+"""))) return@mapNotNull null
            if (text.endsWith("views", ignoreCase = true) || text.endsWith("likes", ignoreCase = true)) return@mapNotNull null
            val browseEndpoint = runObj["navigationEndpoint"]?.jsonObject?.get("browseEndpoint")?.jsonObject
            val browseId = browseEndpoint?.get("browseId")?.jsonPrimitive?.contentOrNull
            val pageType = browseEndpoint?.get("browseEndpointContextSupportedConfigs")?.jsonObject
                ?.get("browseEndpointContextMusicConfig")?.jsonObject
                ?.get("pageType")?.jsonPrimitive?.contentOrNull

            if (isAlbumOrPlaylist(pageType, browseId)) return@mapNotNull null

            val resolvedBrowseId = browseId ?: longRuns?.firstNotNullOfOrNull { longEl ->
                val longObj = longEl.jsonObject
                if (longObj["text"]?.jsonPrimitive?.contentOrNull?.trim() == text) {
                    val longEp = longObj["navigationEndpoint"]?.jsonObject?.get("browseEndpoint")?.jsonObject
                    val bId = longEp?.get("browseId")?.jsonPrimitive?.contentOrNull
                    val pType = longEp?.get("browseEndpointContextSupportedConfigs")?.jsonObject
                        ?.get("browseEndpointContextMusicConfig")?.jsonObject
                        ?.get("pageType")?.jsonPrimitive?.contentOrNull
                    if (isArtistType(pType, bId)) bId else null
                } else null
            } ?: ""
            Artist(id = resolvedBrowseId, name = text)
        }?.filter { it.name.isNotBlank() && it.name != "Unknown" }.orEmpty()

        if (artists.isEmpty()) {
            artists = longRuns?.mapNotNull { runElement ->
                val runObj = runElement.jsonObject
                val text = runObj["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (text.isEmpty() || text == "•" || text == "·" || text.matches(Regex("""[•·\s]+"""))) return@mapNotNull null
                if (text.matches(Regex("""\d{4}""")) || text.matches(Regex("""\d{1,2}:\d{2}"""))) return@mapNotNull null
                if (text.endsWith("views", ignoreCase = true) || text.endsWith("likes", ignoreCase = true) || text.endsWith("plays", ignoreCase = true)) return@mapNotNull null
                val browseEndpoint = runObj["navigationEndpoint"]?.jsonObject?.get("browseEndpoint")?.jsonObject
                val browseId = browseEndpoint?.get("browseId")?.jsonPrimitive?.contentOrNull
                val pageType = browseEndpoint?.get("browseEndpointContextSupportedConfigs")?.jsonObject
                    ?.get("browseEndpointContextMusicConfig")?.jsonObject
                    ?.get("pageType")?.jsonPrimitive?.contentOrNull

                if (isAlbumOrPlaylist(pageType, browseId)) return@mapNotNull null
                if (text.equals(title, ignoreCase = true) && browseId?.startsWith("UC") != true) return@mapNotNull null

                if (isArtistType(pageType, browseId)) {
                    Artist(id = browseId ?: "", name = text)
                } else null
            }?.filter { it.name.isNotBlank() && it.name != "Unknown" }.orEmpty()
        }

        // Sanitize artists
        artists = artists.filter {
            it.name.isNotBlank() &&
            it.name != "Unknown" &&
            it.name != "•" &&
            !it.id.startsWith("MPREb_") &&
            !it.id.startsWith("OLAK5uy_") &&
            !it.id.startsWith("VL") &&
            !it.id.startsWith("PL")
        }.let { list ->
            if (list.size > 1) {
                list.filterNot { it.name.equals(title, ignoreCase = true) && !it.id.startsWith("UC") }
            } else list
        }.distinctBy { it.id.ifEmpty { it.name } }

        val thumbnails = renderer["thumbnail"]?.jsonObject
            ?.get("thumbnails")?.jsonArray

        val coverUrl = thumbnails?.lastOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull

        val lengthText = renderer["lengthText"]?.jsonObject
            ?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.contentOrNull
        val durationMs = lengthText?.let { parseYoutubeDurationString(it, api.data_language) }

        return Track(
            id = videoId,
            title = title,
            artists = artists.ifEmpty { listOf(Artist(id = "", name = "Unknown")) },
            cover = coverUrl?.toImageHolder(crop = true),
            duration = durationMs,
            streamables = EchoEnhancedSongEndpoint.createDefaultStreamables(videoId),
            extras = mapOf("videoId" to videoId)
        )
    }

    private suspend fun generateFromAlbum(album: Album): Radio {
        val track = trackCache[album.id]?.toFeed()?.loadAll()?.firstOrNull()
            ?: (trackCache[album.id.removePrefix("MPREb_")] ?: trackCache["VL${album.id}"])?.toFeed()?.loadAll()?.firstOrNull()
            ?: throw Exception("No tracks found")
        return generateFromTrack(track, null)
    }

    private suspend fun generateFromArtist(artist: Artist): Radio {
        val radioId = "radio_${artist.id}"
        val pagedData = PagedData.Continuous<Track> { token ->
            val result = api.ArtistRadio.getArtistRadio(artist.id, token).getOrThrow()
            val tracks = result.items.map { song ->
                val tr = song.toTrack(thumbnailQuality)
                if (tr.streamables.isEmpty()) {
                    tr.copy(streamables = EchoEnhancedSongEndpoint.createDefaultStreamables(song.id))
                } else tr
            }
            Page(tracks, result.continuation)
        }
        radioFeedCache[radioId] = pagedData

        return Radio(
            id = radioId,
            title = "${artist.name.ifBlank { "Artist" }} Radio",
            cover = artist.cover,
            extras = mapOf("artistId" to artist.id)
        )
    }

    private suspend fun generateFromPlaylist(playlist: Playlist): Radio {
        val track = trackCache[playlist.id]?.toFeed()?.loadAll()?.firstOrNull()
            ?: (trackCache[playlist.id.removePrefix("VL")] ?: trackCache["VL${playlist.id}"])?.toFeed()?.loadAll()?.firstOrNull()
            ?: throw Exception("No tracks found")
        return generateFromTrack(track, null)
    }

    fun loadRadioTracks(radio: Radio): Feed<Track> {
        val cached = radioFeedCache[radio.id]
        if (cached != null) {
            return cached.toFeed()
        }

        val videoId = radio.extras["videoId"] ?: radio.id.removePrefix("radio_")
        val artistId = radio.extras["artistId"]

        val pagedData = if (artistId != null) {
            PagedData.Continuous<Track> { token ->
                val result = api.ArtistRadio.getArtistRadio(artistId, token).getOrThrow()
                val tracks = result.items.map { song ->
                    val tr = song.toTrack(thumbnailQuality)
                    if (tr.streamables.isEmpty()) {
                        tr.copy(streamables = EchoEnhancedSongEndpoint.createDefaultStreamables(song.id))
                    } else tr
                }
                Page(tracks, result.continuation)
            }
        } else {
            PagedData.Continuous<Track> { token ->
                fetchRadioPage(videoId, token)
            }
        }

        radioFeedCache[radio.id] = pagedData
        return pagedData.toFeed()
    }
}
