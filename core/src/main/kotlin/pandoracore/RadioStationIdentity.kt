//
//  RadioStationIdentity.kt
//  pandoracore
//
//  Port of PandoraCore/RadioStationIdentity.swift — the identity behind
//  the generated radio-station artwork.
//
//  Plenty of internet-radio stations have no favicon at all, and the
//  ones that have one usually serve a 16×16 pixel icon — blown up to
//  full-player size that reads as "no artwork" (or worse, as a blurry
//  smudge). The fallback is a generated tile: the station's initials on
//  a deterministic gradient.
//
//  "Deterministic" is the important word: the same station must get
//  the same color on every launch and on every device. Platform hash
//  codes are seeded per process, so they cannot be used here — the
//  name is hashed with FNV-1a instead, which is a fixed algorithm over
//  the UTF-8 bytes.
//
//  Pure Kotlin, no UI — the app target turns the hue into colors;
//  this port mirrors the iOS numbers exactly (pinned by tests).
//

package pandoracore

/** The generated identity of a radio station: its monogram and its hue. */
object RadioStationIdentity {

    /**
     * The monogram shown on the generated tile — the first letter of
     * up to two words of the station name, uppercased.
     *
     * "Radio Paradise" → "RP", "KEXP" → "K". An empty name falls back
     * to "R" (radio) so the tile is never blank.
     */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "R"

        val letters = words.take(2).mapNotNull { word -> word.firstOrNull() }
        if (letters.isEmpty()) return "R"
        return letters.joinToString("").uppercase()
    }

    /**
     * The station's hue in the 0.0..1.0 range, derived from its name.
     *
     * The same name always yields the same hue — on every launch, on
     * every device, on every port of the app.
     */
    fun hue(name: String): Double {
        val hash = fnv1a(name)
        // 3600 steps → a tenth of a degree, so neighbouring names land
        // on visibly different hues.
        return (hash % 3600u).toDouble() / 3600.0
    }

    /**
     * The FNV-1a hash (32 bit) of a name, over its UTF-8 bytes.
     * Lowercased first, so "KEXP" and "kexp" share one color.
     */
    fun fnv1a(name: String): UInt {
        var hash = 0x811c9dc5u
        for (byte in name.trim().lowercase().toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toUInt() and 0xFFu)
            hash = hash * 0x01000193u
        }
        return hash
    }
}
