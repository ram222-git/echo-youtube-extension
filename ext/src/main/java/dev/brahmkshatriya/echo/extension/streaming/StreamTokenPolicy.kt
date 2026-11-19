package dev.brahmkshatriya.echo.extension.streaming

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Clients whose direct HTTPS stream URLs require a PO Token.
 *
 * `visionos` and `TVHTML5` are intentionally absent: they are the token-free
 * lanes. Keep this list in sync with yt-dlp's client policy before adding a
 * client to the extraction cascade.
 */
internal val SpoofClient.requiresStreamToken: Boolean
    get() = when (clientName.uppercase()) {
        "WEB", "WEB_REMIX", "MWEB", "WEB_CREATOR" -> true
        else -> false
    }

/**
 * Appends (or replaces) the `pot` query parameter on a googlevideo URL.
 *
 * Uses the URL builder rather than string concatenation: stream URLs already
 * contain `pot` on some responses, and repeated string appends would produce
 * duplicate parameters that YouTube rejects.
 */
internal fun String.withPoToken(token: String?): String {
    if (token.isNullOrBlank()) return this
    val url = toHttpUrlOrNull() ?: return this
    if (!url.host.endsWith("googlevideo.com", ignoreCase = true)) return this
    if (url.queryParameter("pot") == token) return this
    return url.newBuilder().setQueryParameter("pot", token).build().toString()
}

/**
 * Turns whatever the user typed into a usable provider base URL: adds a scheme,
 * drops trailing slashes and tolerates a pasted `.../get_pot` endpoint. Returns
 * `null` for a blank setting so the provider can report itself as disabled.
 */
internal fun String.normalizedPoTokenBaseUrl(): String? {
    var url = trim()
    if (url.isEmpty()) return null
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        url = "http://$url"
    }
    url = url.trimEnd('/')
    if (url.endsWith("/get_pot", ignoreCase = true)) {
        url = url.dropLast("/get_pot".length).trimEnd('/')
    }
    return url.ifEmpty { null }
}

/**
 * Host + path only. Query strings carry `pot`, `sig`, `expire` and `ip`, all of
 * which are sensitive and must stay out of logs.
 */
internal fun String.redactedStreamUrl(): String {
    val url = toHttpUrlOrNull() ?: return "<unparsable>"
    return "${url.scheme}://${url.host}${url.encodedPath}"
}
