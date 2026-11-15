//
//  StationModes.kt
//  pandoracore
//
//  Port of PandoraCore/StationModes.swift — Pandora "listening modes" —
//  the station-tuning feature ("My Mix", "Deep Cuts", "Crowd Faves",
//  "Discovery", …) that Pandora's own apps expose as "Tune your station".
//
//  These live on the *modern* web API, not the legacy JSON v5 host, and
//  the request bodies are plain JSON (no Blowfish):
//
//    POST https://www.pandora.com/api/v1/interactiveradio/getAvailableModesSimple
//         {"stationId": "<stationId>"}
//    POST https://www.pandora.com/api/v1/interactiveradio/setAndGetAvailableModes
//         {"stationId": "<stationId>", "modeId": <int>, "previousModeId": <int>}
//
//  Auth is the header `X-AuthToken: <userAuthToken>` — the *same* token the
//  legacy `auth.userLogin` returns. Verified against two independent
//  clients (pandoCast's legacy login → modern interactiveradio, and
//  Panedora's web session) — see the iOS file for citations.
//
//  CSRF: the endpoints require `X-CsrfToken`. pandoCast bootstraps it by
//  requesting `https://www.pandora.com/` and reading the `csrftoken`
//  cookie (HEAD, then GET), retrying once with a fresh cookie on 401/403.
//
//  Free accounts: every mode the server marks available is usable —
//  "Deep Cuts" and friends are free features. Modes flagged
//  `isPremiumOnly` (e.g. id 5, "Artist Only") need a paid subscription;
//  both references gate them client-side, which is what
//  `StationMode.isSelectable(isSubscriber)` implements.
//
//  No Android, no UI — pandoracore stays portable.
//
//  Divergence from iOS: `CookieCSRFProvider` reads URLSession's shared
//  `HTTPCookieStorage` (Apple-only). The Kotlin port keeps the
//  `CSRFTokenProviding` seam here; the cookie-jar bootstrap (HEAD/GET
//  www.pandora.com, read the csrftoken cookie, cache, refresh) is the
//  app-side OkHttp adapter's job (A2), where the cookie jar lives.
//

package pandoracore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// MARK: Models

/** One listening mode from `availableModes`. */
data class StationMode(
    /**
     * `modeId` — the value `setAndGetAvailableModes` accepts. 5 is
     * "Artist Only" (premium) when the server omits the flag.
     */
    val id: kotlin.Int,
    /** `modeName`, e.g. "My Mix" / "Deep Cuts". */
    val name: String,
    /** `modeDescription` — the server's one-line blurb for the mode. */
    val modeDescription: String = "",
    /** `isModeAvailable` — whether Pandora offers this mode for the station. */
    val isAvailable: Boolean = false,
    /** `isPremiumOnly` — requires a paid subscription. */
    val isPremiumOnly: Boolean = false,
) {
    /**
     * Whether the mode may be requested under this subscription
     * (panedora: `isModeAvailable && (!premiumOnly || premiumAccess)`).
     */
    fun isSelectable(isSubscriber: Boolean): Boolean =
        isAvailable && (!isPremiumOnly || isSubscriber)
}

/**
 * The `interactiveradio` reply: the station's offered modes plus the
 * currently selected one. `interactiveRadioAvailable` is `false` for
 * stations Pandora does not tune (e.g. QuickMix) — both references hide
 * their picker in that case.
 */
