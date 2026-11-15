//
//  RadioBrowser.kt
//  pandoracore
//
//  Port of PandoraCore/RadioBrowser.swift — the community internet-radio
//  directory behind Shortwave (and many other players):
//  radio-browser.info. A free, keyless, GET-only JSON API served from a
//  set of mirrors. This client keeps the same shape as the Pandora side:
//  an injected transport (tests never touch the network), typed models,
//  and malformed entries skipped — never fatal.
//
//  Reference behavior (docs at https://api.radio-browser.info and
//  GNOME Shortwave's `api` crate):
//
//  - Mirrors: several independent servers exist; a client should move
//    to another when one fails. `/json/servers` lists them; we keep a
//    small known-good default list and rotate on failure.
//  - Search/discover endpoints all live under `/json/stations/…` and
//    accept `order`, `reverse`, `hidebroken`, `limit`.
//  - `url_resolved` is the stream URL actually resolved by the server
//    (follows redirects at directory time) — preferred for playback.
//  - Playing should ping `/json/url/{stationuuid}` so the directory's
//    click counter stays meaningful (Shortwave does this; best-effort).
//  - The API asks clients to identify themselves via User-Agent.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

// MARK: Playback bridge

/** Bridges the directory onto the player's Pandora-shaped `Track` —
 *  a live stream is a track with no duration and no rating. The
 *  `radio.` token prefix marks synthetic tracks so the app can guard
 *  lyrics, scrobbling, history and rating around them. */

/** True when this track was synthesized for a live radio stream —
 *  it has no Pandora song behind it. */
val Track.isRadio: Boolean
    get() = trackToken.startsWith("radio.")

/**
 * The synthetic live-stream track for one directory station:
 * title = station name, artist = its caption (country · codec ·
 * bitrate), artwork = the station's favicon, duration = 0 (live).
 * The stream URL rides as the single (highest-quality) variant —
 * that is where the player looks for what to open.
 */
fun radioTrack(station: RadioStation): Track = Track(
    trackToken = "radio.${station.uuid}",
    title = station.name,
    artist = station.caption,
    album = "",
    artworkUrl = station.faviconURL,
    duration = 0,
    stationId = station.uuid,
    variants = listOf(
        AudioVariant(
            quality = AudioVariant.Quality.highQuality,
            encoding = "radio",
            bitrate = station.bitrate,
            url = station.streamURL,
        ),
    ),
)

// MARK: Models

/**
 * One directory station. Built from a `/json/stations/…` entry;
 * entries missing a uuid, a name or a resolved stream URL are skipped
 * by the client (a directory row must be playable and showable).
 */
