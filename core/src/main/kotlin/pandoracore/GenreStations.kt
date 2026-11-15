//
//  GenreStations.kt
//  pandoracore
//
//  Port of PandoraCore/GenreStations.swift — Pandora's featured "genre"
//  stations — the curated, Pandora-created stations ("Today's Hits",
//  "Classic Rock", …) shown on Pandora's own front page, grouped into
//  browse categories. Legacy v5:
//
//    station.getGenreStations   (no parameters)
//      → {"categories": [
//           {"categoryName": "Pop",
//            "stations": [{"stationId": …, "stationToken": …,
//                          "stationName": …, "categoryName": …}]}],
//         "checksum": …}
//
//  A genre station has no playlist of its own — a personal station is
//  created from it with `station.createStation {"musicToken":
//  <that stationToken>}`. Verified 2026-10-24 against four independent
//  clients (pydora, pancake, python-pandora + the Sonos Indigo plugin,
//  xivpiano) — see the iOS file for the full citation list.
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

/** One Pandora-created station inside a browse category. */
data class GenreStation(
    /**
     * `stationToken` — the value passed as `musicToken` to
     * `station.createStation` when the user wants this station.
     */
    val token: String,
    /**
     * `stationId` — the numeric web id, when the reply carries one
     * (number or string tolerated).
     */
    val stationId: String = "",
    /** `stationName`, e.g. "Today's Hits". */
    val name: String,
    /**
     * `categoryName` — repeated on the station entry by the API; when
     * the entry omits it, the decoder fills in the category's own.
     */
    val categoryName: String,
    /**
     * `artUrl` when the reply carries one; the UI falls back to a
     * generic glyph when absent.
     */
    val artUrl: String? = null,
) {
    val id: String
        get() = token
}

/** A browse category and its stations (one `categories` entry). */
data class GenreCategory(
    /** `categoryName`, e.g. "Pop". */
    val name: String,
    val stations: List<GenreStation>,
) {
    val id: String
        get() = name
}

/** The `station.getGenreStations` reply. */
data class GenreStations(
    val categories: List<GenreCategory>,
) {
    companion object {
        /**
         * Decodes from the request's `result` object (also the test seam).
         * Missing/not-array `categories` is malformed (the method has no
         * other failure mode on the wire); inside, decoding is tolerant —
         * entries without a token or name are dropped, duplicate tokens
         * within a category keep the first, empty categories are dropped.
         */
        fun from(result: Map<String, AnyJSON>): GenreStations {
            val entries = result["categories"]?.arrayValue
                ?: throw PandoraMalformedResponseError("genre stations missing categories array")
            val parsed = mutableListOf<GenreCategory>()
            for (entry in entries) {
                val object_ = entry.objectValue ?: continue
                val name = object_["categoryName"]?.stringValue?.takeIf { it.isNotEmpty() } ?: continue
                val stations = mutableListOf<GenreStation>()
                val seen = mutableSetOf<String>()
                for (station in object_["stations"]?.arrayValue ?: emptyList()) {
                    val stationObject = station.objectValue ?: continue
                    val token = stationObject["stationToken"]?.stringValue?.takeIf { it.isNotEmpty() } ?: continue
                    val stationName = stationObject["stationName"]?.stringValue?.takeIf { it.isNotEmpty() } ?: continue
                    if (seen.contains(token)) continue
                    seen.add(token)
                    stations.add(
                        GenreStation(
                            token = token,
                            stationId = idString(stationObject["stationId"]),
                            name = stationName,
                            categoryName = object_["categoryName"]?.stringValue ?: name,
                            artUrl = stationObject["artUrl"]?.stringValue,
                        )
                    )
                }
                if (stations.isEmpty()) continue
                parsed.add(GenreCategory(name = name, stations = stations))
            }
            return GenreStations(categories = parsed)
        }

        /** `stationId` arrives as JSON number or string. */
        private fun idString(value: AnyJSON?): String =
            value?.stringValue ?: value?.intValue?.toString() ?: ""
    }
}