data class StationModes(
    val isInteractiveRadioAvailable: Boolean = true,
    val currentModeID: kotlin.Int? = null,
    val modes: List<StationMode> = emptyList(),
) {
    fun mode(id: kotlin.Int): StationMode? = modes.firstOrNull { it.id == id }

    val currentMode: StationMode?
        get() = currentModeID?.let { mode(it) }

    companion object {
        /**
         * Decodes the raw response body of either `interactiveradio` endpoint.
         * `modeId`/`currentModeId` arrive as JSON number or digit string
         * (panedora `modeId()` accepts both); entries without a usable id or
         * name are dropped, duplicates by id keep the first (panedora `seen`).
         */
        fun from(data: ByteArray): StationModes {
            val objectValue = try {
                AnyJSON.fromJsonElement(Json.parseToJsonElement(data.decodeToString())).objectValue
            } catch (e: Exception) {
                null
            } ?: throw PandoraMalformedResponseError("station modes body was not a JSON object")
            // panedora treats a present `errorCode` (or `stat: "fail"`) as a
            // failed reply even over HTTP 200.
            val errorCode = objectValue["errorCode"]
            if (errorCode != null && errorCode != AnyJSON.Null) {
                throw PandoraMalformedResponseError("station modes request rejected ($errorCode)")
            }
            if (objectValue["stat"]?.stringValue == "fail") {
                throw PandoraMalformedResponseError("station modes request rejected (stat fail)")
            }
            return fromResult(objectValue)
        }

        /** Decodes from an already-parsed JSON object (also the test seam). */
        fun fromResult(result: Map<String, AnyJSON>): StationModes {
            val interactiveRadioAvailable = result["interactiveRadioAvailable"]?.boolValue ?: true
            // panedora: an absent `availableModes` array is only valid when the
            // server said interactive radio is unavailable.
            val entries = result["availableModes"]?.arrayValue
                ?: (if (interactiveRadioAvailable) null else emptyList())
                ?: throw PandoraMalformedResponseError("station modes missing availableModes array")

            val seen = mutableSetOf<kotlin.Int>()
            val parsed = mutableListOf<StationMode>()
            for (entry in entries) {
                val object_ = entry.objectValue ?: continue
                val id = modeID(object_["modeId"]) ?: continue
                val name = object_["modeName"]?.stringValue?.takeIf { it.isNotEmpty() } ?: continue
                if (seen.contains(id)) continue
                seen.add(id)
                parsed.add(
                    StationMode(
                        id = id,
                        name = name,
                        modeDescription = object_["modeDescription"]?.stringValue ?: "",
                        isAvailable = object_["isModeAvailable"]?.boolValue ?: false,
                        isPremiumOnly = object_["isPremiumOnly"]?.boolValue ?: false,
                    )
                )
            }
            return StationModes(
                isInteractiveRadioAvailable = interactiveRadioAvailable,
                currentModeID = modeID(result["currentModeId"]),
                modes = parsed,
            )
        }

        /** `modeId`/`currentModeId` are JSON number or digit string. */
        private fun modeID(value: AnyJSON?): kotlin.Int? =
            value?.intValue?.toInt() ?: value?.stringValue?.toIntOrNull()
    }
}

// MARK: Errors

/** Port of `StationModesError`. */
sealed class StationModesError : Exception() {
    /**
     * 401/403 even after a fresh csrf token — the auth token was rejected;
     * the caller should re-authenticate and retry once.
     */
    data object Unauthorized : StationModesError()

    /** The requested mode needs a paid subscription (free account). */
    data object NotEntitled : StationModesError()

    /**
     * The server answered 200 but kept the old mode (panedora's
     * post-set `currentModeId` check), or the mode is not offered.
     */
    data object ModeNotOffered : StationModesError()

    /** Any other non-2xx status. */
    data class HttpStatus(val statusCode: kotlin.Int) : StationModesError()
}

// MARK: Transport

/**
 * A POST and its raw reply, *including* non-2xx statuses — the caller
 * distinguishes 401/403 (csrf refresh + retry) from other failures.
 */
data class ModesHTTPResponse(
    val statusCode: kotlin.Int,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ModesHTTPResponse && other.statusCode == statusCode && other.body.contentEquals(body)

    override fun hashCode(): kotlin.Int = statusCode * 31 + body.contentHashCode()
}

fun interface ModesTransport {
    suspend fun post(url: String, body: ByteArray, headers: Map<String, String>): ModesHTTPResponse
}

// MARK: CSRF

