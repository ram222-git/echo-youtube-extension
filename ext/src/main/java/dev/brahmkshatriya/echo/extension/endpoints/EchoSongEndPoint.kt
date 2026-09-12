@file:Suppress("unused", "LocalVariableName")

package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.extension.toAlbum
import dev.brahmkshatriya.echo.extension.toArtist
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import dev.toastbits.ytmkt.model.external.Thumbnail
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmMediaItem
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.internal.BrowseEndpoint
import dev.toastbits.ytmkt.model.internal.MusicResponsiveListItemRenderer
import dev.toastbits.ytmkt.model.internal.MusicThumbnailRenderer
import dev.toastbits.ytmkt.model.internal.NavigationEndpoint
import dev.toastbits.ytmkt.model.internal.TextRun
import dev.toastbits.ytmkt.model.internal.TextRuns
import dev.toastbits.ytmkt.model.internal.WatchEndpoint
import dev.toastbits.ytmkt.uistrings.parseYoutubeDurationString
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

open class EchoSongEndPoint(override val api: YoutubeiApi) : ApiEndpoint() {
    suspend fun loadSong(
        @Suppress("LocalVariableName") song_id: String
    ): Result<Track> = runCatching {
        val nextResponse: HttpResponse = api.client.request {
            endpointPath("next")
            addApiHeadersWithAuthenticated()
            postWithBody {
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
                put("videoId", song_id)
                put("playlistId", "RDAMVM$song_id")
            }
        }
        return@runCatching parseSongResponse(song_id, nextResponse, api).getOrThrow()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun parseSongResponse(
        songId: String,
        response: HttpResponse,
        api: YoutubeiApi
    ) = runCatching {
        val responseText = response.bodyAsText()
        val root = json.parseToJsonElement(responseText).jsonObject

        val contents = root["contents"]?.jsonObject
        val tabs = contents?.get("singleColumnMusicWatchNextResultsRenderer")
            ?.jsonObject?.get("tabbedRenderer")
            ?.jsonObject?.get("watchNextTabbedResultsRenderer")
            ?.jsonObject?.get("tabs")?.jsonArray

        var lyricsBrowseId: String? = null
        var relatedBrowseId: String? = null
        var firstVideoRenderer: JsonObject? = null

        tabs?.forEachIndexed { _, tabElement ->
            val tabRenderer = tabElement.jsonObject["tabRenderer"]?.jsonObject ?: return@forEachIndexed
            val browseEndpoint = tabRenderer["endpoint"]?.jsonObject?.get("browseEndpoint")?.jsonObject
            val browseId = browseEndpoint?.get("browseId")?.jsonPrimitive?.contentOrNull

            val titleElem = tabRenderer["title"]
            val tabTitle = (runCatching { titleElem?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: runCatching { titleElem?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull }.getOrNull())?.lowercase()

            if (browseId?.startsWith("MPLY") == true || tabTitle?.contains("lyric") == true) {
                if (lyricsBrowseId == null && browseId != null) {
                    lyricsBrowseId = browseId
                }
            }
            if (browseId?.startsWith("MPTR") == true || browseId?.startsWith("FEmusic_relat") == true || tabTitle?.contains("relat") == true) {
                if (relatedBrowseId == null && browseId != null) {
                    relatedBrowseId = browseId
                }
            }

            if (firstVideoRenderer == null) {
                val queueRenderer = tabRenderer["content"]?.jsonObject?.get("musicQueueRenderer")?.jsonObject
                val playlistPanel = queueRenderer?.get("content")?.jsonObject?.get("playlistPanelRenderer")?.jsonObject
                val contents = playlistPanel?.get("contents")?.jsonArray
                val firstItem = contents?.firstOrNull()?.jsonObject
                val video = firstItem?.get("playlistPanelVideoRenderer")?.jsonObject
                    ?: firstItem?.get("playlistPanelVideoWrapperRenderer")?.jsonObject?.get("primaryRenderer")?.jsonObject?.get("playlistPanelVideoRenderer")?.jsonObject
                if (video != null) {
                    firstVideoRenderer = video
                }
            }
        }

        val playerOverlays = root["playerOverlays"]?.jsonObject
        val actions = playerOverlays?.get("playerOverlayRenderer")?.jsonObject
            ?.get("actions")?.jsonArray
        val likeStatus = actions?.firstNotNullOfOrNull { action ->
            action.jsonObject["likeButtonRenderer"]?.jsonObject
                ?.get("likeStatus")?.jsonPrimitive?.contentOrNull
        }
        val isLiked = likeStatus == "LIKE"

        val video = firstVideoRenderer
        var title = video?.get("title")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "Unknown"

        val longRuns = video?.get("longBylineText")?.jsonObject?.get("runs")?.jsonArray
        val shortRuns = video?.get("shortBylineText")?.jsonObject?.get("runs")?.jsonArray

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

        val artistsList = mutableListOf<YtmArtist>()
        var detectedAlbumId: String? = null
        var detectedAlbumTitle: String? = null

        // 1. LongRuns is the authoritative source where YouTube provides separate runs for each artist
        if (longRuns != null) {
            for (runElement in longRuns) {
                val runObj = runElement.jsonObject
                val text = runObj["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                if (text.isEmpty()) continue

                val browseEndpoint = runObj["navigationEndpoint"]?.jsonObject?.get("browseEndpoint")?.jsonObject
                val browseId = browseEndpoint?.get("browseId")?.jsonPrimitive?.contentOrNull
                val pageType = browseEndpoint?.get("browseEndpointContextSupportedConfigs")?.jsonObject
                    ?.get("browseEndpointContextMusicConfig")?.jsonObject
                    ?.get("pageType")?.jsonPrimitive?.contentOrNull

                if (isAlbumOrPlaylist(pageType, browseId) || browseId?.startsWith("MPREb_") == true || browseId?.startsWith("OLAK5uy_") == true || pageType?.contains("ALBUM", ignoreCase = true) == true) {
                    if (detectedAlbumId == null && browseId != null) {
                        detectedAlbumId = browseId
                        detectedAlbumTitle = text
                    } else if (detectedAlbumTitle == null) {
                        detectedAlbumTitle = text
                    }
                    continue
                }

                if (text == "•" || text == "·" || dev.brahmkshatriya.echo.extension.utils.ArtistUtils.isDelimiter(text)) continue
                if (dev.brahmkshatriya.echo.extension.utils.ArtistUtils.isMetadataRun(text)) continue
                if (text.equals(title, ignoreCase = true) && browseId?.startsWith("UC") != true) continue

                if (isArtistType(pageType, browseId) || browseId?.startsWith("UC") == true) {
                    artistsList.add(YtmArtist(browseId ?: "", text))
                } else if (artistsList.isEmpty() && browseId == null) {
                    artistsList.add(YtmArtist("", text))
                } else if (detectedAlbumTitle == null && browseId == null && artistsList.isNotEmpty()) {
                    detectedAlbumTitle = text
                }
            }
        }

        // 2. Fallback to shortRuns if longRuns yielded nothing
        if (shortRuns != null) {
            for (runElement in shortRuns) {
                val runObj = runElement.jsonObject
                val text = runObj["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                if (text.isEmpty() || text == "•" || text == "·" || dev.brahmkshatriya.echo.extension.utils.ArtistUtils.isDelimiter(text)) continue

                val browseEndpoint = runObj["navigationEndpoint"]?.jsonObject?.get("browseEndpoint")?.jsonObject
                val browseId = browseEndpoint?.get("browseId")?.jsonPrimitive?.contentOrNull
                val pageType = browseEndpoint?.get("browseEndpointContextSupportedConfigs")?.jsonObject
                    ?.get("browseEndpointContextMusicConfig")?.jsonObject
                    ?.get("pageType")?.jsonPrimitive?.contentOrNull

                if (isAlbumOrPlaylist(pageType, browseId) || browseId?.startsWith("MPREb_") == true || browseId?.startsWith("OLAK5uy_") == true || pageType?.contains("ALBUM", ignoreCase = true) == true) {
                    if (detectedAlbumId == null && browseId != null) {
                        detectedAlbumId = browseId
                        detectedAlbumTitle = text
                    } else if (detectedAlbumTitle == null) {
                        detectedAlbumTitle = text
                    }
                    continue
                }
                if (dev.brahmkshatriya.echo.extension.utils.ArtistUtils.isMetadataRun(text)) continue

                if (artistsList.isEmpty()) {
                    artistsList.add(YtmArtist(browseId ?: "", text))
                }
            }
        }

        // 3. Fallback to menu for album browseId if title was detected or menu has album item
        if (detectedAlbumId == null) {
            val menuItems = video?.get("menu")?.jsonObject?.get("menuRenderer")?.jsonObject?.get("items")?.jsonArray
            menuItems?.forEach { menuItem ->
                val nav = menuItem.jsonObject["menuNavigationItemRenderer"]?.jsonObject
                val iconType = nav?.get("icon")?.jsonObject?.get("iconType")?.jsonPrimitive?.contentOrNull
                val bEndpoint = nav?.get("navigationEndpoint")?.jsonObject?.get("browseEndpoint")?.jsonObject
                val bId = bEndpoint?.get("browseId")?.jsonPrimitive?.contentOrNull
                val bPageType = bEndpoint?.get("browseEndpointContextSupportedConfigs")?.jsonObject
                    ?.get("browseEndpointContextMusicConfig")?.jsonObject
                    ?.get("pageType")?.jsonPrimitive?.contentOrNull
                if (iconType == "ALBUM" || isAlbumOrPlaylist(bPageType, bId)) {
                    if (bId != null) {
                        detectedAlbumId = bId
                    }
                }
            }
        }

        // 3. Sanitize artists
        var artists = artistsList.filter {
            it.name?.isNotBlank() == true &&
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

        // Split combined artists if any single YtmArtist has combined names
        val splitArtists = mutableListOf<YtmArtist>()
        for (artist in artists) {
            val name = artist.name.orEmpty()
            if (dev.brahmkshatriya.echo.extension.utils.ArtistUtils.shouldSplitArtist(name)) {
                val splitNames = dev.brahmkshatriya.echo.extension.utils.ArtistUtils.splitArtistNames(name)
                if (splitNames.size > 1) {
                    splitNames.forEachIndexed { index, splitName ->
                        val id = if (index == 0 && artist.id.startsWith("UC")) artist.id else ""
                        splitArtists.add(YtmArtist(id, splitName))
                    }
                    continue
                }
            }
            splitArtists.add(artist)
        }
        artists = splitArtists.distinctBy { it.id.ifEmpty { it.name } }

        // Fallback to /player endpoint if title or artists are missing or Unknown
        if (title == "Unknown" || artists.isEmpty() || artists.all { it.name == "Unknown" }) {
            val playerData: PlayerData? = runCatching {
                val playerResponse = api.client.request {
                    endpointPath("player")
                    addApiHeadersWithAuthenticated()
                    postWithBody {
                        put("videoId", songId)
                    }
                }
                playerResponse.body<PlayerData>()
            }.getOrNull()

            val details = playerData?.videoDetails
            if (title == "Unknown" && details?.title != null) {
                title = details.title
            }
            val author = details?.author?.takeIf { it.isNotBlank() && it != "Unknown" }
            if (author != null && (artists.isEmpty() || artists.all { it.name == "Unknown" })) {
                val channelId = details.channelId.orEmpty()
                if (dev.brahmkshatriya.echo.extension.utils.ArtistUtils.shouldSplitArtist(author)) {
                    val splitNames = dev.brahmkshatriya.echo.extension.utils.ArtistUtils.splitArtistNames(author)
                    artists = splitNames.mapIndexed { idx, name ->
                        val id = if (idx == 0 && channelId.startsWith("UC")) channelId else ""
                        YtmArtist(id, name)
                    }
                } else {
                    artists = listOf(YtmArtist(channelId, author))
                }
            }
        }

        val lengthText = video?.get("lengthText")?.jsonObject?.get("runs")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        val duration = lengthText?.let { parseYoutubeDurationString(it, api.data_language) }

        val thumbnails = video?.get("thumbnail")?.jsonObject?.get("thumbnails")?.jsonArray
        val coverUrl = thumbnails?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
        val cover = coverUrl?.toImageHolder()

        val album = if (!detectedAlbumTitle.isNullOrBlank() && detectedAlbumTitle != "Unknown") {
            dev.brahmkshatriya.echo.common.models.Album(
                id = detectedAlbumId ?: "album_${detectedAlbumTitle.hashCode()}",
                title = detectedAlbumTitle,
                cover = cover,
                artists = artists.map { it.toArtist(ThumbnailProvider.Quality.HIGH) }
            )
        } else null

        Track(
            id = songId,
            title = title,
            cover = cover,
            artists = artists.map { it.toArtist(ThumbnailProvider.Quality.HIGH) },
            album = album,
            duration = duration,
            extras = mutableMapOf<String, String>().apply {
                relatedBrowseId?.let { put("relatedId", it) }
                lyricsBrowseId?.let { put("lyricsId", it) }
                put("isLiked", isLiked.toString())
            }
        )
    }
}

@Serializable
private data class PlayerData(
    val videoDetails: VideoDetails? = null,
) {
    @Serializable
    data class VideoDetails(
        val title: String? = null,
        val author: String? = null,
        val channelId: String? = null,
    )
}


@Serializable
data class YoutubeiNextResponse(
    val contents: Contents,
    val playerOverlays: PlayerOverlays? = null
) {

    @Serializable
    data class PlayerOverlays(
        val playerOverlayRenderer: PlayerOverlayRenderer? = null
    )

    @Serializable
    data class PlayerOverlayRenderer(
        val actions: List<PlayerOverlayRendererAction>? = null,
        val browserMediaSession: BrowserMediaSession? = null
    )

    @Serializable
    data class PlayerOverlayRendererAction(
        val likeButtonRenderer: LikeButtonRenderer? = null
    )

    @Serializable
    data class LikeButtonRenderer(
        val target: Target? = null,
        val likeStatus: String? = null,
        val trackingParams: String? = null,
        val likesAllowed: Boolean? = null,
        val serviceEndpoints: List<ServiceEndpoint>? = null
    )

    @Serializable
    data class ServiceEndpoint(
        val clickTrackingParams: String? = null,
        val likeEndpoint: LikeEndpoint? = null
    )

    @Serializable
    data class LikeEndpoint(
        val status: String? = null,
        val target: Target? = null,
        val actions: List<LikeEndpointAction>? = null,
        val likeParams: String? = null,
        val dislikeParams: String? = null,
        val removeLikeParams: String? = null
    )

    @Serializable
    data class LikeEndpointAction(
        val clickTrackingParams: String? = null,
        val musicLibraryStatusUpdateCommand: MusicLibraryStatusUpdateCommand? = null
    )

    @Serializable
    data class MusicLibraryStatusUpdateCommand(
        val libraryStatus: String? = null,
        val addToLibraryFeedbackToken: String? = null
    )

    @Serializable
    data class Target(
        val videoId: String? = null
    )

    @Serializable
    data class BrowserMediaSession(
        val browserMediaSessionRenderer: BrowserMediaSessionRenderer? = null
    )

    @Serializable
    data class BrowserMediaSessionRenderer(
        val album: Album? = null,
        val thumbnailDetails: ThumbnailDetails? = null
    )

    @Serializable
    data class Album(
        val runs: List<Run>? = null
    )

    @Serializable
    data class Run(
        val text: String? = null
    )

    @Serializable
    data class ThumbnailDetails(
        val thumbnails: List<Thumbnail>? = null
    )

    @Serializable
    class Contents(val singleColumnMusicWatchNextResultsRenderer: SingleColumnMusicWatchNextResultsRenderer)

    @Serializable
    class SingleColumnMusicWatchNextResultsRenderer(val tabbedRenderer: TabbedRenderer)

    @Serializable
    class TabbedRenderer(val watchNextTabbedResultsRenderer: WatchNextTabbedResultsRenderer)

    @Serializable
    class WatchNextTabbedResultsRenderer(val tabs: List<Tab>)

    @Serializable
    class Tab(val tabRenderer: TabRenderer)

    @Serializable
    class TabRenderer(val content: Content?, val endpoint: TabRendererEndpoint?)

    @Serializable
    class TabRendererEndpoint(val browseEndpoint: BrowseEndpoint)

    @Serializable
    class Content(val musicQueueRenderer: MusicQueueRenderer? = null)

    @Serializable
    class MusicQueueRenderer(
        val content: MusicQueueRendererContent?,
        val subHeaderChipCloud: SubHeaderChipCloud?
    )

    @Serializable
    class SubHeaderChipCloud(val chipCloudRenderer: ChipCloudRenderer)

    @Serializable
    class ChipCloudRenderer(val chips: List<Chip>)

    @Serializable
    class Chip(private val chipCloudChipRenderer: ChipCloudChipRenderer) {
        fun getPlaylistId(): String? =
            chipCloudChipRenderer.navigationEndpoint.queueUpdateCommand.fetchContentsCommand.watchEndpoint.playlistId
    }

    @Serializable
    class ChipCloudChipRenderer(val navigationEndpoint: ChipNavigationEndpoint)

    @Serializable
    class ChipNavigationEndpoint(val queueUpdateCommand: QueueUpdateCommand)

    @Serializable
    class QueueUpdateCommand(val fetchContentsCommand: FetchContentsCommand)

    @Serializable
    class FetchContentsCommand(val watchEndpoint: WatchEndpoint)

    @Serializable
    class MusicQueueRendererContent(val playlistPanelRenderer: PlaylistPanelRenderer)

    @Serializable
    class PlaylistPanelRenderer(val contents: List<ResponseRadioItem>)

    @Serializable
    data class ResponseRadioItem(
        val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer?,
        val playlistPanelVideoWrapperRenderer: PlaylistPanelVideoWrapperRenderer?
    ) {
        private fun getRenderer(): PlaylistPanelVideoRenderer {
            if (playlistPanelVideoRenderer != null) {
                return playlistPanelVideoRenderer
            }

            if (playlistPanelVideoWrapperRenderer == null) {
                throw NotImplementedError("Unimplemented renderer object in ResponseRadioItem")
            }

            return playlistPanelVideoWrapperRenderer.primaryRenderer.getRenderer()
        }
    }

    @Serializable
    class PlaylistPanelVideoWrapperRenderer(
        val primaryRenderer: ResponseRadioItem
    )

    @Serializable
    class PlaylistPanelVideoRenderer(
        val videoId: String,
        val title: TextRuns,
        private val longBylineText: TextRuns,
        val lengthText: TextRuns,
        val menu: Menu,
        val thumbnail: MusicThumbnailRenderer.RendererThumbnail,
        val badges: List<MusicResponsiveListItemRenderer.Badge>?
    ) {
        fun getArtists(): Result<List<YtmArtist>?> = runCatching {
            val artists: List<YtmArtist> = (longBylineText.runs.orEmpty() + title.runs.orEmpty())
                .mapNotNull { run ->
                    val browseId: String = run.navigationEndpoint?.browseEndpoint?.browseId
                        ?: return@mapNotNull null

                    val isArtist = run.browse_endpoint_type?.let { type ->
                        YtmMediaItem.Type.fromBrowseEndpointType(type) == YtmMediaItem.Type.ARTIST
                    } ?: (browseId.startsWith("UC") || run.navigationEndpoint?.browseEndpoint?.getPageType()?.contains("ARTIST", ignoreCase = true) == true)

                    if (!isArtist) {
                        return@mapNotNull null
                    }

                    val artistName = run.text.trim().takeIf { it.isNotEmpty() && it != "•" } ?: return@mapNotNull null
                    YtmArtist(
                        id = browseId,
                        name = artistName
                    )
                }

            if (artists.isNotEmpty()) {
                return@runCatching artists
            }

            val validRuns = longBylineText.runs.orEmpty().filter {
                val t = it.text.trim()
                t.isNotEmpty() && t != "•" && t != "·" && !t.matches(Regex("""[•·\s]+"""))
            }

            val menuArtistId: String? =
                menu.menuRenderer.getArtist()?.menuNavigationItemRenderer?.navigationEndpoint?.browseEndpoint?.browseId

            val firstNonAlbumRun = validRuns.firstOrNull {
                it.navigationEndpoint?.browseEndpoint?.getPageType() != "MUSIC_PAGE_TYPE_ALBUM" &&
                !it.text.trim().matches(Regex("""\d{4}"""))
            }

            if (firstNonAlbumRun != null) {
                return@runCatching listOf(
                    YtmArtist(
                        id = menuArtistId ?: firstNonAlbumRun.navigationEndpoint?.browseEndpoint?.browseId ?: "artist-$videoId",
                        name = firstNonAlbumRun.text.trim()
                    )
                )
            }

            return@runCatching null
        }

        fun getAlbum(): YtmPlaylist? {
            for (run in longBylineText.runs.orEmpty()) {
                if (run.navigationEndpoint?.browseEndpoint?.getPageType() != "MUSIC_PAGE_TYPE_ALBUM") {
                    continue
                }

                val playlist_id: String =
                    run.navigationEndpoint?.browseEndpoint?.browseId ?: continue
                return YtmPlaylist(
                    id = playlist_id,
                    name = run.text,
                    year = longBylineText.runs?.find { it.text.length == 4 }?.text?.toIntOrNull(),
                )
            }

            return null
        }
    }

    @Serializable
    data class Menu(val menuRenderer: MenuRenderer)

    @Serializable
    data class MenuRenderer(val items: List<MenuItem>) {
        fun getArtist(): MenuItem? =
            items.firstOrNull {
                it.menuNavigationItemRenderer?.icon?.iconType == "ARTIST"
            }
    }

    @Serializable
    data class MenuItem(val menuNavigationItemRenderer: MenuNavigationItemRenderer?)

    @Serializable
    data class MenuNavigationItemRenderer(
        val icon: MenuIcon,
        val navigationEndpoint: NavigationEndpoint
    )

    @Serializable
    data class MenuIcon(val iconType: String)
}

