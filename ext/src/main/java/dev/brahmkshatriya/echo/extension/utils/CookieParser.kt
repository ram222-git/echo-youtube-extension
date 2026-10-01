package dev.brahmkshatriya.echo.extension.utils

import java.security.MessageDigest

object CookieParser {
    /**
     * Parses a cookie string (e.g. "a=1; b=2") into a Map of cookie names to values.
     */
    fun parse(cookie: String): Map<String, String> {
        return cookie.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { part ->
                val splitIndex = part.indexOf('=')
                if (splitIndex == -1) null
                else part.substring(0, splitIndex).trim() to part.substring(splitIndex + 1).trim()
            }
            .toMap()
    }

    /**
     * Serializes a Map of cookies back into a cookie header string.
     */
    fun serialize(cookieMap: Map<String, String>): String {
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Merges incoming Set-Cookie headers from an HTTP response into an existing cookie string.
     * Keeps cookies fresh when YouTube / Google updates session cookies like __Secure-1PSIDTS.
     */
    fun mergeCookies(existingCookie: String, setCookieHeaders: List<String>): String {
        if (setCookieHeaders.isEmpty()) return existingCookie
        val map = parse(existingCookie).toMutableMap()
        for (header in setCookieHeaders) {
            val cookiePart = header.substringBefore(';').trim()
            val eqIdx = cookiePart.indexOf('=')
            if (eqIdx != -1) {
                val name = cookiePart.substring(0, eqIdx).trim()
                val value = cookiePart.substring(eqIdx + 1).trim()
                if (name.isNotEmpty()) {
                    map[name] = value
                }
            }
        }
        return serialize(map)
    }

    fun getSapisid(cookieMap: Map<String, String>): String? {
        return cookieMap["SAPISID"]?.takeIf { it.isNotEmpty() }
            ?: cookieMap["__Secure-3PAPISID"]?.takeIf { it.isNotEmpty() }
            ?: cookieMap["__Secure-1PAPISID"]?.takeIf { it.isNotEmpty() }
    }

    /**
     * Generate standard SAPISIDHASH for YouTube Music API authentication.
     */
    fun generateSapisidHash(sapisid: String, origin: String = "https://music.youtube.com"): String {
        val currentTime = System.currentTimeMillis() / 1000
        val str = "$currentTime $sapisid $origin"
        val idHash = MessageDigest.getInstance("SHA-1").digest(str.toByteArray())
            .joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
        return "SAPISIDHASH ${currentTime}_${idHash}"
    }

    /**
     * Generates a fresh dynamic Authorization header with current timestamp.
     * Includes SAPISIDHASH, SAPISID1PHASH, and SAPISID3PHASH if available.
     * This ensures the auth token never expires between requests.
     */
    fun generateDynamicAuthHeader(cookieMap: Map<String, String>, origin: String = "https://music.youtube.com"): String {
        val currentTime = System.currentTimeMillis() / 1000
        val parts = mutableListOf<String>()

        val sapisid = cookieMap["SAPISID"]
        if (!sapisid.isNullOrEmpty()) {
            val str = "$currentTime $sapisid $origin"
            val idHash = MessageDigest.getInstance("SHA-1").digest(str.toByteArray())
                .joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
            parts.add("SAPISIDHASH ${currentTime}_${idHash}")
        }

        val sapisid1p = cookieMap["__Secure-1PAPISID"]
        if (!sapisid1p.isNullOrEmpty()) {
            val str = "$currentTime $sapisid1p $origin"
            val idHash = MessageDigest.getInstance("SHA-1").digest(str.toByteArray())
                .joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
            parts.add("SAPISID1PHASH ${currentTime}_${idHash}")
        }

        val sapisid3p = cookieMap["__Secure-3PAPISID"]
        if (!sapisid3p.isNullOrEmpty()) {
            val str = "$currentTime $sapisid3p $origin"
            val idHash = MessageDigest.getInstance("SHA-1").digest(str.toByteArray())
                .joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
            parts.add("SAPISID3PHASH ${currentTime}_${idHash}")
        }

        return if (parts.isNotEmpty()) parts.joinToString(" ") else {
            // Fallback
            val anySapisid = getSapisid(cookieMap) ?: return ""
            generateSapisidHash(anySapisid, origin)
        }
    }

    fun hasValidSapisid(cookie: String): Boolean {
        val cookieMap = parse(cookie)
        return getSapisid(cookieMap) != null
    }
}