/**
 * Supplies the `X-CsrfToken` header value. `refresh()` must return a
 * *new* token — the client calls it once after a 401/403 and retries.
 */
interface CSRFTokenProviding {
    suspend fun token(): String
    suspend fun refresh(): String
}

// MARK: Client

/**
 * The `interactiveradio` client. Stateless; the session's user token is
 * passed per call so re-auth stays in `SessionManager`.
 */
class PandoraModesClient(
    private val transport: ModesTransport,
    private val csrf: CSRFTokenProviding,
    /**
     * The endpoints sit behind Pandora's web player, so the client
     * identifies itself browser-like (pandoCast sends a Chrome UA).
     */
    private val userAgent: String =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1 Amphora",
) {
    /**
     * The station's offered modes (pandoCast
     * `GetAvailableStationModesAsync` — payload `{"stationId": …}`).
     */
    suspend fun getModes(userAuthToken: String, stationId: String): StationModes {
        val body = post(
            endpoint = GET_MODES_ENDPOINT,
            parameters = mapOf("stationId" to JSONValue.Str(stationId)),
            userAuthToken = userAuthToken,
        )
        return StationModes.from(body)
    }

    /**
     * Select a mode (pandoCast `SetStationModeAsync` —
     * `{"stationId": …, "modeId": …, "previousModeId": …}`; panedora omits
     * `previousModeId`, so it is optional here). Fails with
     * `StationModesError.ModeNotOffered` when the reply's `currentModeId`
     * did not move to the requested mode (pandora can answer 200 while
     * keeping the old mode — panedora re-checks).
     */
    suspend fun setMode(
        userAuthToken: String,
        stationId: String,
        modeId: kotlin.Int,
        previousModeId: kotlin.Int?,
    ): StationModes {
        val parameters = mutableMapOf<String, JSONValue>(
            "stationId" to JSONValue.Str(stationId),
            "modeId" to JSONValue.Int(modeId.toLong()),
        )
        previousModeId?.let { parameters["previousModeId"] = JSONValue.Int(it.toLong()) }
        val body = post(
            endpoint = SET_MODE_ENDPOINT,
            parameters = parameters,
            userAuthToken = userAuthToken,
        )
        val modes = StationModes.from(body)
        val current = modes.currentModeID
        if (current != null && current != modeId) {
            throw StationModesError.ModeNotOffered
        }
        return modes
    }

    private suspend fun post(
        endpoint: String,
        parameters: Map<String, JSONValue>,
        userAuthToken: String,
    ): ByteArray {
        val url = DEFAULT_BASE_URL + endpoint
        val body = JSONValue.encoded(parameters)
        val csrfToken = csrf.token()
        var response = send(url = url, body = body, userAuthToken = userAuthToken, csrfToken = csrfToken)
        if (response.statusCode == 401 || response.statusCode == 403) {
            // pandoCast: re-bootstrap the csrf cookie, retry once.
            val fresh = csrf.refresh()
            response = send(url = url, body = body, userAuthToken = userAuthToken, csrfToken = fresh)
        }
        return when (response.statusCode) {
            in 200..299 -> response.body
            401, 403 -> throw StationModesError.Unauthorized
            else -> throw StationModesError.HttpStatus(response.statusCode)
        }
    }

    private suspend fun send(
        url: String,
        body: ByteArray,
        userAuthToken: String,
        csrfToken: String,
    ): ModesHTTPResponse = transport.post(
        url, body,
        mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "X-AuthToken" to userAuthToken,
            "X-CsrfToken" to csrfToken,
            "Origin" to "https://www.pandora.com",
            "Referer" to "https://www.pandora.com/",
            "X-Requested-With" to "XMLHttpRequest",
            "User-Agent" to userAgent,
        ),
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://www.pandora.com/api/"
        const val GET_MODES_ENDPOINT = "v1/interactiveradio/getAvailableModesSimple"
        const val SET_MODE_ENDPOINT = "v1/interactiveradio/setAndGetAvailableModes"
    }
}
