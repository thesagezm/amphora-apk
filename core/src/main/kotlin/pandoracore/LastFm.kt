//
//  LastFm.kt
//  pandoracore
//
//  Port of PandoraCore/LastFm.swift — Last.fm scrobbling client
//  (AudioScrobbler 2.0 web services). Pure logic — no UI, no audio —
//  so every port can reuse it.
//
//  Protocol, verified 2026-10-24 against the Last.fm API documentation:
//
//  - Endpoint: POST https://ws.audioscrobbler.com/2.0/ with
//    `application/x-www-form-urlencoded` parameters, `format=json`.
//  - Signing (last.fm/api/authspec): all parameters except `format` and
//    `callback`, ordered alphabetically by name, concatenated as
//    <name><value>, the API secret appended, then MD5 — a 32-character
//    lowercase hexadecimal hash.
//  - `auth.getMobileSession`: `username` + `password` (plain text; the
//    `authToken = md5(username + md5(password))` variant is the
//    deprecated one), POST over HTTPS, no session needed. Reply
//    `{"session": {"name", "key", "subscriber"}}`. The API key/secret
//    pair is Pithos's public pair (`plugins/lastfm.py:25`), the same
//    class of public client identity as Pandora's partner credentials.
//  - `track.updateNowPlaying`: required `artist`, `track`, `sk`;
//    optional `album`, `duration`, `albumArtist`.
//  - `track.scrobble`: required `artist`, `track`, `timestamp` (UNIX
//    seconds — when the track *started* playing), `sk`; optional
//    `album`, `duration`, `albumArtist`. Single scrobbles may omit the
//    `artist[0]`-style index (0 ≤ i ≤ 49 for batches; Amphora sends one
//    at a time).
//  - Errors (JSON): `{"error": <code>, "message": "…"}` — 4 auth
//    failed, 9 invalid session key, 10 invalid API key, 13 invalid
//    signature, 29 rate limit exceeded.
//  - Scrobble rule (pithos `plugins/lastfm.py` `_on_song_ended`):
//    scrobble when the track is not an ad, is longer than 30 s, and was
//    played past 240 s or past its midpoint —
//    `not is_ad and duration > 30 and (position > 240 or position >
//    duration / 2)`. `ScrobblePolicy` implements exactly that.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

import java.security.MessageDigest
import kotlinx.serialization.Serializable

// MARK: Models

/**
 * An established Last.fm session (`session.name` / `key` / `subscriber`).
 * Serializable so the app can persist the key in the Keystore as-is.
 */
@Serializable
data class LastFmSession(
    val username: String,
    val key: String,
    val isSubscriber: Boolean,
)

/** Port of `LastFmError`. */
sealed class LastFmError : Exception() {
    /** Error 4 — wrong username/password (auth.getMobileSession). */
    data object InvalidCredentials : LastFmError()

    /** Error 9 — the session key was rejected; the user must re-authenticate. */
    data object InvalidSessionKey : LastFmError()

    /** Error 10. */
    data object InvalidApiKey : LastFmError()

    /** Error 13. */
    data object InvalidSignature : LastFmError()

    /** Error 29 — back off; never retry in a tight loop. */
    data object RateLimited : LastFmError()

    /** Any other `{"error": code, "message": …}` reply. */
    data class Rejected(val code: kotlin.Int, val rejectedMessage: String) : LastFmError()

    /** A non-2xx reply whose body carried no Last.fm error object. */
    data class HttpStatus(val statusCode: kotlin.Int) : LastFmError()

    /** A 2xx reply whose body was not parseable JSON. */
    data object Malformed : LastFmError()

    /** The request never left (network failure). */
    data class Transport(val detail: String) : LastFmError()
}

// MARK: Signing

object LastFmSigning {

    /**
     * The AudioScrobbler request signature: parameters sorted by name
     * (ASCII order), concatenated `<name><value>`, the secret appended,
     * MD5 as lowercase hex. `format` and `callback` are excluded.
     */
    fun signature(parameters: Map<String, String>, secret: String): String {
        val signed = parameters
            .filter { it.key != "format" && it.key != "callback" }
            .toSortedMap()
            .map { it.key + it.value }
            .joinToString("")
        return hexMD5(signed + secret)
    }

