//
//  Lyrics.kt
//  pandoracore
//
//  Port of PandoraCore/Lyrics.swift — time-synced lyrics: the LRC format
//  and the model the player renders.
//
//  LRC is the de-facto standard for synchronised lyrics. A line looks
//  like `[01:23.45] the words`, where the bracket holds the timestamp
//  at which the line starts. Several details matter in the wild and are
//  handled here:
//
//  - **Several timestamps per line** — `[00:12.00][01:12.00] chorus`
//    repeats the same words later in the song.
//  - **Fractional seconds** with one, two or three digits, separated by
//    `.` or `:` (`[01:23.4]`, `[01:23.45]`, `[01:23.456]`,
//    `[01:23:45]`).
//  - **Metadata tags** — `[ar:]`, `[ti:]`, `[al:]`, `[by:]`, `[length:]`,
//    `[offset:]`. A tag is not a timestamp and must not be rendered as
//    a lyric.
//  - **Plain (unsynchronised) lyrics** — the same file without any
//    timestamps; they are kept as text so the UI can still show them.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

// MARK: Line

/** One lyric line: when it starts and what it says. */
@Serializable
data class LyricLine(
    /** Start of the line, in seconds from the beginning of the track. */
    val timestamp: Double,
    /** The words. */
    val text: String,
    /**
     * English translation of `text` (null when the lyrics are already in
     * English, or when no translation was fetched).
     */
    var translation: String? = null,
    /**
     * `text` spelled in alphabet letters (romanization) for lyrics
     * written in a non-Latin script — "사랑해" → "salanghae". Null when the
     * line needs none, or none was fetched.
     */
    var romanization: String? = null,
) {
    val id: String
        get() = "$timestamp-$text"

    /**
     * The line as it should be displayed: the original words, with the
     * translation underneath when there is one.
     */
    val displayText: String
        get() = translation?.takeIf { it.isNotEmpty() } ?: text
}

// MARK: Lyrics

/**
 * A track's lyrics: either synchronised lines, plain text, or nothing
 * (instrumental / not found).
 */
@Serializable
data class Lyrics(
    /** The synchronised lines, ordered by timestamp. Empty when the
     *  lyrics are plain text. */
    val lines: List<LyricLine> = emptyList(),
    /** Un-synchronised lyrics (the whole text). Null when the lyrics are
     *  synchronised. */
    val plainText: String? = null,
    /**
     * The English translation of `plainText` — the counterpart of
     * `LyricLine.translation` for lyrics that carry no timestamps.
     * Null when the lyrics are synchronised, already in English, or no
     * translation was fetched.
     */
    var plainTranslation: String? = null,
    /** The track is instrumental — there are no words to show. */
    val instrumental: Boolean = false,
    /** The id of the lyric record at the source (lrclib's `id`), used
     *  for caching. */
    val trackID: Long? = null,
) {
    companion object {
        /** No lyrics at all. */
        val empty = Lyrics()
    }

    /** `true` when the lyrics can be scrolled in sync with playback. */
    val isSynced: Boolean
        get() = lines.isNotEmpty()

    /** `true` when there is nothing to display. */
    val isEmpty: Boolean
        get() = lines.isEmpty() && (plainText?.isEmpty() ?: true) && !instrumental

    /** `true` when an English translation is on the lyrics — on the
     *  synchronised lines or on the plain text. */
    val hasTranslation: Boolean
        get() {
            if (!plainTranslation.isNullOrEmpty()) return true
            return lines.any { !(it.translation ?: "").isEmpty() }
        }

    /**
     * The lyrics with an English translation applied to the
     * synchronised lines. A count mismatch is **refused** (the
     * alignment would shift and every line would show the wrong
     * words), so the lyrics come back unchanged.
     */
    fun applyingTranslation(translated: List<String>): Lyrics {
        if (!isSynced || lines.size != translated.size) return this
        val newLines = lines.mapIndexed { index, line ->
            line.copy(translation = translated[index])
        }
        return copy(lines = newLines)
    }

    /** The lyrics with an English translation applied to the plain
     *  text. An empty translation is refused. */
    fun applyingPlainTranslation(translated: String?): Lyrics {
        if (translated == null || translated.isEmpty()) return this
        return copy(plainTranslation = translated)
    }

    /**
     * The lyrics with an alphabet romanization applied to the
     * synchronised lines ("사랑해" → "salanghae"). A count mismatch is
     * refused, exactly like the translation.
     */
    fun applyingRomanization(romanized: List<String>): Lyrics {
        if (!isSynced || lines.size != romanized.size) return this
        val newLines = lines.mapIndexed { index, line ->
            line.copy(romanization = romanized[index])
        }
        return copy(lines = newLines)
    }

    /**
     * The index of the line that is playing at `time`, or null before
     * the first line starts.
     */
    fun activeLineIndex(at: Double): kotlin.Int? {
        if (lines.isEmpty()) return null
        // The last line whose timestamp is ≤ time.
        var low = 0
        var high = lines.size - 1
        var found: kotlin.Int? = null
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timestamp <= at) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** The line that is playing at `time`. */
    fun activeLine(at: Double): LyricLine? =
        activeLineIndex(at)?.let { lines[it] }

    /** The line after the one playing at `time` (the "coming up" line). */
    fun nextLine(after: Double): LyricLine? {
        val index = activeLineIndex(after) ?: return lines.firstOrNull()
        val next = index + 1
        return if (next < lines.size) lines[next] else null
    }

    /**
     * How far the current line has progressed (0.0..1.0) — used to
     * animate the highlight.
     */
    fun progress(at: Double): Double {
        val index = activeLineIndex(at) ?: return 0.0
        val start = lines[index].timestamp
        val end = if (index + 1 < lines.size) lines[index + 1].timestamp else start + 4
        val span = end - start
        if (span <= 0) return 0.0
        return min(1.0, max(0.0, (at - start) / span))
    }
}

