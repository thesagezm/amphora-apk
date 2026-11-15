//
//  URLBuilder.kt
//  pandoracore
//
//  Port of PandoraCore/URLBuilder.swift — builds the legacy JSON API (v5)
//  request URL and body, mirroring `pithos.pandora.Pandora.json_call`
//  exactly:
//
//  - Query string order: partner_id, user_id, auth_token, method.
//    `auth_token` prefers the user token, falling back to the partner token.
//  - Body: caller's parameters, plus `syncTime` when a time offset is known,
//    plus `userAuthToken`/`partnerAuthToken` (user preferred), matching
//    pithos' if/elif chain.
//  - Body encryption is opt-in per call (`encrypt`), because
//    `auth.partnerLogin` is sent as plain JSON in pithos while
//    `auth.userLogin` and everything after are Blowfish-ECB hex.
//
//  No networking here — this only assembles bytes. The transport lives above.
//
//  Divergence from iOS: `now` is epoch seconds as a Double instead of
//  `Date`, so the Int() truncation semantics of `syncTime` are exact on
//  the JVM. URLs are Strings (the app-side transport parses them).
//

package pandoracore

class URLBuilderException :
    Exception("URL construction failed") {
    companion object {
        val UrlConstructionFailed = URLBuilderException()
    }
}

class URLBuilder(
    val baseURL: String = PandoraAPI.baseURL,
    var context: Context = Context(),
) {

    /**
     * Session-derived fields threaded into later requests, updated by
     * `SessionManager` as auth progresses.
     */
    data class Context(
        var partnerId: String? = null,
        var partnerAuthToken: String? = null,
        var userId: String? = null,
        var userAuthToken: String? = null,
        /** `pandora_time - local_time` at partner login; null until known. */
        var timeOffset: Double? = null,
    )

    // MARK: URL

    /**
     * Query-string URL for `method`, in pithos' parameter order.
     */
    fun endpointURL(method: String): String {
        val items = mutableListOf<Pair<String, String>>()
        context.partnerId?.let { items.add("partner_id" to it) }
        context.userId?.let { items.add("user_id" to it) }
        (context.userAuthToken ?: context.partnerAuthToken)?.let { items.add("auth_token" to it) }
        items.add("method" to method)

        // Strict RFC 3986 encoding: auth tokens are opaque (may contain
        // `+`, `/`, `=` — none of which are unreserved). Java's URLEncoder
        // is form-encoding (space becomes `+`), which servers can
        // misread — encode byte-for-byte here instead.
        val query = items.joinToString("&") { (k, v) ->
            "${encodeQueryValue(k)}=${encodeQueryValue(v)}"
        }

        // The base URL is a fixed constant without query/fragment; validate
        // it parses, then append the query directly (no re-encoding step).
        try {
            java.net.URI(baseURL)
        } catch (e: Exception) {
            throw URLBuilderException.UrlConstructionFailed
        }
        return if (query.isEmpty()) baseURL else "$baseURL?$query"
    }

    /**
     * Percent-encodes everything outside the RFC 3986 unreserved set
     * (`A-Z a-z 0-9 - . _ ~`) — matches `quote_plus(..., safe="")`.
     */
    internal fun encodeQueryValue(value: String): String {
        val encoded = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            when (c) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '-', '.', '_', '~' -> encoded.append(c)
                else -> encoded.append(String.format("%%%02X", c.code))
            }
        }
        return encoded.toString()
    }

    // MARK: Body

    /**
     * Assembles the request body for `method`: `parameters` plus session
     * fields (see file header), encoded as deterministic JSON — and, when
     * `encrypt` is true, Blowfish-ECB hex using `PandoraAPI.encryptKey`.
     *
     * @param parameters caller-supplied fields for the method.
     * @param encrypt false for `auth.partnerLogin` (plain JSON, per pithos);
     *     true for `auth.userLogin` and everything else.
     * @param now epoch seconds — injection point for deterministic
     *     `syncTime` in tests.
     */
    fun requestBody(
        parameters: Map<String, JSONValue>,
        encrypt: Boolean,
        now: Double,
    ): ByteArray {
        val merged = mutableMapOf<String, JSONValue>()
        merged.putAll(parameters)
        context.timeOffset?.let { offset ->
            // Int(now + offset) truncates like Python int(): toward zero.
            merged["syncTime"] = JSONValue.Int((now + offset).toLong())
        }
        context.userAuthToken?.let { merged["userAuthToken"] = JSONValue.Str(it) }
            ?: context.partnerAuthToken?.let { merged["partnerAuthToken"] = JSONValue.Str(it) }

        val json = JSONValue.encoded(merged)
        return if (encrypt) PandoraCrypto.encryptRequestBody(json).toByteArray(Charsets.UTF_8) else json
    }
}
