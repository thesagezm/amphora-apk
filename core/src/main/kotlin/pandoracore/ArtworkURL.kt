//
//  ArtworkURL.kt
//  pandoracore
//
//  Port of PandoraCore/ArtworkURL.swift — candidate URLs for one artwork
//  reference.
//
//  Pandora and the internet-radio directory hand out artwork URLs from
//  very different worlds:
//
//  - Pandora's `albumArtUrl` / `artUrl` are HTTPS, but some entries
//    arrive scheme-less or with a stray space that the URL parser
//    rejects.
//  - A radio station's favicon lives on whatever host the station
//    happens to run — very often a plain-HTTP host that serves no TLS
//    at all (checked live against radio-browser: a good share of the
//    favicons are `http://`, and some stations have none).
//
//  The candidate list is therefore "HTTPS first, the URL exactly as
//  given second". The second candidate is the important one: an
//  HTTP-only favicon host fails the HTTPS attempt and only answers
//  the raw URL, so the raw form must be kept intact — upgrading it
//  to HTTPS as well would leave such stations with no artwork.
//
//  Pure Kotlin, no UI — this is part of the portable surface.
//  URLs are Strings here (the app's loader parses them).
//

package pandoracore

/** Builds the ordered list of URLs to try for one artwork reference. */
object ArtworkURLCandidates {

    /**
     * The URLs to try, best first:
     *
     * 1. the HTTPS-preferred, percent-encoded form;
     * 2. the URL exactly as the service gave it (scheme preserved),
     *    so an `http://` favicon is actually attempted over HTTP.
     *
     * Duplicates are removed, so an already-HTTPS URL yields a single
     * candidate and a scheme-less URL yields one candidate as well.
     */
    fun candidates(raw: String): List<String> {
        val seen = mutableSetOf<String>()
        val urls = mutableListOf<String>()

        fun add(url: String?) {
            if (url == null || seen.contains(url)) return
            seen.add(url)
            urls.add(url)
        }

        add(httpsPreferredURL(raw))
        add(rawURL(raw))
        return urls
    }

    /**
     * The scheme-normalized, percent-encoded, HTTPS-preferred URL:
     *
     * - a missing scheme becomes `https://` (Pandora's artwork URLs
     *   are not always absolute);
     * - an `http://` URL is upgraded to `https://` — every Pandora
     *   artwork host serves TLS, and cleartext should be the fallback
     *   path, never the first attempt;
     * - characters a URL parser rejects (spaces are the common one)
     *   are percent-encoded instead of making the whole URL unusable.
     */
    fun httpsPreferredURL(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val lowercased = trimmed.lowercase()
        val hasScheme = lowercased.startsWith("http://") || lowercased.startsWith("https://")
        val withScheme = if (hasScheme) trimmed else "https://" + trimmed

        val parsed = parseOrEncode(withScheme) ?: return null
        return forceScheme(parsed, "https")
    }

    /**
     * The URL exactly as provided, scheme preserved — `http://` stays
     * `http://`. This is the last-resort candidate for radio favicons
     * on hosts that serve nothing but cleartext. Null when the string
     * carries no scheme (there is nothing to preserve) or cannot be
     * parsed even after encoding.
     */
    fun rawURL(raw: String): String? {
        val trimmed = raw.trim()
        val lowercased = trimmed.lowercase()
        val isHTTP = lowercased.startsWith("http://")
        if (!isHTTP && !lowercased.startsWith("https://")) return null

        val parsed = parseOrEncode(trimmed) ?: return null
        return forceScheme(parsed, if (isHTTP) "http" else "https")
    }

    /** Parses `s`, or percent-encodes rejected characters and retries. */
    private fun parseOrEncode(s: String): java.net.URI? {
        try {
            return java.net.URI(s)
        } catch (_: Exception) {
            // fall through to the encoded retry
        }
        return try {
            java.net.URI(percentEncodeRejected(s))
        } catch (_: Exception) {
            null
        }
    }

    /** Replaces the leading scheme with `scheme`, rejecting an empty host. */
    private fun forceScheme(uri: java.net.URI, scheme: String): String? {
        // Swift rejects `components.host == ""` (e.g. "https:///path").
        if (uri.rawAuthority != null && uri.rawAuthority.isEmpty()) return null
        return uri.toString().replaceFirst(Regex("(?i)^[a-z][a-z0-9+.-]*://"), "$scheme://")
    }

    /**
     * Host + path + query + fragment characters, minus the space that
     * makes the URL parser reject the string outright. The percent sign
     * is allowed explicitly so an already-escaped URL (`%20`, `%2F` —
     * signed CDN paths carry them) is not encoded a second time when the
     * space-repair path runs.
     */
    private fun isAllowed(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c in "!#$&'()*+,-./:;=?@[]_~"

    private fun percentEncodeRejected(s: String): String = buildString {
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c < 128.toChar() && isAllowed(c)) {
                append(c)
            } else {
                append(String.format("%%%02X", byte.toInt() and 0xFF))
            }
        }
    }
}