    internal fun hexMD5(string: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(string.toByteArray(Charsets.UTF_8))
        // Lowercase hex, byte at a time.
        return digest.joinToString("") { String.format("%02x", it) }
    }
}

// MARK: Scrobble policy

object ScrobblePolicy {

    /**
     * Pithos's scrobble decision (`plugins/lastfm.py`): the track is not
     * an advertisement, is longer than 30 seconds, and was played past
     * 240 seconds or past its midpoint. `position` is how far into the
     * track playback reached when it ended (natural end or skip).
     */
    fun shouldScrobble(
        duration: Double,
        position: Double,
        isAdvertisement: Boolean,
    ): Boolean =
        !isAdvertisement &&
            duration > 30 &&
            (position > 240 || position > duration / 2)
}

// MARK: Transport

/**
 * A form-POST reply, *including* the status code (some Last.fm failures
 * arrive as non-2xx without a JSON error body).
 */
data class LastFmHTTPResponse(
    val statusCode: kotlin.Int,
    val body: ByteArray,
)

fun interface LastFmTransport {
    suspend fun post(url: String, form: ByteArray, headers: Map<String, String>): LastFmHTTPResponse
}

// MARK: Client

/**
 * The Last.fm web-services client. Stateless; the session key is passed
 * per call so storage/re-auth stays with the owner.
 */
