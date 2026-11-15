//
//  SongArtwork.kt
//  pandoracore
//
//  Port of PandoraCore/SongArtwork.swift — song artwork for a radio
//  station's *identified* song, from the iTunes Search API. A radio
//  station announces "Artist - Title" in its ICY metadata — nothing
//  more — so the cover has to be looked up by that pair. The station's
//  own tile stays the fallback in the app.
//
//  The lookup is deliberately distrustful. The search answers the
//  *best fuzzy match*, which is not always the asked-for song — the
//  motivating case, observed live while building this: a query for
//  "Céline Dion" answered with a track by "Olivier Dion". A wrong
//  cover is worse than no cover, so the answer is verified before it
//  is trusted:
//
//  - **artist must match exactly** after normalization (lowercase,
//    diacritics folded — "Céline" == "celine"), with a primary-artist
//    tolerance for guests the station announces and the store does not
//    ("X feat. Y" is still X's song);
//  - **title must match loosely** — containment either way, because
//    stations announce variants ("Title (Radio Edit)", "Title - Live")
//    that still refer to the same song.
//
//  A result that fails the check yields **no artwork**, not a guess.
//  The lookup runs two passes (combined "artist title" term, then the
//  artist alone) and scans each window — the details are on the
//  client struct below.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// MARK: The result

/** The cover art found for one song. */
data class SongArtwork(
    /** The artwork URL the API returned (its 100×100 size). */
    val artworkURL: String,
    /**
     * The same artwork at 600×600 — the source the full player uses.
     * Falls back to `artworkURL` when the URL does not carry the
     * size token the API normally embeds.
     */
    val largeArtworkURL: String,
) {
    constructor(artworkURL: String) : this(artworkURL, sixHundred(artworkURL))

    companion object {
        /**
         * The API's artwork URLs end in a size token like
         * `100x100bb.jpg`; asking for the large cover is the same URL
         * with `600x600` in its place. Anything that does not carry the
         * token is returned unchanged — the app scales what it gets.
         */
        internal fun sixHundred(url: String): String =
            if (!url.contains("100x100")) url else url.replace("100x100", "600x600")
    }
}

// MARK: Errors

/** Port of `SongArtworkError`. */
sealed class SongArtworkError : Exception() {
    /** The service answered with something that does not decode. */
    data object MalformedResponse : SongArtworkError()
}

// MARK: The lookup

/** Finds song artwork by artist and title. */
fun interface SongArtworkProviding {
    /**
     * The cover for the song, or `null` when the answer cannot be
     * trusted to be the asked-for song (no result, or the verification
     * refused it). Transport failures and malformed answers throw.
     */
    suspend fun artwork(artist: String, title: String): SongArtwork?
}

/**
 * The iTunes Search API client (`https://itunes.apple.com/search`).
 *
 * Two passes, both verified before anything is trusted:
 *
 * 1. **"artist title"** — the search a person would type. The *whole*
 *    result window is scanned for a verified match, not just the top
 *    answer: the API's best fuzzy match is frequently another
 *    artist's same-titled song (verified live 2026-11-12: a "Céline
 *    Dion — Tous les chemins mènent à l'amour" query answered with
 *    Floran, Olivier Dion ×2, Stromae and Rose in its first five —
 *    with `limit=1` the verification could only ever refuse, which is
 *    the "stations identify songs but fetch no artwork" report).
 * 2. **artist only** — the combined query can be too narrow (the
 *    store's title variant drags the ranking down); the artist's own
 *    catalogue is searched and the *title* check picks the song.
 */
