//
//  RadioStreamMetadata.kt
//  pandoracore
//
//  Port of PandoraCore/RadioStreamMetadata.swift — the now-playing
//  metadata that internet-radio streams carry — the ICY protocol
//  (Shoutcast / Icecast). Verified live against real stations while
//  building this: Europe 1 and MANGORADIO both answer the stream request
//  with an `icy-metaint` header and interleave a `StreamTitle` into the
//  audio.
//
//  How it works — the client asks for the metadata (`Icy-MetaData: 1`)
//  and the server answers with `icy-metaint: N`: every N bytes of
//  audio, one length byte follows, and the metadata block after it is
//  `length × 16` bytes long:
//
//      <N bytes audio> <length byte> <length × 16 bytes metadata> …
//
//  The length byte counts *blocks of 16 bytes* (0 means "no metadata
//  this time"), and the block itself is a list of `key='value';` pairs
//  padded with nulls:
//
//      StreamTitle='Céline Dion - Tous les chemins mènent à l'amour';StreamUrl='';
//
//  This file is the pure part: the byte state machine, the header
//  lookup and the "Artist - Title" split. The transport (the OkHttp
//  byte stream) belongs to the app target, so this stays portable and
//  testable — the whole suite runs on the JVM.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

// MARK: The now-playing song

/** The song a radio station is currently announcing. */
data class RadioNowPlaying(
    /** The station's own `StreamTitle` string, verbatim
     *  ("Artist - Title"). */
    val rawTitle: String,
    /** The artist, when the announcement could be split into one. */
    val artist: String?,
    /** The song title (the `StreamTitle` without its artist part). */
    val title: String,
) {
    /** The query a lyrics or an artwork lookup would use. */
    val lookupQuery: String
        get() = if (artist.isNullOrEmpty()) title else "$artist $title"
}

// MARK: Reading the metadata

/**
 * Reads the metadata blocks out of a live stream's byte flow.
 *
 * The parser is fed whatever bytes arrive — in whatever sizes they
 * arrive — and hands back the metadata blocks it completed. It never
 * buffers more than one metadata block, and it never looks at the
 * audio itself.
 */
class ICYMetadataParser {

    /** Where the parser is in the stream. */
    sealed class State {
        /** No interval yet — the response header has not arrived, so
         *  there is nothing to look for in the bytes. */
        data object Idle : State()

        /** Reading audio bytes; `remaining` of them before the next
         *  metadata block. */
        data class Audio(val remaining: kotlin.Int) : State()

        /** Reading the one-byte metadata length. */
        data object Length : State()

        /** Reading the metadata block; `remaining` bytes of it. */
        data class Metadata(val remaining: kotlin.Int) : State()
    }

    /** Where the parser currently is. */
    var state: State = State.Idle
        private set

    /** The metadata interval in bytes (the `icy-metaint` header). */
    var interval: kotlin.Int = 0
        private set

    /** `true` when the stream announced a metadata interval — a
     *  station that announces nothing can never be identified. */
    val isSupported: Boolean
        get() = interval > 0

    /** The bytes of the metadata block being read. */
    private val blockBuffer = mutableListOf<Byte>()

    /**
     * Sets the metadata interval from the response's `icy-metaint`
     * header. A non-positive value means the stream announces
     * nothing, and the parser goes idle.
     */
    fun setMetadataInterval(bytes: kotlin.Int) {
        interval = maxOf(0, bytes)
        blockBuffer.clear()
        state = if (interval > 0) State.Audio(remaining = interval) else State.Idle
    }

