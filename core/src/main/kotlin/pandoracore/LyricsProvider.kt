//
//  LyricsProvider.kt
//  pandoracore
//
//  Port of PandoraCore/LyricsProvider.swift — fetches time-synchronised
//  lyrics from lrclib.net and, when the lyrics are not in English, an
//  English translation from a translation service.
//
//  **lrclib.net** (https://lrclib.net) — a free, open lyrics database
//  with a documented JSON API. The endpoints and response fields were
//  verified against the live service and against the server's own source
//  (`github.com/tranxuanthang/lrclib`, `server/src/router.rs`):
//
//    - `GET /api/get?artist_name=…&track_name=…&album_name=…&duration=…`
//      → one track record (404 when nothing matches)
//    - `GET /api/get/{id}` → one track record by id
//    - `GET /api/search?track_name=…&artist_name=…` → an array of records
//
//  A record looks like:
//
//    { "id": 496, "name": "Creep", "trackName": "Creep",
//      "artistName": "Radiohead", "albumName": "Pablo Honey",
//      "duration": 239.0, "instrumental": false, "hasWordSync": false,
//      "plainLyrics": "…", "syncedLyrics": "[00:12.00]…", "lyricsfile": null }
//
//  The service asks clients to identify themselves with a `User-Agent`
//  (it also accepts `Lrclib-Client` / `X-User-Agent`), and answers
//  `503 {"name":"ServerOverloaded"}` when it is busy — that is retried,
//  not reported as an error.
//
//  **Translation**: lrclib itself has no translation endpoint, so the
//  English line comes from a separate translation service (the
//  `translate_a` endpoint of Google Translate, with `sl=auto` so the
//  source language is detected). The detected language comes back in the
//  response, so English lyrics are recognised and left alone.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// MARK: Errors

/** Port of `LyricsError`. */
sealed class LyricsError : Exception() {
    /** The lyrics service is busy (HTTP 503) — the caller may retry. */
    data object ServiceBusy : LyricsError()

    /** The service answered with something unexpected. */
    data object MalformedResponse : LyricsError()

    /** The track has no lyrics at all. */
    data object NotFound : LyricsError()
}

// MARK: Provider

/** Fetches lyrics for a track. */
fun interface LyricsProviding {
    suspend fun lyrics(track: Track): Lyrics
}