// MARK: Parser

/** Parses the LRC format. */
object LRC {

    /** Parses an LRC document (or plain lyrics) into `Lyrics`. */
    fun parse(raw: String): Lyrics {
        val synced = mutableListOf<LyricLine>()
        val plainLines = mutableListOf<String>()
        var offsetSeconds = 0.0
        var trackID: Long? = null

        for (rawLine in raw.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val (timestamps, text) = split(line)
            if (timestamps.isEmpty()) {
                // No timestamp: either a metadata tag or a plain lyric
                // line.
                val tag = metadata(line)
                if (tag != null) {
                    when (tag.first.lowercase()) {
                        "offset" -> offsetSeconds = (tag.second.toDoubleOrNull() ?: 0.0) / 1000
                        "id" -> trackID = tag.second.toLongOrNull()
                    }
                } else {
                    plainLines.add(line)
                }
                continue
            }

            for (stamp in timestamps) {
                // The `[offset:]` tag is in milliseconds and shifts the
                // lyrics *earlier* for a positive value — the same
                // convention FFmpeg's LRC demuxer uses
                // (`sub->pts = ts_start - ts_offset`, `libavformat/lrcdec.c`).
                synced.add(LyricLine(timestamp = stamp - offsetSeconds, text = text))
            }
        }

        synced.sortBy { it.timestamp }

        if (synced.isNotEmpty()) {
            return Lyrics(lines = synced, trackID = trackID)
        }
        if (plainLines.isNotEmpty()) {
            return Lyrics(plainText = plainLines.joinToString("\n"), trackID = trackID)
        }
        return Lyrics(trackID = trackID)
    }

    /**
     * Splits a line into its timestamps and the text that follows them.
     * A line without any timestamp yields an empty timestamp list.
     */
    internal fun split(line: String): Pair<List<Double>, String> {
        val timestamps = mutableListOf<Double>()
        var index = 0

        while (index < line.length && line[index] == '[') {
            val close = line.indexOf(']', startIndex = index)
            if (close < 0) break
            val content = line.substring(index + 1, close)
            val stamp = timestamp(content) ?: break
            timestamps.add(stamp)
            index = close + 1
        }

        val text = line.substring(index).trim()
        return timestamps to text
    }

    /**
     * Parses one timestamp: `mm:ss`, `mm:ss.f`, `mm:ss.ff`, `mm:ss.fff`
     * or `mm:ss:ff`.
     */
    internal fun timestamp(content: String): Double? {
        // Swift splits with omittingEmptySubsequences: false — keep
        // empty trailing fields.
        val parts = content.split(":")
        if (parts.size != 2 && parts.size != 3) return null
        val minutes = parts[0].toDoubleOrNull() ?: return null
        val secondsValue = parts[1].toDoubleOrNull() ?: return null
        if (minutes < 0 || secondsValue < 0) return null

        var fraction = 0.0
        if (parts.size == 3) {
            // `mm:ss:ff` — the third field is centiseconds.
            val value = parts[2].toDoubleOrNull() ?: return null
            fraction = value / 100
        } else {
            val dot = parts[1].indexOf('.')
            if (dot >= 0) {
                val whole = parts[1].substring(0, dot)
                val decimals = parts[1].substring(dot + 1)
                val value = whole.toDoubleOrNull() ?: return null
                fraction = scaledFraction(decimals)
                return minutes * 60 + value + fraction
            }
        }
        return minutes * 60 + secondsValue + fraction
    }

    /**
     * Fractional seconds: one digit = tenths, two = hundredths, three
     * = thousandths.
     */
    private fun scaledFraction(digits: String): Double {
        val value = digits.toDoubleOrNull() ?: return 0.0
        return when (digits.length) {
            0 -> 0.0
            1 -> value / 10
            2 -> value / 100
            else -> value / 1000
        }
    }

    /**
     * A metadata tag (`[ar:Artist]`) as key/value pair, or null when the
     * bracketed text is not a tag.
     */
    internal fun metadata(line: String): Pair<String, String>? {
        if (!line.startsWith("[")) return null
        val close = line.indexOf(']')
        if (close < 0) return null
        val content = line.substring(1, close)
        val colon = content.indexOf(':')
        if (colon < 0) return null
        val key = content.substring(0, colon)
        if (key.isEmpty() || !key.all { it.isLetterOrDigit() }) return null
        val value = content.substring(colon + 1)
        return key to value
    }
}