    /**
     * Feeds bytes from the stream and returns the metadata blocks they
     * completed — the raw `key='value';` text, with the null padding
     * trimmed. Blocks that carry no text (the server says "nothing
     * changed") are not returned.
     */
    fun consume(data: ByteArray): List<String> {
        val blocks = mutableListOf<String>()
        var index = 0
        while (index < data.size) {
            when (val current = state) {
                is State.Idle -> {
                    // No interval yet — the bytes are just audio.
                    index = data.size
                }
                is State.Audio -> {
                    val available = data.size - index
                    val take = minOf(available, current.remaining)
                    index += take
                    if (take == current.remaining) {
                        state = State.Length
                    } else {
                        // Part of the audio is still to come — the counter
                        // has to follow it down.
                        state = State.Audio(remaining = current.remaining - take)
                    }
                }
                is State.Length -> {
                    val length = (data[index].toInt() and 0xFF) * 16
                    index += 1
                    state = if (length > 0) {
                        State.Metadata(remaining = length)
                    } else {
                        State.Audio(remaining = interval)
                    }
                }
                is State.Metadata -> {
                    val available = data.size - index
                    val take = minOf(available, current.remaining)
                    for (i in 0 until take) blockBuffer.add(data[index + i])
                    index += take
                    if (take == current.remaining) {
                        val block = blockBuffer.toByteArray().decodeToString()
                        blockBuffer.clear()
                        state = State.Audio(remaining = interval)
                        // The block is padded with nulls; the text itself
                        // never contains one.
                        val trimmed = block.trim { it.isISOControl() || it.isWhitespace() }
                        if (trimmed.isNotEmpty()) blocks.add(trimmed)
                    } else {
                        state = State.Metadata(remaining = current.remaining - take)
                    }
                }
            }
        }
        return blocks
    }

    companion object {
        /**
         * The metadata interval from a response's headers — the
         * `icy-metaint` header, looked up case-insensitively (servers
         * spell it `icy-metaint`, `Icy-MetaInt`, `ICY-METAINT`…).
         */
        fun metadataInterval(headers: Map<String, String>): kotlin.Int? {
            for ((key, value) in headers) {
                if (key.lowercase() != "icy-metaint") continue
                val trimmed = value.trim()
                val interval = trimmed.toIntOrNull() ?: return null
                if (interval <= 0) return null
                return interval
            }
            return null
        }
    }
}

// MARK: The announcement

/** The pieces of a station's now-playing announcement. */
object RadioStreamMetadata {

    /**
     * The `StreamTitle` in a metadata block, if the block carries one.
     *
     * The value ends at the first `';` — the separator between the
     * metadata keys. (An apostrophe inside a title is harmless: it is
     * only the quote *and* the semicolon that end the value.)
     */
    fun streamTitle(block: String): String? {
        val key = "streamtitle='"
        val keyRange = block.indexOf(key, ignoreCase = true)
        if (keyRange < 0) return null
        val rest = block.substring(keyRange + key.length)
        val end = rest.indexOf("';")
        return if (end >= 0) {
            val value = rest.substring(0, end).trim()
            value.ifEmpty { null }
        } else {
            // No closing separator — the value runs to the end of the block.
            rest.trim().ifEmpty { null }
        }
    }

    /**
     * Splits a `StreamTitle` into the artist and the song title.
     *
     * The convention (Shoutcast, Icecast, and what radio apps show)
     * is "Artist - Title". Some stations announce only a title, so the
     * artist is optional. The separator is the *spaced* " - ": a bare
     * hyphen is part of a title far more often than a separator.
     */
    fun nowPlayingFromStreamTitle(raw: String): RadioNowPlaying? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val separator = trimmed.indexOf(" - ")
        if (separator >= 0) {
            val artist = trimmed.substring(0, separator).trim()
            val title = trimmed.substring(separator + 3).trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) {
                return RadioNowPlaying(rawTitle = trimmed, artist = artist, title = title)
            }
        }
        return RadioNowPlaying(rawTitle = trimmed, artist = null, title = trimmed)
    }

    /** The now-playing song a metadata block announces, if any. */
    fun nowPlaying(block: String): RadioNowPlaying? {
        val title = streamTitle(block) ?: return null
        return nowPlayingFromStreamTitle(title)
    }
}