class LastFmClient(
    private val transport: LastFmTransport,
    private val userAgent: String = "Amphora",
) {

    /** `auth.getMobileSession` — exchanges Last.fm credentials for a
     *  session key. POST over HTTPS per the docs. */
    suspend fun authenticate(username: String, password: String): LastFmSession {
        val parameters = mutableMapOf(
            "method" to "auth.getMobileSession",
            "api_key" to API_KEY,
            "username" to username,
            "password" to password,
        )
        parameters["api_sig"] = LastFmSigning.signature(parameters, API_SECRET)
        val body = post(parameters)
        val objectValue = parseObject(body) ?: throw LastFmError.Malformed
        errorFrom(objectValue)?.let { throw it }
        val session = objectValue["session"]?.objectValue
        val key = session?.get("key")?.stringValue
        if (session == null || key.isNullOrEmpty()) {
            throw LastFmError.Malformed
        }
        return LastFmSession(
            username = session["name"]?.stringValue ?: username,
            key = key,
            isSubscriber = session["subscriber"]?.intValue == 1L,
        )
    }

    /**
     * `track.updateNowPlaying` — updates the user's Last.fm profile page
     * while the track plays. Fire-and-forget semantics are the caller's
     * concern; the call itself reports failures.
     */
    suspend fun updateNowPlaying(
        sessionKey: String,
        artist: String,
        track: String,
        album: String? = null,
        duration: Double? = null,
    ) {
        val parameters = mutableMapOf(
            "method" to "track.updateNowPlaying",
            "api_key" to API_KEY,
            "sk" to sessionKey,
            "artist" to artist,
            "track" to track,
        )
        if (!album.isNullOrEmpty()) parameters["album"] = album
        if (duration != null && duration > 0) parameters["duration"] = duration.toLong().toString()
        parameters["api_sig"] = LastFmSigning.signature(parameters, API_SECRET)
        val body = post(parameters)
        val objectValue = parseObject(body) ?: throw LastFmError.Malformed
        errorFrom(objectValue)?.let { throw it }
        if (objectValue["nowplaying"] == null) throw LastFmError.Malformed
    }

    /**
     * `track.scrobble` — one scrobble. `timestamp` is UNIX seconds for
     * when the track started playing (not when it ended). Single
     * scrobbles omit the `artist[0]` bracket notation (documented).
     */
    suspend fun scrobble(
        sessionKey: String,
        artist: String,
        track: String,
        album: String? = null,
        timestamp: Long,
        duration: Double? = null,
    ) {
        val parameters = mutableMapOf(
            "method" to "track.scrobble",
            "api_key" to API_KEY,
            "sk" to sessionKey,
            "artist" to artist,
            "track" to track,
            "timestamp" to timestamp.toString(),
        )
        if (!album.isNullOrEmpty()) parameters["album"] = album
        if (duration != null && duration > 0) parameters["duration"] = duration.toLong().toString()
        parameters["api_sig"] = LastFmSigning.signature(parameters, API_SECRET)
        val body = post(parameters)
        val objectValue = parseObject(body) ?: throw LastFmError.Malformed
        errorFrom(objectValue)?.let { throw it }
        if (objectValue["scrobbles"] == null) throw LastFmError.Malformed
    }

    // MARK: Internals (test seams)

    internal suspend fun post(parameters: Map<String, String>): ByteArray {
        // Last.fm replies in **XML** unless the request explicitly asks
        // for JSON — and this client only decodes JSON. The parameter
        // must ride on every call; it is excluded from the signature
        // (authspec), so adding it here does not disturb `api_sig`.
        // Verified live 2026-11-07: the same signed request returns
        // `<lfm …>` XML without it and JSON with it.
        val withFormat = parameters + ("format" to "json")
        val form = percentEncodedForm(withFormat)
        val response: LastFmHTTPResponse = try {
            transport.post(
                ENDPOINT,
                form.toByteArray(Charsets.UTF_8),
                mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to userAgent,
                ),
            )
        } catch (error: LastFmError) {
            throw error
        } catch (error: Exception) {
            throw LastFmError.Transport("network failure")
        }
        // Parse the body first: failures usually arrive as HTTP 200 with
        // a JSON error object; non-2xx without one is its own failure.
        try {
            parseObject(response.body)?.let { objectValue ->
                errorFrom(objectValue)?.let { throw it }
            }
        } catch (error: LastFmError.Malformed) {
            // A non-JSON body on a 2xx is malformed at the call site; on
            // a non-2xx it falls through to the status check (Swift used
            // `try?` here and matched the status afterward).
            if (response.statusCode in 200..299) throw error
        }
        if (response.statusCode !in 200..299) {
            throw LastFmError.HttpStatus(response.statusCode)
        }
        return response.body
    }

    private fun parseObject(data: ByteArray): Map<String, AnyJSON>? {
        if (data.isEmpty()) return null
        return try {
            AnyJSON.fromJsonElement(
                kotlinx.serialization.json.Json.parseToJsonElement(data.decodeToString()),
            ).objectValue
        } catch (error: LastFmError) {
            throw error
        } catch (_: Exception) {
            throw LastFmError.Malformed
        }
    }

    companion object {
        const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"

        /** Pithos's public API pair (`pithos/plugins/lastfm.py:25-26`) — a
         *  public client identity, not a user credential. */
        const val API_KEY = "997f635176130d5d6fe3a7387de601a8"
        const val API_SECRET = "3243b876f6bf880b923a3c9fb955720c"

        /**
         * Maps `{"error": code, "message": …}` to a typed error; `code`
         * tolerates number-or-string.
         */
        fun errorFrom(objectValue: Map<String, AnyJSON>): LastFmError? {
            val errorField = objectValue["error"] ?: return null
            if (errorField == AnyJSON.Null) return null
            val code = errorField.intValue?.toInt()
                ?: errorField.stringValue?.toIntOrNull()
                ?: 0
            val message = objectValue["message"]?.stringValue ?: ""
            return when (code) {
                4 -> LastFmError.InvalidCredentials
                9 -> LastFmError.InvalidSessionKey
                10 -> LastFmError.InvalidApiKey
                13 -> LastFmError.InvalidSignature
                29 -> LastFmError.RateLimited
                else -> LastFmError.Rejected(code, message)
            }
        }

        /**
         * `application/x-www-form-urlencoded` with strict percent-encoding
         * (RFC 3986 unreserved only — `&`, `+`, `=` inside values must never
         * survive; spaces become %20).
         */
        fun percentEncodedForm(parameters: Map<String, String>): String {
            fun encode(value: String): String = buildString {
                for (byte in value.toByteArray(Charsets.UTF_8)) {
                    val c = (byte.toInt() and 0xFF).toChar()
                    when {
                        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                            c == '-' || c == '.' || c == '_' || c == '~' -> append(c)
                        else -> append(String.format("%%%02X", byte.toInt() and 0xFF))
                    }
                }
            }
            return parameters
                .toSortedMap()
                .map { (k, v) -> "${encode(k)}=${encode(v)}" }
                .joinToString("&")
        }
    }
}
