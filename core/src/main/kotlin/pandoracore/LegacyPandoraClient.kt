//
//  LegacyPandoraClient.kt
//  pandoracore
//
//  Port of PandoraCore/LegacyPandoraClient.swift — JSON API v5 transport:
//  assembles URL + body with `URLBuilder`, performs the POST via an
//  injected `HttpTransport`, unwraps the response envelope. Session
//  lifecycle (partner → user → re-auth) lives in `SessionManager`.
//
//  Derived from `pithos.pandora.Pandora.json_call`. No UI, no Android,
//  no HTTP client — the platform adapter (OkHttp on Android) implements
//  `HttpTransport` above.
//

package pandoracore

class LegacyPandoraClient(
    private val transport: HttpTransport,
    builder: URLBuilder = URLBuilder(),
) : PandoraAPIClient {

    private val stateLock = Object()
    private var builder: URLBuilder = builder // var: context mutates after SessionManager auth steps

    /**
     * Current session fields (partner id, tokens, time offset). Readable by
     * `SessionManager`; writes happen only through the setter below.
     */
    var context: URLBuilder.Context
        get() = synchronized(stateLock) { builder.context }
        set(value) = synchronized(stateLock) { builder.context = value }

    /**
     * Issues one call against the endpoint.
     *
     * @param method e.g. `auth.partnerLogin`.
     * @param parameters JSON fields (nested values pass through).
     * @param encrypt Blowfish-ECB body encryption; see `URLBuilder` — false
     *     only for `auth.partnerLogin`.
     * @param now epoch seconds — clock injection for deterministic
     *     `syncTime` in tests.
     */
    suspend fun request(
        method: String,
        parameters: Map<String, JSONValue> = emptyMap(),
        encrypt: Boolean = true,
        now: Double,
    ): Map<String, AnyJSON> {
        val url = synchronized(stateLock) { builder.endpointURL(method) }
        val body = synchronized(stateLock) {
            builder.requestBody(
                parameters = parameters,
                encrypt = encrypt,
                now = now,
            )
        }

        // pithos sends text/plain for the encrypted-body form.
        val contentType = if (encrypt) "text/plain" else "application/json"
        val payload = transport.post(url, body, contentType)
        return ResponseEnvelope.result(payload)
    }

    // MARK: PandoraAPIClient

    override suspend fun request(
        method: String,
        parameters: Map<String, JSONValue>,
    ): Map<String, AnyJSON> = request(method = method, parameters = parameters, encrypt = true, now = epochNow())

    companion object {
        /** Wall-clock injection point; tests pass explicit `now` instead. */
        fun epochNow(): Double = System.currentTimeMillis() / 1000.0
    }
}
