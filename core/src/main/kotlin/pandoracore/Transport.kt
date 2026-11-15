//
//  Transport.kt
//  pandoracore
//
//  The transport seam of PandoraCore/LegacyPandoraClient.swift, split for
//  platform portability: the Swift `HTTPTransport` protocol and the pure
//  `makeRequest` assembly port here; the URLSession implementation becomes
//  an app-side OkHttp adapter (`:app`), since `:core` stays free of any
//  HTTP client. Tests inject fakes and never touch the network.
//

package pandoracore

/**
 * POSTs a request body and returns the raw response body.
 * Injected so tests never touch the network.
 *
 * Throws `PandoraTransportError` with the sanitized reason strings the
 * error copy knows: "unreachable", "timeout", "network failure",
 * "non-HTTP response", "HTTP <status>".
 */
fun interface HttpTransport {
    suspend fun post(url: String, body: ByteArray, contentType: String): ByteArray
}

/**
 * A minimal GET transport (Swift: `HTTPGETTransport`, defined beside the
 * lyrics provider), so the lyrics / artwork / radio-directory clients
 * never talk to a HTTP client directly and can be tested with a stub.
 *
 * Throws `PandoraTransportError` with the same sanitized reason strings
 * as `HttpTransport.post`.
 */
fun interface HttpGetTransport {
    suspend fun get(url: String, headers: Map<String, String>): ByteArray
}

/**
 * The wire request a transport sends — pure assembly, no I/O. A type of
 * its own so the timeout and headers stay unit-testable without a
 * network (the port of `URLSessionTransport.makeRequest`).
 */
data class TransportRequest(
    val url: String,
    val method: String,
    val body: ByteArray,
    val contentType: String,
    val userAgent: String,
    val timeoutSeconds: Double,
) {
    companion object {
        /** The main transport's request timeout — the shared package
         *  default (see `NetworkDefaults`). */
        const val DEFAULT_TIMEOUT_INTERVAL: Double = NetworkDefaults.requestTimeoutInterval

        const val DEFAULT_USER_AGENT = "Amphora"

        /**
         * Assembles the POST request. The OkHttp adapter maps this onto
         * its client call and applies `timeoutSeconds` per request — a
         * named interval, never the system default, so a connection that
         * is accepted but never answers cannot hang the request
         * effectively forever.
         */
        fun make(
            url: String,
            body: ByteArray,
            contentType: String,
            timeoutInterval: Double = DEFAULT_TIMEOUT_INTERVAL,
            userAgent: String = DEFAULT_USER_AGENT,
        ): TransportRequest = TransportRequest(
            url = url,
            method = "POST",
            body = body,
            contentType = contentType,
            userAgent = userAgent,
            timeoutSeconds = timeoutInterval,
        )
    }
}