/** The lrclib.net client. */
class LRCLibClient(
    private val transport: HttpGetTransport,
    private val baseURL: String = DEFAULT_BASE_URL,
    /** The identifying string the service asks for. */
    private val userAgent: String = DEFAULT_USER_AGENT,
    /** How many times a busy service is retried. */
    private val maxRetries: kotlin.Int = 2,
) : LyricsProviding {

    /** Looks the track up by its metadata (title / artist / album /
     *  duration) and returns its lyrics. */
    override suspend fun lyrics(track: Track): Lyrics {
        val items = mutableListOf(
            "track_name" to track.title,
            "artist_name" to track.artist,
        )
        if (track.album.isNotEmpty()) {
            items.add("album_name" to track.album)
        }
        if (track.duration > 0) {
            items.add("duration" to track.duration.toString())
        }
        val url = "$baseURL/get?" + items.joinToString("&") { (name, value) ->
            "${queryEncode(name)}=${queryEncode(value)}"
        }

        val data = fetch(url)
        val record = decodeRecord(data) ?: throw LyricsError.MalformedResponse
        return Lyrics(
            lines = record.syncedLyrics?.let { LRC.parse(it).lines } ?: emptyList(),
            plainText = if (record.syncedLyrics == null) record.plainLyrics else null,
            instrumental = record.instrumental,
            trackID = record.id,
        )
    }

    /** One request with a retry when the service reports it is busy. */
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
                    // A short pause before trying again — the service asks
                    // clients to retry in a moment.
                    try {
                        delay(400L * attempt)
                    } catch (_: Exception) {
                        // Swift used `try?` — a cancelled sleep proceeds.
                    }
                } else if (error.reason == "HTTP 404") {
                    throw LyricsError.NotFound
                } else {
                    throw error
                }
            }
        }
    }

    private data class Record(
        val id: Long,
        val instrumental: Boolean,
        val plainLyrics: String?,
        val syncedLyrics: String?,
    )

    private fun decodeRecord(data: ByteArray): Record? = try {
        val obj = Json.parseToJsonElement(data.decodeToString()).jsonObject
        Record(
            id = (obj["id"] as JsonPrimitive).content.toLong(),
            instrumental = (obj["instrumental"] as? JsonPrimitive)?.content == "true",
            plainLyrics = (obj["plainLyrics"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
            syncedLyrics = (obj["syncedLyrics"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        /** The service's API root. */
        const val DEFAULT_BASE_URL = "https://lrclib.net/api"
        const val DEFAULT_USER_AGENT = "Amphora (https://github.com/thesagezm/amphora-ios)"

        internal fun queryEncode(value: String): String = buildString {
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

// MARK: Translation

/**
 * The outcome of an English-translation request.
 *
 * The three answers are genuinely different to the user: a translation
 * to show, lyrics that are already English (nothing to do), and a
 * failed request (worth a retry). Collapsing the last two into "no
 * translation" is what left the player unable to explain itself — and
 * unable to offer a second try.
 */
sealed class LyricsTranslation {
    /** The English lines — one per input line. */
    data class Translated(val lines: List<String>) : LyricsTranslation()

    /** The lyrics are already in English; the service detected it. */
    data object AlreadyEnglish : LyricsTranslation()

    /**
     * The service could not be reached, or answered with something
     * unexpected. The reason is for the log, not for the UI.
     */
    data class Failed(val reason: String) : LyricsTranslation()
}

/** Translates lyric lines into English. */
fun interface LyricsTranslating {
    /** The English version of `lines`. */
    suspend fun englishTranslation(lines: List<String>): LyricsTranslation
}

/**
 * Spells lyric lines in alphabet letters ("사랑해" → "salanghae") for
 * lyrics written in a non-Latin script, so the user can sing along.
 */
fun interface LyricsRomanizing {
    /**
     * The romanized version of `lines`, one entry per line, or null when
     * the lines need none (Latin script) or none could be produced.
     */
    suspend fun romanization(lines: List<String>): List<String>?
}

/**
 * The Google `translate_a` endpoint with automatic source-language
 * detection. The response is
 * `[[["translated","original",…]],null,"<detected source>",…]`.
 */
class GoogleTranslateClient(
    private val transport: HttpGetTransport,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val userAgent: String = LRCLibClient.DEFAULT_USER_AGENT,
) : LyricsTranslating, LyricsRomanizing {

    override suspend fun englishTranslation(lines: List<String>): LyricsTranslation {
        // Empty lines are not sent. The service skips blank input
        // entirely, so a blank line between sections would shift the
        // answer's alignment for every line after it — the misaligned
        // answer would then be refused and the whole translation lost
        // (the "translate doesn't work" report). Empty lines carry no
        // words; their translation slot stays "".
        val sentIndices = lines.indices.filter { lines[it].trim().isNotEmpty() }
        val sentLines = sentIndices.map { lines[it] }
        if (sentLines.isEmpty()) return LyricsTranslation.Failed("empty lyrics")

        val url = endpoint + "?" + listOf(
            "client" to "gtx",
            "sl" to "auto",
            "tl" to "en",
            "dt" to "t",
            "q" to sentLines.joinToString("\n"),
        ).joinToString("&") { (name, value) ->
            "${LRCLibClient.queryEncode(name)}=${LRCLibClient.queryEncode(value)}"
        }

        // The free endpoint rate-limits by IP (HTTP 429) — a short
        // backoff and a retry, like the lyrics service's own busy
        // answer. A shared carrier IP can 429 the first request of a
        // song and answer the next fine.
        var fetched: ByteArray? = null
        var attempt = 0
        while (fetched == null) {
            try {
                fetched = transport.get(url, mapOf("User-Agent" to userAgent))
            } catch (error: PandoraTransportError) {
                if (error.reason == "HTTP 429" && attempt < 2) {
                    attempt++
                    try {
                        delay(700L * attempt)
                    } catch (_: Exception) {
                        // Swift used `try?`.
                    }
                } else {
                    return LyricsTranslation.Failed(error.reason)
                }
            } catch (error: Exception) {
                return LyricsTranslation.Failed("network failure")
            }
        }
        val response = decodeResponse(fetched)
            ?: return LyricsTranslation.Failed("unexpected response")
        // Already English — no translation needed.
        val detected = response.detectedSourceLanguage?.lowercase() ?: ""
        if (detected == "en" || detected.startsWith("en-")) return LyricsTranslation.AlreadyEnglish

        // The alignment, in two shapes the service answers with:
        // - one segment per line (the common case) maps directly;
        // - the service splits *long* lines into several segments —
        //   then the segment texts carry the line breaks, and joining
        //   them and splitting back reconstructs the lines.
        // Either way the count must match the *sent* lines exactly, or
        // the answer is refused — never shown against the wrong words.
        val direct = response.segments.map { it.translated }
        val sentTranslations: List<String> = if (direct.size == sentLines.size) {
            direct
        } else {
            val rejoined = direct.joinToString("").split("\n")
            if (rejoined.size != sentLines.size) {
                return LyricsTranslation.Failed("unexpected response")
            }
            rejoined
        }

        // Map the answer back onto the original line list: the lines
        // that were sent keep their translation, the empty ones stay
        // empty.
        val translated = Array(lines.size) { "" }
        for ((sentIndex, translation) in sentIndices.zip(sentTranslations)) {
            translated[sentIndex] = translation
        }
        return LyricsTranslation.Translated(translated.toList())
    }

    /** The service's reply: `[[segments], null, detectedLanguage, …]`. */
    private data class Segment(val translated: String, val original: String)

    private data class Response(val segments: List<Segment>, val detectedSourceLanguage: String?)

    private fun decodeResponse(data: ByteArray): Response? = try {
        val root = Json.parseToJsonElement(data.decodeToString()).jsonArray
        val segmentsJson = root[0].jsonArray
        val segments = segmentsJson.map { seg ->
            val fields = seg.jsonArray
            Segment(
                translated = (fields[0] as JsonPrimitive).content,
                original = (fields[1] as JsonPrimitive).content,
            )
        }
        val detected = (root.getOrNull(2) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        Response(segments, detected)
    } catch (_: Exception) {
        null
    }

    // MARK: Romanization

    /**
     * The same `translate_a` endpoint the translation uses, with the
     * `dt=rm` mode instead of `dt=t`. Verified live:
     * "사랑해" → "salanghae", "君の名は" → "Kiminonaha", "我爱你" →
     * pinyin; the reply preserves line breaks, so the result aligns
     * with the input lines. Latin-script input answers with **no
     * segments** (`[null,null,"en",…]`) — that is the signal that
     * there is nothing to romanize.
     */
    override suspend fun romanization(lines: List<String>): List<String>? {
        val text = lines.joinToString("\n")
        if (text.isEmpty()) return null
        // Only non-Latin scripts can be romanized. Skipping the request
        // outright keeps English/French/Portuguese lyrics at exactly one
        // Google request (the translation) — the endpoint rate-limits
        // probing IPs with a 302 to its "unusual traffic" page.
        if (!containsNonLatinScript(text)) return null

        val url = endpoint + "?" + listOf(
            "client" to "gtx",
            "sl" to "auto",
            "tl" to "en",
            "dt" to "rm",
            "q" to text,
        ).joinToString("&") { (name, value) ->
            "${LRCLibClient.queryEncode(name)}=${LRCLibClient.queryEncode(value)}"
        }

        val data = try {
            transport.get(url, mapOf("User-Agent" to userAgent))
        } catch (_: Exception) {
            return null
        }
        val response = decodeRomanizationResponse(data) ?: return null
        // Latin-script input carries no segments — nothing to do.
        val segments = response.segments
        if (segments.isNullOrEmpty()) return null
        val parts = segments.mapNotNull { it.text }
        if (parts.isEmpty()) return null
        // The service keeps the line breaks, so the joined text must
        // split back into exactly the input lines — a mismatched count
        // would shift the alignment, which is never shown.
        val joined = parts.joinToString("\n")
        val romanized = joined.split("\n")
        if (romanized.size == lines.size) return romanized
        // Segmentation without embedded breaks: one segment per line.
        if (parts.size == lines.size) return parts
        return null
    }

    /**
     * The `dt=rm` reply: `[[[null,null,null,"romanized"],…],null,"ko",…]`.
     * A different segment shape from the translation reply — the
     * romanized text sits at index 3, and Latin-script input answers with
     * **no segments at all**.
     */
    private data class RomanizationSegment(val text: String?)

    private data class RomanizationResponse(
        val segments: List<RomanizationSegment>?,
        val detectedSourceLanguage: String?,
    )

    private fun decodeRomanizationResponse(data: ByteArray): RomanizationResponse? = try {
        val root = Json.parseToJsonElement(data.decodeToString()).jsonArray
        val segments = (root.getOrNull(0) as? JsonArray)?.map { seg ->
            val fields = seg.jsonArray
            RomanizationSegment(
                text = fields.getOrNull(3)?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content },
            )
        }
        RomanizationResponse(
            segments = segments,
            detectedSourceLanguage = (root.getOrNull(2) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        /** The translation endpoint. */
        const val DEFAULT_ENDPOINT = "https://translate.googleapis.com/translate_a/single"

        /**
         * `true` when the text contains characters from a script the
         * romanization mode can spell in alphabet letters. Scripts covered:
         * Hangul (syllables + jamo), kana, CJK ideographs, Thai, and
         * Cyrillic — the endpoint transliterates all of them. Latin-script
         * languages (English, French, Portuguese, …) contain none and never
         * reach the endpoint from `romanization`.
         */
        fun containsNonLatinScript(text: String): Boolean =
            text.codePoints().anyMatch { scalar ->
                nonLatinScriptRanges.any { (it.first <= scalar && scalar <= it.last) }
            }

        private val nonLatinScriptRanges: List<LongRange> = listOf(
            0x0400L..0x04FF, // Cyrillic
            0x0E00L..0x0E7F, // Thai
            0x1100L..0x11FF, // Hangul jamo
            0x3040L..0x30FF, // Kana (hiragana + katakana)
            0x3130L..0x318F, // Hangul compatibility jamo
            0x3400L..0x4DBF, // CJK extension A
            0x4E00L..0x9FFF, // CJK unified ideographs
            0xAC00L..0xD7AF, // Hangul syllables
        )
    }
}
