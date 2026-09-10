package dev.brahmkshatriya.echo.extension.utils

import java.security.MessageDigest

object CookieParser {
    fun parse(cookie: String): Map<String, String> {
        return cookie.split("; ")
            .filter { it.isNotEmpty() }
            .mapNotNull { part ->
                val splitIndex = part.indexOf('=')
                if (splitIndex == -1) null
                else part.substring(0, splitIndex) to part.substring(splitIndex + 1)
            }
            .toMap()
    }

    fun getSapisid(cookieMap: Map<String, String>): String? {
        return cookieMap["SAPISID"]?.takeIf { it.isNotEmpty() }
    }

     //Generate SAPISIDHASH for YouTube Music API authentication.

    fun generateSapisidHash(sapisid: String, origin: String = "https://music.youtube.com"): String {
        val currentTime = System.currentTimeMillis() / 1000
        val str = "$currentTime $sapisid $origin"
        val idHash = MessageDigest.getInstance("SHA-1").digest(str.toByteArray())
            .joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
        return "SAPISIDHASH ${currentTime}_${idHash}"
    }

    fun hasValidSapisid(cookie: String): Boolean {
        val cookieMap = parse(cookie)
        return getSapisid(cookieMap) != null
    }
}
