//
//  LyricsFallbacks.kt
//  pandoracore
//
//  Port of PandoraCore/LyricsFallbacks.swift — fallback lyrics sources,
//  so a track whose lyrics the first provider does not have still gets
//  words.
//
//  The exact-match endpoint of lrclib (`/api/get`) is strict: it
//  matches on title **and** artist **and** album **and** duration, so
//  a wrong album name or a two-second duration difference answers
//  404 even when the lyrics exist. Two fallbacks close that gap:
//
//  1. **lrclib's own search** (`/api/search?track_name=…&artist_name=…`)
//     — the same database, but fuzzy: it answers with every record it
//     knows for the title/artist. The candidate is then scored against
//     the track (artist match, title match, duration proximity) and
//     the best one wins. This is the same idea as pithos' own
//     fallback path, which searches the database when the direct
//     lookup comes back empty.
//
//  2. **lyrics.ovh** (`https://api.lyrics.ovh/v1/{artist}/{title}`) — a
//     completely different database. It only serves plain (not
//     time-synchronised) lyrics, but it covers a lot of songs lrclib
//     does not have.
//
//  Both are pure logic + a GET transport — no UI, no Android, so
//  pandoracore stays portable.
//

package pandoracore

import java.text.Normalizer
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

// MARK: Candidate scoring

/**
 * One record a search endpoint returned, before it is scored against
 * the track being played.
 */
data class LyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String? = null,
    /** The record's length in seconds, when the source sent one. */
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    /** The lyrics this candidate carries, parsed into the app's model. */
    val lyrics: Lyrics
        get() {
            if (!syncedLyrics.isNullOrEmpty()) {
                val parsed = LRC.parse(syncedLyrics)
                if (parsed.isSynced) {
                    return Lyrics(
                        lines = parsed.lines,
                        instrumental = instrumental,
                        trackID = id,
                    )
                }
            }
            return Lyrics(
                plainText = plainLyrics,
                instrumental = instrumental,
                trackID = id,
            )
        }
}

/**
 * Scores a search result against the track being played.
 *
 * The scoring is deliberately simple and deterministic: a wrong song
 * with the same title ("Brilliant" by one artist vs another) must not
 * win over the right one, and a live cover (different length, same
 * song) should still be usable.
 */
object LyricsMatchScorer {
    /** The score below which a candidate is not the song we are looking
     *  for. */
    const val MINIMUM_SCORE = 30

    /**
     * Scores `candidate` for `track`. Higher is better; a negative
     * score means "not this song".
     */
    fun score(track: Track, candidate: LyricsCandidate): kotlin.Int {
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        val candidateTitle = normalize(candidate.trackName)
        val candidateArtist = normalize(candidate.artistName)

        var score = 0

        // The artist decides first: two songs can share a title
        // ("Brilliant" by several artists), and the wrong artist's
        // version is the wrong song — no amount of title agreement
        // makes up for it.
        if (artist.isNotEmpty() && candidateArtist.isNotEmpty()) {
            score = when {
                artist == candidateArtist -> score + 40
                artist.contains(candidateArtist) || candidateArtist.contains(artist) -> score + 20
                else -> return -100 // A different artist is disqualifying on its own.
            }
        }

        // The title is the second signal.
        if (title.isNotEmpty() && candidateTitle.isNotEmpty()) {
            score = when {
                title == candidateTitle -> score + 50
                title.contains(candidateTitle) || candidateTitle.contains(title) -> score + 25
                else -> return -100
            }
        }

        // The length is a tie-breaker: a different recording of the same
        // song is usually still the right song.
        if (track.duration > 0 && candidate.duration != null) {
            val difference = kotlin.math.abs(track.duration - candidate.duration)
            when {
                difference <= 3 -> score += 20
                difference <= 10 -> score += 10
                difference > 30 -> score -= 15
            }
        }

        return score
    }

    /**
     * Lower-cases, strips punctuation and diacritics, and collapses
     * whitespace, so "ÆØÅ" / "A-ha!" / "A HA" compare equal.
     */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        val cleaned = folded.map { c ->
            if (c.isLetterOrDigit()) c else ' '
        }.joinToString("")
        return cleaned.split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
}

// MARK: lrclib search

/**
 * The lrclib *search* endpoint — the fuzzy fallback for the strict
 * `/api/get` lookup.
 */