@Serializable
data class RadioStation(
    /** The directory's stable id (`stationuuid`). */
    val uuid: String,
    val name: String,
    /** The resolved stream URL (`url_resolved`) — what the player opens. */
    val streamURL: String,
    val homepageURL: String? = null,
    val faviconURL: String? = null,
    /** Lowercase, comma-separated free-form tags ("jazz", "smooth jazz"). */
    val tags: List<String> = emptyList(),
    val country: String = "",
    /** ISO 3166-1 alpha-2 code, uppercased; may be empty for uncategorized
     *  stations. */
    val countryCode: String = "",
    val language: String? = null,
    /** Stream codec ("MP3", "AAC", …) as reported by the directory. */
    val codec: String = "",
    /** Bitrate in kbps; 0 when the directory does not know. */
    val bitrate: kotlin.Int = 0,
    /** Community upvotes — the default ranking signal. */
    val votes: kotlin.Int = 0,
    /** Cumulative play clicks recorded by the directory. */
    val clicks: kotlin.Int = 0,
) {
    val id: String
        get() = uuid

    /**
     * A compact human caption ("Germany · MP3 · 128 kbps") for list rows.
     * Empty pieces are omitted; an entirely unknown station yields "".
     */
    val caption: String
        get() {
            val parts = mutableListOf<String>()
            if (country.isNotEmpty()) parts.add(country)
            if (codec.isNotEmpty()) parts.add(codec)
            if (bitrate > 0) parts.add("$bitrate kbps")
            return parts.joinToString(" · ")
        }

    companion object {
        /** Wire entry → station; null when the entry is unusable. */
        fun from(entry: Map<String, AnyJSON>): RadioStation? {
            val uuid = entry["stationuuid"]?.stringValue?.takeIf { it.isNotEmpty() }
                ?: return null
            val name = entry["name"]?.stringValue?.takeIf { it.isNotEmpty() }
                ?: return null
            // `url_resolved` falls back to `url` (the submitted form) — some
            // entries never got resolved by the server side. An *empty*
            // resolved URL must fall back too, not just a null one.
            val resolved = entry["url_resolved"]?.stringValue ?: ""
            val submitted = entry["url"]?.stringValue ?: ""
            val stream = resolved.ifEmpty { submitted }
            if (stream.isEmpty()) return null
            val tags = (entry["tags"]?.stringValue ?: "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return RadioStation(
                uuid = uuid,
                name = name,
                streamURL = stream,
                homepageURL = entry["homepage"]?.stringValue,
                faviconURL = entry["favicon"]?.stringValue,
                tags = tags,
                country = entry["country"]?.stringValue ?: "",
                countryCode = (entry["countrycode"]?.stringValue ?: "").uppercase(),
                language = entry["language"]?.stringValue,
                codec = entry["codec"]?.stringValue ?: "",
                bitrate = entry.intLike("bitrate") ?: 0,
                votes = entry.intLike("votes") ?: 0,
                clicks = entry.intLike("clicks") ?: 0,
            )
        }

        /** Decodes an array of directory entries, skipping malformed ones. */
        fun fromData(data: ByteArray): List<RadioStation> = try {
            kotlinx.serialization.json.Json.parseToJsonElement(data.decodeToString())
                .let { AnyJSON.fromJsonElement(it) }
                .arrayValue
                ?.mapNotNull { entry -> entry.objectValue?.let { from(it) } }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * One country in the directory (`/json/countries`), with how many
 * stations it carries — the discovery list's currency.
 */
@Serializable
data class RadioCountry(
    val name: String,
    /** ISO 3166-1 alpha-2 code used by the by-country filter. */
    val code: String,
    val stationCount: kotlin.Int,
) {
    val id: String
        get() = if (code.isEmpty()) name else code
}

/** One community tag (`/json/tags`) — Shortwave's genre discovery. */
@Serializable
data class RadioTag(
    val name: String,
    val stationCount: kotlin.Int,
) {
    val id: String
        get() = name
}

// MARK: Errors

/**
 * Typed failures of the directory client — mapped to short user copy
 * above the core, same as the Pandora error codes.
 */
sealed class RadioBrowserError : Exception() {
    data class HttpStatus(val statusCode: kotlin.Int) : RadioBrowserError()
    data object NetworkFailure : RadioBrowserError()

    /**
     * The body was not the expected JSON shape (never happens for
     * arrays of entries — those skip bad entries instead — only for
     * the ping/lookup endpoints).
     */
    data object BadResponse : RadioBrowserError()
}

// MARK: Transport

/**
 * GETs a URL and returns the raw body. Injected so tests never touch
 * the network (mirrors the Pandora side's `HttpTransport`).
 */
fun interface RadioBrowserTransport {
    suspend fun get(url: String): ByteArray
}

// MARK: Client

/**
 * The radio-browser directory client.
 *
 * The Swift actor becomes a Mutex-serialized class: mirror rotation is
 * shared state and must not race. On any failure the next mirror is
 * tried immediately within the same call; only when every mirror failed
 * does the last error surface.
 */
class RadioBrowserClient(
    private val transport: RadioBrowserTransport,
    mirrors: List<Mirror> = DEFAULT_MIRRORS,
) {
    /** One known directory mirror (base URL without trailing slash). */
    data class Mirror(val baseURL: String)

    /** The default mirrors — independent servers run by the
     *  radio-browser project. `all.api…` round-robins via DNS; the
     *  country mirrors are tried directly. */
    companion object {
        val DEFAULT_MIRRORS = listOf(
            Mirror(baseURL = "https://de1.api.radio-browser.info"),
            Mirror(baseURL = "https://de2.api.radio-browser.info"),
            Mirror(baseURL = "https://fi1.api.radio-browser.info"),
        )
    }

    private val mirrors: List<Mirror> = mirrors
    /** Where the rotation starts next — advances after every failure so
     *  a dead mirror is not retried first forever. */
    private var mirrorOffset: kotlin.Int = 0
    private val mutex = Mutex()

    // MARK: Discovery endpoints

    /**
     * Full-text search by station name (`/json/stations/search`).
     * Ranked by community votes, broken stations hidden. `offset`
     * pages through the ranking (the app's "Show More").
     */
    suspend fun searchStations(name: String, limit: kotlin.Int = 50, offset: kotlin.Int = 0): List<RadioStation> {
        val query = trimmed(name)
        if (query.isEmpty()) return emptyList()
        val items = mutableListOf(
            "name" to query,
            "order" to "votes",
            "reverse" to "true",
            "hidebroken" to "true",
            "limit" to limit.toString(),
        )
        if (offset > 0) items.add("offset" to offset.toString())
        return fetchStations(path = "/json/stations/search", query = items)
    }

    /** The directory's most upvoted stations (`/json/stations/topvote`). */
    suspend fun topStations(limit: kotlin.Int = 50, offset: kotlin.Int = 0): List<RadioStation> =
        fetchStations(
            path = "/json/stations/topvote/$limit",
            query = offsetQuery(offset),
        )

    /**
     * Stations of one country, by ISO code
     * (`/json/stations/bycountrycodeexact/{code}`).
     * Kotlin note: Swift allowed two `stations(_:)` overloads with the
     * same JVM erasure; the port names them by filter.
     */
    suspend fun stationsByCountryCode(countryCode: String, limit: kotlin.Int = 50, offset: kotlin.Int = 0): List<RadioStation> {
        val code = trimmed(countryCode).uppercase()
        if (code.isEmpty()) return emptyList()
        return fetchStations(
            path = "/json/stations/bycountrycodeexact/${percentEncoded(code)}",
            query = listQuery(limit = limit, offset = offset),
        )
    }

    /** Stations under one community tag (`/json/stations/bytag/{tag}`). */
    suspend fun stationsByTag(tag: String, limit: kotlin.Int = 50, offset: kotlin.Int = 0): List<RadioStation> {
        val tag = trimmed(tag)
        if (tag.isEmpty()) return emptyList()
        return fetchStations(
            path = "/json/stations/bytag/${percentEncoded(tag)}",
            query = listQuery(limit = limit, offset = offset),
        )
    }

    /** Countries with at least one station, largest first
     *  (`/json/countries`). */
    suspend fun countries(limit: kotlin.Int = 100): List<RadioCountry> {
        val data = fetch(
            path = "/json/countries",
            query = listOf(
                "order" to "stationcount",
                "reverse" to "true",
                "limit" to limit.toString(),
            ),
        )
        return try {
            AnyJSON.fromJsonElement(
                kotlinx.serialization.json.Json.parseToJsonElement(data.decodeToString()),
            ).arrayValue?.mapNotNull { entry ->
                val obj = entry.objectValue ?: return@mapNotNull null
                RadioCountry(
                    name = obj.stringField("name") ?: return@mapNotNull null,
                    code = (obj.stringField("iso_3166_1") ?: "").uppercase(),
                    stationCount = obj.intLikeField("stationcount") ?: 0,
                )
            } ?: throw RadioBrowserError.BadResponse
        } catch (error: RadioBrowserError) {
            throw error
        } catch (_: Exception) {
            throw RadioBrowserError.BadResponse
        }
    }

    /** Community tags, largest first (`/json/tags`). */
    suspend fun tags(limit: kotlin.Int = 100): List<RadioTag> {
        val data = fetch(
            path = "/json/tags",
            query = listOf(
                "order" to "stationcount",
                "reverse" to "true",
                "limit" to limit.toString(),
            ),
        )
        return try {
            AnyJSON.fromJsonElement(
                kotlinx.serialization.json.Json.parseToJsonElement(data.decodeToString()),
            ).arrayValue?.mapNotNull { entry ->
                val obj = entry.objectValue ?: return@mapNotNull null
                RadioTag(
                    name = obj.stringField("name") ?: return@mapNotNull null,
                    stationCount = obj.intLikeField("stationcount") ?: 0,
                )
            } ?: throw RadioBrowserError.BadResponse
        } catch (error: RadioBrowserError) {
            throw error
        } catch (_: Exception) {
            throw RadioBrowserError.BadResponse
        }
    }

    /**
     * Registers one play click (`/json/url/{uuid}`). Fire-and-forget on
     * the caller's side — a failure here must never disturb playback.
     */
    suspend fun registerClick(uuid: String) {
        if (trimmed(uuid).isEmpty()) return
        try {
            fetch(path = "/json/url/${percentEncoded(uuid)}", query = emptyList())
        } catch (_: Exception) {
            // best-effort
        }
    }

    // MARK: Internals

    private fun Map<String, AnyJSON>.stringField(key: String): String? =
        (this[key] as? AnyJSON.Str)?.value

    private fun Map<String, AnyJSON>.intLikeField(key: String): kotlin.Int? {
        val value = this[key] ?: return null
        value.intValue?.let { return it.toInt() }
        value.stringValue?.let { return it.toIntOrNull() }
        return null
    }

    /** The shared list ordering: most popular first, broken hidden.
     *  `offset` pages past the first window (0 = the default, omitted
     *  from the URL so the first page looks exactly as before). */
    private fun listQuery(limit: kotlin.Int, offset: kotlin.Int): List<Pair<String, String>> =
        listOf(
            "order" to "votes",
            "reverse" to "true",
            "hidebroken" to "true",
            "limit" to limit.toString(),
        ) + offsetQuery(offset)

    private fun offsetQuery(offset: kotlin.Int): List<Pair<String, String>> =
        if (offset > 0) listOf("offset" to offset.toString()) else emptyList()

    private suspend fun fetchStations(path: String, query: List<Pair<String, String>>): List<RadioStation> =
        RadioStation.fromData(fetch(path = path, query = query))

    /**
     * One GET against the current mirror; on failure the next mirror is
     * tried immediately. The rotation remembers only *failures*: after
     * a mirror fails, subsequent calls start past it — a mirror that
     * answered stays first until it stops answering.
     */
    private suspend fun fetch(path: String, query: List<Pair<String, String>>): ByteArray =
        mutex.withLock {
            if (mirrors.isEmpty()) throw RadioBrowserError.NetworkFailure
            val start = mirrorOffset
            var lastError: Exception = RadioBrowserError.NetworkFailure
            for (attempt in 0 until mirrors.size) {
                val index = (start + attempt) % mirrors.size
                val base = mirrors[index].baseURL
                val url = base + path + queryString(query)
                    ?: throw RadioBrowserError.NetworkFailure
                try {
                    return@withLock transport.get(url)
                } catch (error: Exception) {
                    lastError = error
                    mirrorOffset = (index + 1) % mirrors.size
                }
            }
            throw lastError
        }

    private fun queryString(query: List<Pair<String, String>>): String =
        if (query.isEmpty()) "" else "?" + query.joinToString("&") { (name, value) ->
            "${LRCLibClient.queryEncode(name)}=${LRCLibClient.queryEncode(value)}"
        }

    private fun trimmed(value: String): String = value.trim()

    private fun percentEncoded(value: String): String = buildString {
        // Swift encodes with .urlPathAllowed; the port uses the same
        // RFC 3986 path-safe set.
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c in "!$&'()*+,-./:=@_~" -> append(c)
                else -> append(String.format("%%%02X", byte.toInt() and 0xFF))
            }
        }
    }
}
