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

    private fun sha1Hex(input: String): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
    }

    private fun formatSapisidHash(prefix: String, sapisid: String, origin: String, timestamp: Long): String {
        val hash = sha1Hex("$timestamp $sapisid $origin")
        return "$prefix ${timestamp}_$hash"
    }

    /**
     * Generate standard SAPISIDHASH for YouTube Music API authentication.
     */
    fun generateSapisidHash(sapisid: String, origin: String = "https://music.youtube.com"): String {
        val currentTime = System.currentTimeMillis() / 1000
        return formatSapisidHash("SAPISIDHASH", sapisid, origin, currentTime)
    }

    /**
     * Generates a fresh dynamic Authorization header with current timestamp.
     * Uses SAPISID (or 3P/1P fallback) to produce a single standard SAPISIDHASH.
     * This ensures the auth token never expires between requests and matches YouTube's expected format.
     */
    fun generateDynamicAuthHeader(cookieMap: Map<String, String>, origin: String = "https://music.youtube.com"): String {
        val sapisid = getSapisid(cookieMap) ?: return ""
        return generateSapisidHash(sapisid, origin)
    }

    fun hasValidSapisid(cookie: String): Boolean {
        val cookieMap = parse(cookie)
        return getSapisid(cookieMap) != null
    }
}
