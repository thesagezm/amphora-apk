//
//  Errors.kt
//  pandoracore
//
//  Port of PandoraCore/Errors.swift — typed errors mapped from Pandora's
//  fault codes. The code table and the human-facing messages are
//  transcribed from `pithos.pandora.ApiError` (`pithos/pandora/pandora.py`),
//  the reference implementation.
//
//  Never crash on an API error; everything the server can say lands here.
//
//  Divergence from iOS: `PandoraTransportError(urlError: URLError)` has no
//  JVM equivalent (URLError is Apple-only). The reason-string constructor
//  and the user-facing copy are ported verbatim; socket-cause
//  classification (timeout / unreachable / network failure) happens in the
//  app-side OkHttp adapter, which maps IOException kinds onto the same
//  reason strings before raising this error.
//
//  Kotlin note: `message` on each error carries the RAW server/transport
//  string (what the log keeps); the user-facing copy is the separate
//  `localizedDescription` property — matching the Swift split.
//

package pandoracore

/**
 * A fault returned in a `{"stat":"fail"}` response envelope.
 */
class PandoraAPIError(
    /** Pandora's numeric fault code (e.g. 1001 invalid auth token). */
    val code: kotlin.Int,
    /** The server's message string, verbatim. */
    override val message: String,
) : Exception(message) {

    // MARK: Fault codes (pithos `ApiError`)

    @Suppress("EnumEntryName")
    enum class Code(val raw: kotlin.Int) {
        internalError(0),
        maintenanceMode(1),
        urlParamMissingMethod(2),
        urlParamMissingAuthToken(3),
        urlParamMissingPartnerId(4),
        urlParamMissingUserId(5),
        secureProtocolRequired(6),
        certificateRequired(7),
        parameterTypeMismatch(8),
        parameterMissing(9),
        parameterValueInvalid(10),
        apiVersionNotSupported(11),
        countryNotSupported(12),
        insufficientConnectivity(13),
        unknownMethodName(14),
        wrongProtocol(15),

        readOnlyMode(1000),
        invalidAuthToken(1001),
        invalidLogin(1002),
        listenerNotAuthorized(1003),
        userNotAuthorized(1004),
        maxStationsReached(1005),
        stationDoesNotExist(1006),
        complimentaryPeriodAlreadyInUse(1007),
        callNotAllowed(1008),
        deviceNotFound(1009),
        partnerNotAuthorized(1010),
        invalidUsername(1011),
        invalidPassword(1012),
        usernameAlreadyExists(1013),
        deviceAlreadyAssociatedToAccount(1014),
        upgradeDeviceModelInvalid(1015),
        explicitPinIncorrect(1018),
        explicitPinMalformed(1020),
        deviceModelInvalid(1023),
        zipCodeInvalid(1024),
        birthYearInvalid(1025),
        birthYearTooYoung(1026),
        deviceDisabled(1034),
        dailyTrialLimitReached(1035),
        invalidSponsor(1036),
        userAlreadyUsedTrial(1037),
        playlistExceeded(1039),
        ;

        companion object {
            private val byRaw = entries.associateBy { it.raw }
            fun from(raw: kotlin.Int): Code? = byRaw[raw]
        }
    }

    /** `true` when the session token must be refreshed (re-login + retry). */
    val isSessionExpired: Boolean
        get() = code == Code.invalidAuthToken.raw

    /**
     * Pandora's fault message when a station's playlist simply ran out
     * (Elpis `Libs/PandoraSharp/Station.cs:232` compares the exception
     * message against this literal).
     */
    companion object {
        const val playlistEndMessage = "PLAYLIST_END"

        /**
         * Pandora's fault message when the daily skip limit was reached and
         * an explicit skip was refused (same reference).
         */
        const val dailySkipLimitMessage = "DAILY_SKIP_LIMIT_REACHED"

        /**
         * Curated copy lifted from pithos (`ApiError.sub_message`), adapted
         * for an Amphora user (no Pithos-specific references).
         */
        private val friendlyMessages: Map<Code, String> = mapOf(
            Code.maintenanceMode to "Pandora is performing maintenance. Try again later.",
            Code.countryNotSupported to "Pandora is not available in your country.",
            Code.insufficientConnectivity to "Out of sync. Check your system's clock.",
            Code.readOnlyMode to "Pandora is in read-only mode. Try again later.",
            Code.invalidLogin to "Invalid username or password.",
            Code.listenerNotAuthorized to "A Pandora One subscription is required for this feature.",
            Code.maxStationsReached to "You have reached the maximum number of stations.",
            Code.partnerNotAuthorized to "Invalid Pandora partner keys — the client may need updating.",
            Code.deviceModelInvalid to "Invalid Pandora device model — the client may need updating.",
            Code.playlistExceeded to "Too many playlist requests. Try again later.",
        )
    }

    /**
     * `true` when the fault means Pandora's daily skip limit stopped
     * the playlist.
     *
     * The legacy API signals this by **message**, not by a numeric
     * fault code — the code table has no entry for it, which is
     * why it is matched on the server's text. The comparison is
     * case-insensitive and tolerates surrounding whitespace; a message
     * that merely contains the token counts too (the server may append
     * a sentence).
     */
    val isSkipLimitReached: Boolean
        get() {
            val text = message.trim().uppercase()
            return text.contains(playlistEndMessage) || text.contains(dailySkipLimitMessage)
        }

    /**
     * `true` when the fault is the playlist running out
     * (`PLAYLIST_END`) rather than an explicit skip being refused
     * (`DAILY_SKIP_LIMIT_REACHED`). Elpis treats the playlist-end fault
     * as "skip limit reached" too — both stop the stream of tracks.
     */
    val isPlaylistEnd: Boolean
        get() = message.trim().uppercase().contains(playlistEndMessage)

    /** `true` when credentials were rejected — do not retry automatically. */
    val isInvalidCredentials: Boolean
        get() = code == Code.invalidLogin.raw ||
            code == Code.invalidUsername.raw ||
            code == Code.invalidPassword.raw

    /**
     * Human-facing summary, preferring pithos' curated copy over the raw
     * server message (pithos `ApiError.sub_message` / `.title`).
     */
    val localizedDescription: String
        get() {
            val known = Code.from(code) ?: return "Pandora error $code: $message"
            return friendlyMessages[known]
                ?: "Pandora error $code (${known.name}): $message"
        }
}

