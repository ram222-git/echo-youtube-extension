package dev.brahmkshatriya.echo.extension.endpoints

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import dev.toastbits.ytmkt.model.ApiEndpoint
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs

class EchoLyricsEndPoint(override val api: YoutubeiApi) : ApiEndpoint() {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ParsedLyrics(
        val lyric: Lyrics.Lyric,
        val source: String? = null
    )

    private fun webRemixContext(): JsonObject = buildJsonObject {
        put("context", buildJsonObject {
            put("client", buildJsonObject {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20260304.03.00")
                put("hl", "en")
                put("gl", "US")
            })
        })
    }

    suspend fun getLyrics(
        id: String?,
        title: String? = null,
        artist: String? = null,
        durationMs: Long? = null
    ): ParsedLyrics? {
        // 1. If YouTube browseId is provided, attempt to fetch from YouTube Music
        var ytmLyrics: ParsedLyrics? = null
        if (!id.isNullOrBlank() && !id.startsWith("lrclib_")) {
            ytmLyrics = fetchYtmLyrics(id)
            // If YouTube Music provided timed lyrics, return immediately
            if (ytmLyrics?.lyric is Lyrics.Timed) {
                return ytmLyrics
            }
        }

        // 2. Query LRCLIB for synchronized / timed lyrics
        if (!title.isNullOrBlank()) {
            val lrcLyrics = fetchLrcLibLyrics(title, artist, durationMs)
            if (lrcLyrics != null && lrcLyrics.lyric is Lyrics.Timed) {
                return lrcLyrics
            }
            // If LRCLIB returned plain lyrics and YouTube Music didn't have any, use LRCLIB plain
            if (lrcLyrics != null && ytmLyrics == null) {
                return lrcLyrics
            }
        }

        // 3. Fallback to YouTube Music plain text lyrics if available
        if (ytmLyrics != null) {
            return ytmLyrics
        }

        return null
    }

    suspend fun searchLyrics(query: String): List<Lyrics> = runCatching {
        val response: HttpResponse = api.client.get("https://lrclib.net/api/search") {
            header("User-Agent", "Echo/1.0")
            url {
                parameters.append("q", query)
            }
        }
        if (response.status.value != 200) return@runCatching emptyList()
        val body = response.bodyAsText()
        val array = json.parseToJsonElement(body).jsonArray
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val trackName = obj["trackName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val artistName = obj["artistName"]?.jsonPrimitive?.contentOrNull
            val parsed = parseLrcLibJsonObject(obj) ?: return@mapNotNull null
            Lyrics("lrclib_$id", trackName, artistName, parsed.lyric)
        }
    }.getOrDefault(emptyList())

    private suspend fun fetchYtmLyrics(id: String): ParsedLyrics? = runCatching {
        val response: HttpResponse = api.client.request {
            endpointPath("browse")
            addApiHeadersWithAuthenticated()
            postWithBody(webRemixContext()) {
                put("browseId", id)
            }
        }
        val responseText = response.bodyAsText()
        val root = json.parseToJsonElement(responseText).jsonObject

        // 1. Try parsing synchronized / timed lyrics (elementRenderer)
        val timedLyrics = parseTimedLyrics(root)
        if (timedLyrics != null) {
            return@runCatching timedLyrics
        }

        // 2. Try parsing plain text lyrics (musicDescriptionShelfRenderer)
        val textLyrics = parseTextLyrics(root)
        if (textLyrics != null) {
            return@runCatching textLyrics
        }

        null
    }.onFailure {
        println("EchoLyricsEndPoint: Failed to fetch YTM lyrics for $id: ${it.message}")
    }.getOrNull()

