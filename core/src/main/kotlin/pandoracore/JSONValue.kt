//
//  JSONValue.kt
//  pandoracore
//
//  Port of PandoraCore/JSONValue.swift — minimal typed JSON scalar for
//  building request bodies without `Any`. Bodies are serialized with
//  sorted keys so tests can assert exact bytes — Pandora's server ignores
//  object key order.
//

package pandoracore

sealed class JSONValue {
    data class Str(val value: String) : JSONValue()
    data class Int(val value: Long) : JSONValue()
    data class Bool(val value: Boolean) : JSONValue()

    /** Arrays of scalars (e.g. `ad.registerAd`'s `adTrackingTokens`). */
    data class Array(val values: List<JSONValue>) : JSONValue()

    companion object {
        /**
         * Encodes a parameter dictionary as JSON with lexicographically
         * sorted keys. Deterministic across platforms.
         */
        fun encoded(parameters: Map<String, JSONValue>): ByteArray {
            val body = parameters.keys.sorted().joinToString(",") { key ->
                escape(key) + ":" + (parameters[key] ?: Str("")).literal
            }
            return "{$body}".toByteArray(Charsets.UTF_8)
        }

        /**
         * RFC 8259 string escaping. Control characters use the short forms
         * where they exist; everything else below 0x20 uses \u00XX.
         */
        fun escape(string: String): String {
            val out = StringBuilder("\"")
            for (scalar in string.codePoints()) {
                when {
                    scalar == 0x22 -> out.append("\\\"")
                    scalar == 0x5C -> out.append("\\\\")
                    scalar == 0x08 -> out.append("\\b")
                    scalar == 0x09 -> out.append("\\t")
                    scalar == 0x0A -> out.append("\\n")
                    scalar == 0x0C -> out.append("\\f")
                    scalar == 0x0D -> out.append("\\r")
                    scalar in 0x00..0x1F -> out.append(String.format("\\u%04x", scalar))
                    else -> out.appendCodePoint(scalar)
                }
            }
            out.append("\"")
            return out.toString()
        }
    }

    /** This value's JSON text (not including surrounding braces). */
    private val literal: String
        get() = when (this) {
            is Str -> escape(value)
            is Int -> value.toString()
            is Bool -> if (value) "true" else "false"
            is Array -> values.joinToString(",", "[", "]") { it.literal }
        }
}