class ITunesSongArtworkClient(
    private val transport: HttpGetTransport,
    private val baseURL: String = DEFAULT_BASE_URL,
) : SongArtworkProviding {

    override suspend fun artwork(artist: String, title: String): SongArtwork? {
        searchAndVerify(term = "$artist $title", artist = artist, title = title)?.let { return it }
        // No verified match in the combined window — the artist's own
        // catalogue, verified by title. A refusal here yields no
        // artwork, never a guess.
        return searchAndVerify(term = artist, artist = artist, title = title)
    }

    /** One search pass: fetches the window and returns the first
     *  result that *is* the asked-for song. */
    private suspend fun searchAndVerify(term: String, artist: String, title: String): SongArtwork? {
        val url = baseURL + "?" + listOf(
            "term" to term,
            "entity" to "song",
            "limit" to LIMIT.toString(),
        ).joinToString("&") { (name, value) ->
            "${encodeQueryComponent(name)}=${encodeQueryComponent(value)}"
        }

        val data = transport.get(url, emptyMap())
        val decoded = try {
            val root = Json.parseToJsonElement(data.decodeToString()).jsonObject
            val results = root["results"]?.jsonArray ?: return null
            results.map { result ->
                val obj = result.jsonObject
                ITunesResult(
                    artistName = (obj["artistName"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                    trackName = (obj["trackName"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                    artworkUrl100 = (obj["artworkUrl100"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                )
            }
        } catch (e: Exception) {
            throw SongArtworkError.MalformedResponse
        }
        for (result in decoded) {
            val artwork = result.artworkUrl100 ?: continue
            // The verification per result: a fuzzy window is only
            // trusted one entry at a time. Anything else is skipped —
            // a wrong cover is worse than none.
            if (!artistMatches(result.artistName, artist) ||
                !titleLooselyMatches(result.trackName, title)
            ) {
                continue
            }
            return SongArtwork(artworkURL = artwork)
        }
        return null
    }

    private data class ITunesResult(
        val artistName: String?,
        val trackName: String?,
        val artworkUrl100: String?,
    )

    companion object {
        /** The service's API root. */
        const val DEFAULT_BASE_URL = "https://itunes.apple.com/search"

        /**
         * How many results each pass fetches — big enough to hold the
         * song behind a crowd of same-titled tracks, small enough to stay
         * a light request.
         */
        const val LIMIT = 25

        // MARK: Normalization and the two checks

        /**
         * Lowercase with diacritics folded — the comparison space both
         * checks live in ("Céline" == "celine", "Beyoncé" == "beyonce").
         * Whitespace runs collapse to one space (and the ends trim), so
         * "AB  BA" and "AB BA" are the same artist.
         */
        fun normalized(text: String): String {
            val folded = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            return folded.lowercase()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }

        /**
         * The artist must be the artist: exact equality after
         * normalization — or equality of the *primary* artist, because
         * stations announce guests the store does not carry
         * ("Céline Dion feat. someone" vs "Céline Dion"). The primary
         * comparison still refuses the "Olivier Dion" answer to a
         * "Céline Dion" query.
         */
        fun artistMatches(found: String?, asked: String): Boolean {
            if (found == null) return false
            val foundNormalized = normalized(found)
            val askedNormalized = normalized(asked)
            if (foundNormalized == askedNormalized) return true
            val foundPrimary = primaryArtist(foundNormalized)
            val askedPrimary = primaryArtist(askedNormalized)
            if (foundPrimary.isEmpty() || askedPrimary.isEmpty()) return false
            return foundPrimary == askedPrimary
        }

        /**
         * The artist without its guest markers ("feat. …", "ft …",
         * "featuring …", "vs …", "& …", "with …"). When everything after
         * cutting is empty (an artist *named* by a marker word), the full
         * name is kept — a real artist must never be cut away to nothing.
         */
        fun primaryArtist(normalizedArtist: String): String {
            val markers = setOf("feat", "ft", "featuring", "vs", "with", "&")
            val tokens = normalizedArtist.split(" ")
                .map { it.trim(' ', '.', ',', ';', '(', ')') }
            val primary = mutableListOf<String>()
            for (token in tokens) {
                if (markers.contains(token)) break
                primary.add(token)
            }
            val joined = primary.joinToString(" ")
            return if (joined.isEmpty()) normalizedArtist else joined
        }

        /**
         * The title may differ in its decorations: stations announce
         * variants ("Title (Radio Edit)", "Title - Live") and the store
         * may carry a longer form. Containment either way after
         * normalization accepts those and still refuses a different song.
         */
        fun titleLooselyMatches(found: String?, asked: String): Boolean {
            if (found == null) return false
            val foundTitle = normalized(found)
            val askedTitle = normalized(asked)
            if (foundTitle.isEmpty() || askedTitle.isEmpty()) return false
            return foundTitle.contains(askedTitle) || askedTitle.contains(foundTitle)
        }

        /** Strict RFC 3986 query-component encoding (same rules as URLBuilder). */
        private fun encodeQueryComponent(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = (byte.toInt() and 0xFF).toChar()
                when {
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                        c == '-' || c == '.' || c == '_' || c == '~' -> append(c)
                    else -> append(String.format("%%%02X", byte.toInt() and 0xFF))
                }
            }
        }
    }
}
