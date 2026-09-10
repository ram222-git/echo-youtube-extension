package dev.brahmkshatriya.echo.extension.providers.social

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.helpers.ClientException


class ShareManager {
    fun getShareUrl(item: EchoMediaItem): String {
        return when (item) {
            is Album -> "https://music.youtube.com/browse/${item.id}"
            is Playlist -> "https://music.youtube.com/playlist?list=${item.id}"
            is Radio -> "https://music.youtube.com/playlist?list=${item.id}"
            is Artist -> "https://music.youtube.com/channel/${item.id}"
            is Track -> "https://music.youtube.com/watch?v=${item.id}"
        }
    }
}
