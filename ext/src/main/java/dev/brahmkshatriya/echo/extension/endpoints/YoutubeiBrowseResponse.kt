package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.extension.endpoints.EchoPlaylistEndpoint.Companion.findSongCount
import dev.brahmkshatriya.echo.extension.endpoints.EchoPlaylistEndpoint.Companion.findTrackCount
import dev.toastbits.ytmkt.endpoint.SongFeedFilterChip
import dev.toastbits.ytmkt.impl.youtubei.endpoint.ChipCloudRendererHeader
import dev.toastbits.ytmkt.model.YtmApi
import dev.toastbits.ytmkt.model.external.Thumbnail
import dev.toastbits.ytmkt.model.external.ThumbnailProvider
import dev.toastbits.ytmkt.model.external.mediaitem.YtmArtist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmMediaItem
import dev.toastbits.ytmkt.model.external.mediaitem.YtmPlaylist
import dev.toastbits.ytmkt.model.external.mediaitem.YtmSong
import dev.toastbits.ytmkt.model.internal.HeaderRenderer
import dev.toastbits.ytmkt.model.internal.MusicMultiRowListItemRenderer
import dev.toastbits.ytmkt.model.internal.MusicResponsiveListItemRenderer
import dev.toastbits.ytmkt.model.internal.TextRun
import dev.toastbits.ytmkt.radio.YoutubeiNextResponse
import dev.toastbits.ytmkt.uistrings.YoutubeUiString
import dev.toastbits.ytmkt.uistrings.parseYoutubeDurationString
import kotlinx.serialization.Serializable

