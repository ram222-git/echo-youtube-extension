package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.User


object ModelTypeHelper {
    private const val CONVERTED_FROM_USER_KEY = "convertedFromUser"
    private const val CONVERTED_FROM_USER_VALUE = "true"
    
    fun userToArtist(user: User): Artist {
        return Artist(
            id = user.id,
            name = user.name,
            cover = user.cover,
            subtitle = user.subtitle,
            extras = user.extras + (CONVERTED_FROM_USER_KEY to CONVERTED_FROM_USER_VALUE)
        )
    }
    
    fun safeArtistListConversion(list: List<Any>): List<Artist> {
        return list.mapNotNull { item -> 
            when (item) {
                is Artist -> item
                is User -> userToArtist(item)
                else -> {
                    println("Unexpected type in artist list: ${item::class.simpleName}")
                    null
                }
            }
        }
    }
}