class LRCLibSearchClient(
    private val transport: HttpGetTransport,
    private val baseURL: String = LRCLibClient.DEFAULT_BASE_URL,
    private val userAgent: String = LRCLibClient.DEFAULT_USER_AGENT,
    private val maxRetries: kotlin.Int = 2,
) : LyricsProviding {

    override suspend fun lyrics(track: Track): Lyrics {
        val url = "$baseURL/search?" + listOf(
            "track_name" to track.title,
            "artist_name" to track.artist,
        ).joinToString("&") { (name, value) ->
            "${LRCLibClient.queryEncode(name)}=${LRCLibClient.queryEncode(value)}"
        }

        val data = fetch(url)
        val candidates = decodeCandidates(data) ?: throw LyricsError.MalformedResponse

        val best = bestCandidate(track, candidates) ?: throw LyricsError.NotFound
        val lyrics = best.lyrics
        if (lyrics.isEmpty) {
            throw LyricsError.NotFound
        }
        return lyrics
    }

    /**
     * The candidate with the best score, or null when none scores
     * above the minimum.
     */
    internal fun bestCandidate(
        track: Track,
        candidates: List<LyricsCandidate>,
    ): LyricsCandidate? = candidates
        .map { it to LyricsMatchScorer.score(track = track, candidate = it) }
        .filter { it.second >= LyricsMatchScorer.MINIMUM_SCORE }
        .maxByOrNull { it.second }
        ?.first

    private suspend fun fetch(url: String): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return transport.get(
                    url,
                    mapOf("User-Agent" to userAgent, "Lrclib-Client" to userAgent),
                )
            } catch (error: PandoraTransportError) {
                if (error.reason == "HTTP 503") {
                    attempt++
                    if (attempt > maxRetries) throw LyricsError.ServiceBusy
                    try {
                        delay(400L * attempt)
                    } catch (_: Exception) {
                        // Swift used `try?`.
                    }
                } else if (error.reason == "HTTP 404") {
                    throw LyricsError.NotFound
                } else {
                    throw error
                }
            }
        }
    }

    /** One record of the search reply (the same shape `/api/get`
     *  returns, but in an array). */
    private fun decodeCandidates(data: ByteArray): List<LyricsCandidate>? = try {
        Json.parseToJsonElement(data.decodeToString()).jsonArray.map { element ->
            val obj = element.jsonObject
            fun stringField(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            fun doubleField(key: String): Double? =
                (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toDoubleOrNull()
            LyricsCandidate(
                id = ((obj["id"] as? JsonPrimitive)?.content ?: "0").toLong(),
                trackName = stringField("trackName") ?: "",
                artistName = stringField("artistName") ?: "",
                albumName = stringField("albumName"),
                duration = doubleField("duration"),
                instrumental = (obj["instrumental"] as? JsonPrimitive)?.content == "true",
                plainLyrics = stringField("plainLyrics"),
                syncedLyrics = stringField("syncedLyrics"),
            )
        }
    } catch (_: Exception) {
        null
    }
}

// MARK: lyrics.ovh

/**
 * The lyrics.ovh API — a second, independent lyrics database.
 *
 * It only serves plain lyrics (no timestamps), and it is keyed by
 * artist and title, so it works for songs lrclib does not have.
 */
class LyricsOvhClient(
    private val transport: HttpGetTransport,
    private val baseURL: String = DEFAULT_BASE_URL,
    private val userAgent: String = LRCLibClient.DEFAULT_USER_AGENT,
) : LyricsProviding {

    override suspend fun lyrics(track: Track): Lyrics {
        if (track.artist.isEmpty() || track.title.isEmpty()) {
            throw LyricsError.NotFound
        }
        // The path components are percent-encoded by hand — an artist
        // like "AC/DC" would otherwise break the URL (a slash in a path
        // component reads as a separator).
        val encodedArtist = percentEncodePathComponent(track.artist)
        val encodedTitle = percentEncodePathComponent(track.title)
        val url = "$baseURL/v1/$encodedArtist/$encodedTitle"

        val data = try {
            transport.get(url, mapOf("User-Agent" to userAgent))
        } catch (error: PandoraTransportError) {
            if (error.reason == "HTTP 404") throw LyricsError.NotFound
            throw error
        }

        val replyLyrics = try {
            (Json.parseToJsonElement(data.decodeToString()).jsonObject["lyrics"] as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }?.content
        } catch (_: Exception) {
            throw LyricsError.MalformedResponse
        }
        val text = (replyLyrics ?: "").trim()
        if (text.isEmpty()) throw LyricsError.NotFound
        return Lyrics(plainText = text)
    }

    /**
     * Everything a path segment may contain — but never a slash. The
     * port mirrors the Swift `urlPathAllowed minus "/"` set: unreserved
     * plus the path-safe sub-delims (percent kept for already-encoded
     * input).
     */
    private fun percentEncodePathComponent(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '-' || c == '.' || c == '_' || c == '~' ||
                    c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' ||
                    c == ')' || c == '*' || c == '+' || c == ',' || c == ';' ||
                    c == '=' || c == ':' || c == '@' -> append(c)
                else -> append(String.format("%%%02X", byte.toInt() and 0xFF))
            }
        }
    }

    companion object {
        /** The lyrics.ovh API root. */
        const val DEFAULT_BASE_URL = "https://api.lyrics.ovh"
    }
}

// MARK: The chain

/**
 * Tries several providers in order and returns the first lyrics it
 * gets.
 *
 * This is what makes a missing lyric a non-event: the strict exact
 * match comes first, then the fuzzy search of the same database, then
 * a completely different database. A provider that fails is skipped —
 * only when *every* provider has nothing does the chain report
 * "not found".
 */
class LyricsProviderChain(
    private val providers: List<LyricsProviding>,
) : LyricsProviding {

    /**
     * The chain Amphora uses: exact match → fuzzy search → a second
     * database.
     */
    companion object {
        fun defaultChain(
            transport: HttpGetTransport,
        ): LyricsProviderChain = LyricsProviderChain(
            listOf(
                LRCLibClient(transport),
                LRCLibSearchClient(transport),
                LyricsOvhClient(transport),
            ),
        )
    }

    override suspend fun lyrics(track: Track): Lyrics {
        var lastError: Exception = LyricsError.NotFound
        for (provider in providers) {
            try {
                val lyrics = provider.lyrics(track)
                if (!lyrics.isEmpty) {
                    return lyrics
                }
                lastError = LyricsError.NotFound
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError
    }
}
