package dev.brahmkshatriya.echo.extension.utils

import dev.brahmkshatriya.echo.common.models.Artist

object ArtistUtils {
    private val KNOWN_BAND_NAMES = setOf(
        "simon & garfunkel",
        "earth, wind & fire",
        "brooks & dunn",
        "dan + shay",
        "hall & oates",
        "daryl hall & john oates",
        "crosby, stills & nash",
        "crosby, stills, nash & young",
        "florence + the machine",
        "florence and the machine",
        "of monsters and men",
        "blood, sweat & tears",
        "kc & the sunshine band",
        "kool & the gang",
        "tom petty and the heartbreakers",
        "bob marley & the wailers",
        "huey lewis & the news",
        "joan jett & the blackhearts",
        "iron & wine",
        "king gizzard & the lizard wizard",
        "above & beyond",
        "angus & julia stone",
        "belle and sebastian",
        "captain & tennille",
        "death grips",
        "mumford & sons"
    )

    private val ARTIST_SPLIT_REGEX = Regex("""\s+(?:&|feat\.|ft\.|feat|ft|and|x|X)\s+|\s*,\s*|\s*/\s*""")

    fun isDelimiter(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.isEmpty() ||
                lower == "•" || lower == "·" || lower == "," || lower == "&" || lower == "/" || lower == "+" ||
                lower == "feat." || lower == "ft." || lower == "feat" || lower == "ft" ||
                lower == "and" || lower == "with" || lower.matches(Regex("""^[•·,&/+\s]+$"""))
    }

    fun isMetadataRun(text: String): Boolean {
        return text.matches(Regex("""^\d{4}$""")) ||
                text.matches(Regex("""^\d{1,2}:\d{2}$""")) ||
                text.endsWith("views", ignoreCase = true) ||
                text.endsWith("likes", ignoreCase = true) ||
                text.endsWith("plays", ignoreCase = true)
    }

    fun shouldSplitArtist(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (KNOWN_BAND_NAMES.contains(trimmed.lowercase())) return false
        return trimmed.contains(" & ") ||
                trimmed.contains(", ") ||
                trimmed.contains(" feat. ", ignoreCase = true) ||
                trimmed.contains(" ft. ", ignoreCase = true) ||
                trimmed.contains(" feat ", ignoreCase = true) ||
                trimmed.contains(" ft ", ignoreCase = true) ||
                trimmed.contains(" / ")
    }

    fun splitArtistNames(name: String): List<String> {
        return name.split(ARTIST_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() && !isDelimiter(it) }
    }

    /**
     * Splits combined artists into individual Artist objects.
     */
    fun splitCombinedArtists(artists: List<Artist>): List<Artist> {
        val result = mutableListOf<Artist>()
        for (artist in artists) {
            if (shouldSplitArtist(artist.name)) {
                val names = splitArtistNames(artist.name)
                if (names.size > 1) {
                    names.forEachIndexed { index, name ->
                        val id = if (index == 0 && artist.id.startsWith("UC")) artist.id else ""
                        result.add(Artist(id = id, name = name))
                    }
                    continue
                }
            }
            result.add(artist)
        }
        return result.distinctBy { it.id.ifEmpty { it.name } }
    }
}