@Serializable
data class YoutubeiBrowseResponse(
    val contents: Contents?,
    val continuationContents: ContinuationContents?,
    val header: Header?,
    val microformat: Microformat? = null
) {
    val ctoken: String?
        get() = continuationContents?.sectionListContinuation?.continuations?.firstOrNull()?.nextContinuationData?.continuation
            ?: contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.continuations?.firstOrNull()?.nextContinuationData?.continuation

    fun getShelves(hasContinuation: Boolean): List<YoutubeiShelf> {
        if (hasContinuation) {
            return continuationContents?.sectionListContinuation?.contents ?: emptyList()
        }
        val result = mutableListOf<YoutubeiShelf>()
        contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.let {
            result.addAll(it)
        }
        contents?.twoColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.let {
            result.addAll(it)
        }
        contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents?.let {
            result.addAll(it)
        }
        if (result.isEmpty()) {
            contents?.sectionListRenderer?.contents?.let { result.addAll(it) }
        }
        return result
    }

    fun getHeaderChips(dataLanguage: String): List<SongFeedFilterChip>? =
        contents?.singleColumnBrowseResultsRenderer?.tabs?.first()?.tabRenderer?.content?.sectionListRenderer?.header?.chipCloudRenderer?.chips?.map {
            SongFeedFilterChip(
                YoutubeUiString.Type.FILTER_CHIP.createFromKey(
                    it.chipCloudChipRenderer.text!!.first_text,
                    dataLanguage
                ),
                it.chipCloudChipRenderer.navigationEndpoint.browseEndpoint!!.params!!
            )
        }

    @Serializable
    data class Contents(
        val singleColumnBrowseResultsRenderer: SingleColumnBrowseResultsRenderer?,
        val twoColumnBrowseResultsRenderer: TwoColumnBrowseResultsRenderer?,
        val sectionListRenderer: SectionListRenderer?
    )

    @Serializable
    data class SingleColumnBrowseResultsRenderer(val tabs: List<Tab>)

    @Serializable
    data class Tab(val tabRenderer: TabRenderer)

    @Serializable
    data class TabRenderer(val content: Content?)

    @Serializable
    data class Content(val sectionListRenderer: SectionListRenderer?)

    @Serializable
    data class SectionListRenderer(
        val contents: List<YoutubeiShelf>?,
        val header: ChipCloudRendererHeader?,
        val continuations: List<YoutubeiNextResponse.Continuation>?
    )

    @Serializable
    data class TwoColumnBrowseResultsRenderer(
        val tabs: List<Tab>,
        val secondaryContents: SecondaryContents
    ) {
        @Serializable
        data class SecondaryContents(val sectionListRenderer: SectionListRenderer)
    }

    @Serializable
    data class YoutubeiShelf(
        val musicShelfRenderer: MusicShelfRenderer?,
        val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer?,
        val musicResponsiveHeaderRenderer: MusicResponsiveHeaderRenderer?,
        val musicEditablePlaylistDetailHeaderRenderer: MusicEditablePlaylistDetailHeaderRenderer?,
        val musicCarouselShelfRenderer: MusicCarouselShelfRenderer?,
        val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer?,
        val musicCardShelfRenderer: MusicCardShelfRenderer?,
        val gridRenderer: GridRenderer?,
        val itemSectionRenderer: ItemSectionRenderer?
    ) {
        val title: TextRun?
            get() =
                if (musicShelfRenderer != null) musicShelfRenderer.title?.runs?.firstOrNull()
                else if (musicCarouselShelfRenderer != null) musicCarouselShelfRenderer.header?.getRenderer()?.title?.runs?.firstOrNull()
                else if (musicCardShelfRenderer != null) musicCardShelfRenderer.title.runs?.firstOrNull()
                else if (gridRenderer != null) gridRenderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()
                else null

        fun getMediaItems(hl: String, api: YtmApi) = run {
            val contents = musicShelfRenderer?.contents
                ?: musicPlaylistShelfRenderer?.contents
                ?: musicCarouselShelfRenderer?.contents
                ?: gridRenderer?.items
            contents?.mapNotNull { it.toMediaItemData(hl, api)?.first }
        }

        @Serializable
        data class MusicShelfRenderer(
            val title: dev.toastbits.ytmkt.model.internal.TextRuns?,
            val contents: List<YoutubeiShelfContentsItem>? = null,
            val continuations: List<YoutubeiNextResponse.Continuation>? = null,
            val bottomEndpoint: dev.toastbits.ytmkt.model.internal.NavigationEndpoint?
        )

        @Serializable
        data class YoutubeiShelfContentsItem(
            val musicTwoRowItemRenderer: MusicTwoRowItemRenderer?,
            val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer?,
            val musicMultiRowListItemRenderer: MusicMultiRowListItemRenderer?,
            val musicTwoColumnItemRenderer: MusicTwoColumnItemRenderer?,
            val continuationItemRenderer: ContinuationItemRenderer?
        ) {

            // Pair(item, playlistSetVideoId)
            fun toMediaItemData(hl: String, api: YtmApi): Pair<YtmMediaItem, String?>? {
                if (musicTwoRowItemRenderer != null) {
                    val item = musicTwoRowItemRenderer.toYtmMediaItem(api) ?: return null
                    val enhancedItem = if (item is YtmSong && (item.album == null || item.album?.name.isNullOrBlank() || item.album?.name == "Unknown")) {
                        var foundAlbumTitle: String? = null
                        var foundAlbumId: String? = item.album?.id
                        val runs = musicTwoRowItemRenderer.subtitle?.runs.orEmpty()
                        for (run in runs) {
                            val text = run.text.trim()
                            if (text.isBlank() || text == "•" || text == "·" || dev.brahmkshatriya.echo.extension.utils.ArtistUtils.isDelimiter(text) || dev.brahmkshatriya.echo.extension.utils.ArtistUtils.isMetadataRun(text)) continue
                            val bEndpoint = run.navigationEndpoint?.browseEndpoint
                            val bId = bEndpoint?.browseId
                            val pageType = bEndpoint?.getPageType()
                            val isAlbum = pageType?.contains("ALBUM", ignoreCase = true) == true ||
                                    bId?.startsWith("MPREb_") == true ||
                                    bId?.startsWith("OLAK5uy_") == true ||
                                    (foundAlbumId != null && bId == foundAlbumId)
                            if (isAlbum) {
                                foundAlbumTitle = text
                                if (bId != null) foundAlbumId = bId
                                break
                            }
                        }
                        if (foundAlbumTitle != null) {
                            item.copy(album = YtmPlaylist(id = foundAlbumId ?: "", name = foundAlbumTitle))
                        } else if (item.album?.name.isNullOrBlank() || item.album?.name == "Unknown") {
                            item.copy(album = null)
                        } else item
                    } else item
                    return Pair(enhancedItem, null)
                } else if (musicResponsiveListItemRenderer != null) {
                    val pair = musicResponsiveListItemRenderer.toMediaItemAndPlaylistSetVideoId(hl) ?: return null
                    val item = pair.first
                    val enhancedItem = if (item is YtmSong && (item.album == null || item.album?.name.isNullOrBlank() || item.album?.name == "Unknown")) {
                        var foundAlbumTitle: String? = null
                        var foundAlbumId: String? = item.album?.id
                        val columns = musicResponsiveListItemRenderer.flexColumns.orEmpty()
                        for (col in columns) {
                            val runs = col.musicResponsiveListItemFlexColumnRenderer.text.runs.orEmpty()
                            for (run in runs) {
                                val text = run.text.trim()
                                if (text.isBlank() || text == "•" || text == "·" || dev.brahmkshatriya.echo.extension.utils.ArtistUtils.isDelimiter(text) || dev.brahmkshatriya.echo.extension.utils.ArtistUtils.isMetadataRun(text)) continue
                                val bEndpoint = run.navigationEndpoint?.browseEndpoint
                                val bId = bEndpoint?.browseId
                                val pageType = bEndpoint?.getPageType()
                                val isAlbum = pageType?.contains("ALBUM", ignoreCase = true) == true ||
                                        bId?.startsWith("MPREb_") == true ||
                                        bId?.startsWith("OLAK5uy_") == true ||
                                        (foundAlbumId != null && bId == foundAlbumId)
                                if (isAlbum) {
                                    foundAlbumTitle = text
                                    if (bId != null) foundAlbumId = bId
                                    break
                                }
                            }
                            if (foundAlbumTitle != null) break
                        }
                        if (foundAlbumTitle != null) {
                            item.copy(album = YtmPlaylist(id = foundAlbumId ?: "", name = foundAlbumTitle))
                        } else if (item.album?.name.isNullOrBlank() || item.album?.name == "Unknown") {
                            item.copy(album = null)
                        } else item
                    } else item
                    return Pair(enhancedItem, pair.second)
                } else if (musicMultiRowListItemRenderer != null) {
                    return Pair(musicMultiRowListItemRenderer.toMediaItem(hl), null)
                } else if (musicTwoColumnItemRenderer != null) {
                    return musicTwoColumnItemRenderer.toMediaItemData(hl)
                } else if (continuationItemRenderer != null) return null
                return null
            }
        }

        fun getMediaItemsAndSetIds(hl: String, api: YtmApi) = run {
            val renderer = musicShelfRenderer?.contents ?: musicPlaylistShelfRenderer?.contents
            renderer?.mapNotNull { it.toMediaItemData(hl, api) }
        }

        fun getRenderer() =
            musicShelfRenderer ?: musicPlaylistShelfRenderer ?: musicCarouselShelfRenderer
            ?: musicDescriptionShelfRenderer ?: musicCardShelfRenderer ?: gridRenderer


        fun getPlaylistData(hl: String) = (musicResponsiveHeaderRenderer
            ?: musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer)?.run {
            val title = this.title?.runs?.joinToString("") { it.text }?.trim()?.takeIf { it.isNotBlank() && it != "Unknown" }
                ?: this.title?.runs?.firstOrNull()?.text?.trim()?.takeIf { it.isNotBlank() && it != "Unknown" }
                ?: "Unknown"
            val thumbnail = thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.let {
                ThumbnailProvider.fromThumbnails(it)
            }
            val year = subtitle?.runs?.find { it.text.length == 4 }?.text?.toIntOrNull()
            val description =
                description?.musicDescriptionShelfRenderer?.description?.runs?.joinToString("") {
                    it.text
                }
            val tracks = secondSubtitle?.runs?.findSongCount()
                ?: secondSubtitle?.runs?.findTrackCount()

            val duration = secondSubtitle?.runs?.lastOrNull()?.let {
                parseYoutubeDurationString(it.text, hl)
            }
            val isEditable = thumbnailEditButton?.buttonRenderer?.isDisabled == false
            val artist = facepile?.avatarStackViewModel?.let { model ->
                val url =
                    model.rendererContext?.commandContext?.onTap?.innertubeCommand?.browseEndpoint?.browseId
                        ?: return@let null
                val name = model.rendererContext.accessibilityContext?.label ?: "Unknown"
                YtmArtist(url, name)
            }?.let { listOf(it) }
            val straplineArtists = straplineTextOne?.runs?.mapNotNull {
                val text = it.text?.trim().orEmpty()
                if (text.isBlank() || text == "," || text == "&" || text == "•") return@mapNotNull null
                val id = it.navigationEndpoint?.browseEndpoint?.browseId ?: "artist_$text"
                YtmArtist(id, text)
            } ?: emptyList()
            val subtitleArtists = subtitle?.runs?.mapNotNull {
                val text = it.text?.trim().orEmpty()
                val isArtist = it.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType == "MUSIC_PAGE_TYPE_ARTIST"
                if (!isArtist || text.isBlank() || text == "," || text == "&" || text == "•") return@mapNotNull null
                val id = it.navigationEndpoint?.browseEndpoint?.browseId ?: "artist_$text"
                YtmArtist(id, text)
            } ?: emptyList()
            val allArtists = if (straplineArtists.isNotEmpty()) straplineArtists else subtitleArtists
            val isExplicit =
                subtitleBadge?.any { it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" }
                    ?: false
            PlaylistData(
                title = title,
                description = description,
                thumbnail = thumbnail,
                artists = if (allArtists.isNotEmpty()) allArtists else (artist ?: emptyList()),
                year = year,
                explicit = isExplicit,
                isEditable = isEditable,
                count = tracks,
                duration = duration
            )
        }
    }

    @Serializable
    data class ContinuationItemRenderer(
        val continuationEndpoint: ContinuationEndpoint
    )

    @Serializable
    data class ContinuationEndpoint(
        val continuationCommand: ContinuationCommand
    )

    @Serializable
    data class ContinuationCommand(
        val token: String
    )

    @Serializable
    data class ContinuationContents(
        val sectionListContinuation: SectionListRenderer?,
        val musicPlaylistShelfContinuation: MusicPlaylistShelfRenderer?,
        val musicShelfContinuation: YoutubeiShelf.MusicShelfRenderer?
    )

    data class PlaylistData(
        val title: String?,
        val description: String?,
        val thumbnail: ThumbnailProvider?,
        val artists: List<YtmArtist>,
        val year: Int?,
        val explicit: Boolean = false,
        val isEditable: Boolean = false,
        val count: Int? = null,
        val duration: Long? = null
    )

    @Serializable
    data class Header(
        val musicDetailHeaderRenderer: MusicDetailHeaderRenderer? = null,
        val musicElementHeaderRenderer: MusicElementHeaderRenderer? = null,
        val musicCarouselShelfBasicHeaderRenderer: HeaderRenderer?,
        val musicImmersiveHeaderRenderer: HeaderRenderer?,
        val musicVisualHeaderRenderer: HeaderRenderer?,
        val musicCardShelfHeaderBasicRenderer: HeaderRenderer?
    ) {
        fun getRenderer(): HeaderRenderer? {
            return musicCarouselShelfBasicHeaderRenderer
                ?: musicImmersiveHeaderRenderer
                ?: musicVisualHeaderRenderer
        }
    }

    @Serializable
    data class MusicResponsiveHeaderRenderer(
        val thumbnail: StraplineThumbnailClass? = null,
        val title: TextRuns? = null,
        val subtitle: TextRuns? = null,
        val description: MusicResponsiveHeaderRendererDescription? = null,
        val secondSubtitle: TextRuns? = null,
        val thumbnailEditButton: ThumbnailEditButton? = null,
        val facepile: Facepile? = null,
        val straplineTextOne: TextRuns? = null,
        val straplineThumbnail: StraplineThumbnailClass? = null,
        val subtitleBadge: List<SubtitleBadge>? = null
    )

    @Serializable
    data class MusicResponsiveHeaderRendererDescription(
        val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer? = null
    )

    @Serializable
    data class MusicDescriptionShelfRenderer(
        val description: TextRuns? = null
    )

    @Serializable
    data class URLEndpoint(
        val url: String? = null,
        val target: String? = null
    )

    @Serializable
    data class Facepile(
        val avatarStackViewModel: AvatarStackViewModel? = null
    )

    @Serializable
    data class AvatarStackViewModel(
        val rendererContext: RendererContext? = null,
        val text: Text? = null
    )

    @Serializable
    data class RendererContext(
        val accessibilityContext: AccessibilityData? = null,
        val commandContext: CommandContext? = null
    )

    @Serializable
    data class CommandContext(
        val onTap: OnTap? = null
    )

    @Serializable
    data class OnTap(
        val innertubeCommand: InnertubeCommandClass? = null
    )

    @Serializable
    data class InnertubeCommandClass(
        val browseEndpoint: BrowseEndpoint? = null
    )

    @Serializable
    data class Text(
        val content: String? = null
    )

    @Serializable
    data class TextRuns(
        val runs: List<TextRun>? = null
    )

    @Serializable
    data class StraplineThumbnailClass(
        val musicThumbnailRenderer: MusicThumbnailRenderer? = null
    )

    @Serializable
    data class ThumbnailEditButton(
        val buttonRenderer: ButtonRenderer? = null
    )

    @Serializable
    data class ButtonRenderer(
        val isDisabled: Boolean? = null
    )

    @Serializable
    data class MusicDetailHeaderRenderer(
        val title: TextRuns? = null,
        val subtitle: TextRuns? = null,
        val byline: Byline? = null,
        val menu: Menu? = null,
        val thumbnail: MusicDetailHeaderRendererThumbnail? = null,
        val subtitleBadges: List<SubtitleBadge>? = null,
        val secondTitle: SecondTitle? = null
    )

    @Serializable
    data class Byline(
        val musicDetailHeaderButtonsBylineRenderer: MusicDetailHeaderButtonsBylineRenderer? = null
    )

    @Serializable
    data class MusicDetailHeaderButtonsBylineRenderer(
        val description: TextRuns? = null
    )

    @Serializable
    data class Menu(
        val menuRenderer: MenuRenderer? = null
    )

    @Serializable
    data class MenuRenderer(
        val trackingParams: String? = null,
        val title: Title? = null
    )

    @Serializable
    data class Title(
        val musicMenuTitleRenderer: MusicMenuTitleRenderer? = null
    )

    @Serializable
    data class MusicMenuTitleRenderer(
        val primaryText: TextRuns? = null,
        val secondaryText: SecondaryText? = null,
        val thumbnail: MusicMenuTitleRendererThumbnail? = null
    )

    @Serializable
    data class SecondaryText(
        val runs: List<TextRun>? = null,
        val accessibility: Accessibility? = null
    )

    @Serializable
    data class Accessibility(
        val accessibilityData: AccessibilityData? = null
    )

    @Serializable
    data class AccessibilityData(
        val label: String? = null
    )

    @Serializable
    data class MusicMenuTitleRendererThumbnail(
        val musicThumbnailRenderer: MusicThumbnailRenderer? = null
    )

    @Serializable
    data class MusicThumbnailRenderer(
        val thumbnail: MusicThumbnailRendererThumbnail? = null,
        val thumbnailCrop: String? = null,
        val thumbnailScale: String? = null,
        val trackingParams: String? = null
    )

    @Serializable
    data class MusicThumbnailRendererThumbnail(
        val thumbnails: List<Thumbnail>? = null
    )

    @Serializable
    data class SecondTitle(
        val runs: List<SecondTitleRun>? = null
    )

    @Serializable
    data class SecondTitleRun(
        val text: String? = null,
        val navigationEndpoint: NavigationEndpoint? = null
    )

    @Serializable
    data class NavigationEndpoint(
        val clickTrackingParams: String? = null,
        val browseEndpoint: BrowseEndpoint? = null,
        val urlEndpoint: URLEndpoint? = null
    )

    @Serializable
    data class BrowseEndpoint(
        val browseId: String? = null,
        val browseEndpointContextSupportedConfigs: BrowseEndpointContextSupportedConfigs? = null
    )

    @Serializable
    data class BrowseEndpointContextSupportedConfigs(
        val browseEndpointContextMusicConfig: BrowseEndpointContextMusicConfig? = null
    )

    @Serializable
    data class BrowseEndpointContextMusicConfig(
        val pageType: String? = null
    )

    @Serializable
    data class SubtitleBadge(
        val musicInlineBadgeRenderer: MusicInlineBadgeRenderer? = null
    )

    @Serializable
    data class MusicInlineBadgeRenderer(
        val trackingParams: String? = null,
        val icon: Icon? = null,
        val accessibilityData: Accessibility? = null
    )

    @Serializable
    data class Icon(
        val iconType: String? = null
    )

    @Serializable
    data class MusicDetailHeaderRendererThumbnail(
        val croppedSquareThumbnailRenderer: CroppedSquareThumbnailRenderer? = null
    )

    @Serializable
    data class CroppedSquareThumbnailRenderer(
        val thumbnail: MusicThumbnailRendererThumbnail? = null
    )

    @Serializable
    data class MusicEditablePlaylistDetailHeaderRenderer(
        val header: YoutubeiShelf? = null,
        val editHeader: EditHeader? = null,
        val trackingParams: String? = null,
        val playlistId: String? = null
    )

    @Serializable
    data class EditHeader(
        val musicPlaylistEditHeaderRenderer: MusicPlaylistEditHeaderRenderer? = null
    )

    @Serializable
    data class MusicPlaylistEditHeaderRenderer(
        val title: TextRuns? = null,
        val editTitle: TextRuns? = null,
        val description: TextRuns? = null,
        val editDescription: TextRuns? = null,
        val privacy: String? = null,
        val playlistId: String? = null,
        val metadataFieldsDisabled: Boolean? = null
    )

    @Serializable
    data class MusicElementHeaderRenderer(
        val elementRenderer: MusicElementHeaderRendererElementRenderer? = null,
        val useSplitScreenLayoutOnCompatibleScreenSizes: Boolean? = null
    )

    @Serializable
    data class MusicElementHeaderRendererElementRenderer(
        val elementRenderer: ElementRendererElementRenderer? = null
    )

    @Serializable
    data class ElementRendererElementRenderer(
        val newElement: NewElement? = null
    )

    @Serializable
    data class NewElement(
        val type: Type? = null
    )

    @Serializable
    data class Type(
        val componentType: ComponentType? = null
    )

    @Serializable
    data class ComponentType(
        val model: Model? = null
    )

    @Serializable
    data class Model(
        val musicBlurredBackgroundHeaderModel: MusicBlurredBackgroundHeaderModel? = null
    )

    @Serializable
    data class MusicBlurredBackgroundHeaderModel(
        val data: Data? = null
    )

    @Serializable
    data class Data(
        val title: String? = null,
        val primaryImage: Image? = null,
        val backgroundImage: Image? = null,
        val formattedDescription: FormattedDescription? = null,
        val straplineData: StraplineData? = null,
        val titleMaxLines: Long? = null,
        val formattedTitle: FormattedTitle? = null
    )

    @Serializable
    data class Image(
        val sources: List<Thumbnail>? = null
    )

    @Serializable
    data class FormattedDescription(
        val content: String? = null,
        val lineSpacing: Long? = null,
        val styleRuns: List<StyleRun>? = null
    )

    @Serializable
    data class StyleRun(
        val startIndex: Long? = null,
        val length: Long? = null,
        val fontSize: Long? = null,
        val fontColor: Long? = null
    )

    @Serializable
    data class FormattedTitle(
        val content: String? = null
    )

    @Serializable
    data class StraplineData(
        val textLine1: TextLine? = null,
        val textLine2: TextLine? = null,
        val avatarImage: Image? = null,
        val headerOnTapCommand: HeaderOnTapCommand? = null
    )

    @Serializable
    data class HeaderOnTapCommand(
        val innertubeCommand: NavigationEndpoint? = null
    )

    @Serializable
    data class TextLine(
        val content: String? = null,
        val styleRuns: List<StyleRun>? = null
    )

    @Serializable
    data class Microformat(
        val microformatDataRenderer: MicroformatDataRenderer? = null
    )

    @Serializable
    data class MicroformatDataRenderer(
        val title: String? = null
    )

}
