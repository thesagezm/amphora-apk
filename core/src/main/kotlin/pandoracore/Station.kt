//
//  Station.kt
//  pandoracore
//
//  Port of PandoraCore/Station.swift — models for `user.getStationList`
//  results. Field names are the wire keys from the legacy JSON API (v5),
//  cross-checked against both references:
//
//  - `pithos/pandora/pandora.py` `Station.__init__` — the minimal required
//    set every response carries (`stationId`, `stationToken`, `isShared`,
//    `isQuickMix`, `stationName`, plus `quickMixStationIds` on quickmix).
//  - pydora's `pandora/models/station.py` `Station`/`StationList` — the
//    optional set (`artUrl`, `isThumbprint`, `isGenreStation`, allow* flags,
//    `checksum`), decoded here as optionals so a sparse response never
//    fails the whole list.
//
//  No Android, no UI — pandoracore stays portable.
//
//  Kotlin note: Swift decodes via a Codable round-trip; the port parses
//  the `[String: AnyJSON]` result directly with the same defaults and the
//  same string-or-number id handling. `@Serializable` covers persistence
//  round-trips of already-stringified ids.
//

package pandoracore

import kotlinx.serialization.Serializable

/** One station from `user.getStationList` (or a `station.createStation`
 *  result — same wire shape, pithos constructs `Station(d)` from both). */
@Serializable
data class Station(
    /** `stationId` — stable numeric id (pithos `Station.id`). */
    val id: String,
    /**
     * `stationToken` — what later calls (`station.getPlaylist`,
     * feedback, rename, delete) pass as `stationToken`.
     */
    val token: String,
    /** `stationName` (pithos `Station.name`). */
    val name: String,
    /**
     * `isShared` — shared stations are not the user's own
     * (pithos: `isCreator = not isShared`; rename transforms them first).
     */
    val isShared: Boolean = false,
    /** `isQuickMix` — the QuickMix aggregator station. */
    val isQuickMix: Boolean = false,
    /** `isThumbprint` — seeded from the user's thumbs (pydora). */
    val isThumbprint: Boolean = false,
    /** `isGenreStation` — a genre station rather than a personal one. */
    val isGenreStation: Boolean = false,
    /**
     * `artUrl` — station artwork when the response carries it (pydora
     * `art_url`; pithos doesn't read it). `includeStationArtUrl=true`.
     */
    val artUrl: String? = null,
    /**
     * `quickMixStationIds` — present only on the quickmix station;
     * the ids of the stations feeding it (pithos reads it there).
     */
    val quickMixStationIds: List<String> = emptyList(),
) {
    companion object {
        /**
         * Decodes one station from an unwrapped result object (e.g.
         * `station.createStation`'s reply).
         */
        fun from(result: Map<String, AnyJSON>): Station = Station(
            id = stringId(result, "stationId"),
            token = stringId(result, "stationToken"),
            name = result["stationName"]?.stringValue
                ?: throw PandoraMalformedResponseError("missing string field stationName"),
            isShared = result["isShared"]?.boolValue ?: false,
            isQuickMix = result["isQuickMix"]?.boolValue ?: false,
            isThumbprint = result["isThumbprint"]?.boolValue ?: false,
            isGenreStation = result["isGenreStation"]?.boolValue ?: false,
            artUrl = result["artUrl"]?.stringValue,
            quickMixStationIds = result["quickMixStationIds"]?.arrayValue
                ?.map { it.stringValue ?: throw PandoraMalformedResponseError("non-string quickMixStationIds entry") }
                ?: emptyList(),
        )

        /** `stationId`/`stationToken` are string-or-number on the wire. */
        private fun stringId(result: Map<String, AnyJSON>, key: String): String {
            result[key]?.stringValue?.let { return it }
            result[key]?.intValue?.let { return it.toString() }
            throw PandoraMalformedResponseError("missing or non-numeric $key")
        }
    }
}

/**
 * The `user.getStationList` result: `stations` plus a `checksum` that
 * later `user.getStationListChecksum` calls compare against (pydora
 * `StationList.has_changed`; unused for now, decoded so it isn't lost).
 */
@Serializable
data class StationList(
    val stations: List<Station>,
    val checksum: String? = null,
) {
    companion object {
        /**
         * Decodes from the already-unwrapped `[String: AnyJSON]` result of
         * `ResponseEnvelope.result` — the same shape pydora feeds its
         * model layer.
         */
        fun from(result: Map<String, AnyJSON>): StationList {
            val array = result["stations"]?.arrayValue
                ?: throw PandoraMalformedResponseError("station list missing stations array")
            return StationList(
                stations = array.map { entry ->
                    val obj = entry.objectValue
                        ?: throw PandoraMalformedResponseError("station list entry is not an object")
                    Station.from(obj)
                },
                checksum = result["checksum"]?.stringValue,
            )
        }
    }
}