    private fun parseTimedLyrics(root: JsonObject): ParsedLyrics? {
        return runCatching {
            val contents = root["contents"]?.jsonObject ?: return null
            val elementRenderer = contents["elementRenderer"]?.jsonObject
                ?: contents["sectionListRenderer"]?.jsonObject
                    ?.get("contents")?.jsonArray?.firstNotNullOfOrNull {
                        it.jsonObject["elementRenderer"]?.jsonObject
                    }
                ?: return null

            val timedLyricsModel = elementRenderer["newElement"]?.jsonObject
                ?.get("type")?.jsonObject
                ?.get("componentType")?.jsonObject
                ?.get("model")?.jsonObject
                ?.get("timedLyricsModel")?.jsonObject
                ?: return null

            val lyricsData = timedLyricsModel["lyricsData"]?.jsonObject ?: return null
            val timedLyricsData = lyricsData["timedLyricsData"]?.jsonArray ?: return null
            val sourceMessage = lyricsData["sourceMessage"]?.jsonPrimitive?.contentOrNull

            val items = timedLyricsData.mapNotNull { datumElement ->
                val datum = datumElement.jsonObject
                val line = datum["lyricLine"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val cueRange = datum["cueRange"]?.jsonObject ?: return@mapNotNull null
                val startMs = cueRange["startTimeMilliseconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val endMs = cueRange["endTimeMilliseconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: (startMs + 3000L)
                Lyrics.Item(line, startMs, endMs)
            }

            if (items.isNotEmpty()) {
                ParsedLyrics(Lyrics.Timed(items), sourceMessage)
            } else null
        }.getOrNull()
    }

    private fun parseTextLyrics(root: JsonObject): ParsedLyrics? {
        return runCatching {
            val contents = root["contents"]?.jsonObject ?: return null
            val sectionListRenderer = contents["sectionListRenderer"]?.jsonObject ?: return null
            val sectionContents = sectionListRenderer["contents"]?.jsonArray ?: return null

            val shelfRenderer = sectionContents.firstNotNullOfOrNull {
                it.jsonObject["musicDescriptionShelfRenderer"]?.jsonObject
            } ?: return null

            val descriptionRuns = shelfRenderer["description"]?.jsonObject
                ?.get("runs")?.jsonArray

            val text = descriptionRuns?.mapNotNull {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }?.joinToString("")?.trim()

            val footerRuns = shelfRenderer["footer"]?.jsonObject
                ?.get("runs")?.jsonArray

            val source = footerRuns?.mapNotNull {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }?.joinToString("")?.trim()

            if (!text.isNullOrBlank()) {
                ParsedLyrics(Lyrics.Simple(text), source.takeIf { it?.isNotBlank() == true })
            } else null
        }.getOrNull()
    }

    private suspend fun fetchLrcLibLyrics(
        title: String,
        artist: String?,
        durationMs: Long?
    ): ParsedLyrics? = runCatching {
        val clean = cleanTitle(title)
        val cleanArt = cleanArtist(artist, title)
        val durationSec = durationMs?.let { it / 1000L }

        // 1. Try exact get with duration
        var match = fetchLrcLibGet(clean, cleanArt, durationSec)
        if (match != null && match.lyric is Lyrics.Timed) return@runCatching match

        // 2. Try exact get without duration (in case video intro/outro differs slightly)
        if (match == null && durationSec != null) {
            match = fetchLrcLibGet(clean, cleanArt, null)
            if (match != null && match.lyric is Lyrics.Timed) return@runCatching match
        }

        // 3. Try search endpoint
        val searchMatch = fetchLrcLibSearch(clean, cleanArt, durationSec)
        if (searchMatch != null) return@runCatching searchMatch

        // 4. Fallback to match (e.g. plain lyrics) if found earlier
        match
    }.onFailure {
        println("EchoLyricsEndPoint: Failed to fetch LRCLIB lyrics for '$title': ${it.message}")
    }.getOrNull()

    private suspend fun fetchLrcLibGet(
        title: String,
        artist: String?,
        durationSec: Long?
    ): ParsedLyrics? = runCatching {
        val response: HttpResponse = api.client.get("https://lrclib.net/api/get") {
            header("User-Agent", "Echo/1.0")
            url {
                parameters.append("track_name", title)
                if (!artist.isNullOrBlank()) {
                    parameters.append("artist_name", artist)
                }
                if (durationSec != null && durationSec > 0) {
                    parameters.append("duration", durationSec.toString())
                }
            }
        }
        if (response.status.value != 200) return@runCatching null
        val body = response.bodyAsText()
        parseLrcLibJsonObject(json.parseToJsonElement(body).jsonObject)
    }.getOrNull()

    private suspend fun fetchLrcLibSearch(
        title: String,
        artist: String?,
        durationSec: Long?
    ): ParsedLyrics? = runCatching {
        val response: HttpResponse = api.client.get("https://lrclib.net/api/search") {
            header("User-Agent", "Echo/1.0")
            url {
                parameters.append("track_name", title)
                if (!artist.isNullOrBlank()) {
                    parameters.append("artist_name", artist)
                }
            }
        }
        if (response.status.value != 200) return@runCatching null
        val body = response.bodyAsText()
        val array = json.parseToJsonElement(body).jsonArray
        if (array.isEmpty()) {
            return@runCatching fetchLrcLibSearchQuery("$title ${artist.orEmpty()}".trim(), durationSec)
        }

        findBestLrcMatch(array, durationSec)
    }.getOrNull()

    private suspend fun fetchLrcLibSearchQuery(
        query: String,
        durationSec: Long?
    ): ParsedLyrics? = runCatching {
        val response: HttpResponse = api.client.get("https://lrclib.net/api/search") {
            header("User-Agent", "Echo/1.0")
            url {
                parameters.append("q", query)
            }
        }
        if (response.status.value != 200) return@runCatching null
        val body = response.bodyAsText()
        val array = json.parseToJsonElement(body).jsonArray
        findBestLrcMatch(array, durationSec)
    }.getOrNull()

    private fun findBestLrcMatch(array: JsonArray, durationSec: Long?): ParsedLyrics? {
        val objects = array.mapNotNull { it as? JsonObject }
        if (objects.isEmpty()) return null

        val withSynced = objects.filter {
            val synced = it["syncedLyrics"]?.jsonPrimitive?.contentOrNull
            !synced.isNullOrBlank()
        }

        if (withSynced.isNotEmpty()) {
            val best = if (durationSec != null && durationSec > 0) {
                withSynced.minByOrNull { item ->
                    val d = item["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L
                    abs(d - durationSec)
                } ?: withSynced.first()
            } else {
                withSynced.first()
            }
            val parsed = parseLrcLibJsonObject(best)
            if (parsed != null) return parsed
        }

        return objects.firstNotNullOfOrNull { item ->
            parseLrcLibJsonObject(item)
        }
    }

    private fun parseLrcLibJsonObject(item: JsonObject): ParsedLyrics? {
        val synced = item["syncedLyrics"]?.jsonPrimitive?.contentOrNull
        val artist = item["artistName"]?.jsonPrimitive?.contentOrNull
        val source = if (!artist.isNullOrBlank()) "LRCLIB • $artist" else "LRCLIB"

        if (!synced.isNullOrBlank()) {
            val timed = parseLrcToTimedLyrics(synced)
            if (timed != null) {
                return ParsedLyrics(timed, source)
            }
        }

        val plain = item["plainLyrics"]?.jsonPrimitive?.contentOrNull
        if (!plain.isNullOrBlank()) {
            return ParsedLyrics(Lyrics.Simple(plain), source)
        }

        return null
    }

    private fun parseLrcToTimedLyrics(lrcContent: String): Lyrics.Timed? {
        val timestampRegex = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")
        data class RawLine(val timeMs: Long, val text: String)

        val rawLines = mutableListOf<RawLine>()
        lrcContent.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            val matches = timestampRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach

            val text = trimmed.substring(matches.last().range.last + 1).trim()

            matches.forEach { match ->
                val min = match.groupValues[1].toLongOrNull() ?: return@forEach
                val secFloat = match.groupValues[2].toDoubleOrNull() ?: return@forEach
                val timeMs = (min * 60_000L) + (secFloat * 1000.0).toLong()
                rawLines.add(RawLine(timeMs, text))
            }
        }

        if (rawLines.isEmpty()) return null
        rawLines.sortBy { it.timeMs }

        val items = mutableListOf<Lyrics.Item>()
        for (i in rawLines.indices) {
            val current = rawLines[i]
            if (current.text.isBlank()) continue

            val nextStart = rawLines.subList(i + 1, rawLines.size)
                .firstOrNull { it.timeMs > current.timeMs }?.timeMs
                ?: (current.timeMs + 4000L)

            items.add(Lyrics.Item(current.text, current.timeMs, nextStart))
        }

        return if (items.isNotEmpty()) Lyrics.Timed(items) else null
    }

    private fun cleanTitle(title: String): String {
        var cleaned = title
        if (cleaned.contains(" - ")) {
            val parts = cleaned.split(" - ")
            if (parts.size >= 2) {
                cleaned = parts.drop(1).joinToString(" - ")
            }
        }
        cleaned = cleaned.replace(
            Regex("""(?i)\s*[\(\[](?:official\s+)?(?:music\s+)?(?:video|audio|visualizer|lyric(?:s)?(?:\s+video)?|hd|4k|live|remaster(?:ed)?).*?[\)\]]"""),
            ""
        )
        cleaned = cleaned.replace(
            Regex("""(?i)\s*[\(\[]?(?:feat\.?|ft\.?)\s+[^\)\]]+[\)\]]?"""),
            ""
        )
        cleaned = cleaned.replace(Regex("""\s*[-|/]\s*$"""), "")
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String?, rawTitle: String): String? {
        var clean = artist?.replace(Regex("""(?i)\s*-\s*Topic$"""), "")?.trim()
        clean = clean?.replace(Regex("""(?i)^LRCLIB\s*[•\-]\s*"""), "")?.trim()
        if (clean.isNullOrBlank() || clean.equals("Unknown", ignoreCase = true)) {
            clean = if (rawTitle.contains(" - ")) {
                rawTitle.split(" - ").first().trim()
            } else {
                null
            }
        }
        return clean?.ifBlank { null }
    }
}