/**
 * Transport-level failure (offline, DNS, TLS, timeout).
 *
 * The `reason` strings the transports produce ("unreachable", "timeout",
 * "network failure", "HTTP 502", "non-HTTP response") must each map to
 * words a user can act on, and nothing else may leak into the UI.
 */
class PandoraTransportError(
    /** What went wrong, as the transport itself describes it. */
    val reason: String,
) : Exception(reason) {

    /**
     * What went wrong, in words a user can act on — the transport-error
     * counterpart of `PandoraAPIError.localizedDescription`. The raw
     * `reason` stays what the log keeps; the UI must never show it.
     */
    val localizedDescription: String
        get() = when (reason) {
            "unreachable" -> "No internet connection. Amphora will try again."
            "timeout" -> "Pandora took too long to respond. Check your connection and try again."
            "network failure" -> "Couldn't reach Pandora. Check your connection and try again."
            "non-HTTP response" -> "Pandora sent an unexpected response. Try again."
            else -> httpStatusCode
                ?.let { "Pandora's servers had a problem (HTTP $it). Try again." }
                ?: "Something went wrong talking to Pandora. Try again."
        }

    /**
     * The `HTTP <status>` reason, when that is what failed — a 502 from
     * a CDN is a server problem, not the user's connection.
     */
    val httpStatusCode: kotlin.Int?
        get() {
            if (!reason.startsWith("HTTP ")) return null
            return reason.drop("HTTP ".length).trim().toIntOrNull()
        }
}

/** The response body was not decodable JSON, or had no `stat`. */
class PandoraMalformedResponseError(
    val detail: String,
) : Exception(detail)

/**
 * A playlist request Pandora refused for a station-specific reason.
 */
sealed class PlaylistError : Exception() {

    /**
     * Pandora's daily skip limit was reached for the station: the
     * server stops handing out tracks until the limit resets.
     *
     * This is **not** a transport failure and must not be retried in a
     * loop — AGENTS.md: "Respect skip limits and rate limits. Never
     * hammer the API."
     *
     * [playlistEnded] tells the two server signals apart:
     * `PLAYLIST_END` (the playlist ran out) and
     * `DAILY_SKIP_LIMIT_REACHED` (an explicit skip was refused).
     */
    class SkipLimitReached(val playlistEnded: Boolean) : PlaylistError()
}
