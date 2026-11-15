//
//  StationOrder.kt
//  pandoracore
//
//  Port of PandoraCore/StationOrder.swift — the user's station ordering
//  (AGENTS.md persistence: "station order").
//
//  Pure logic: an ordered list of station ids. Stations the user has
//  never touched keep the server's order; the stored ids only pin the
//  ones the user reordered.
//
//  Kotlin note: the Swift struct is a value type with `mutating` funcs;
//  the port keeps the same shape as an immutable data class whose
//  `setOrder`/`move` return the new value (`order = order.move(...)`).
//

package pandoracore

import kotlinx.serialization.Serializable

/** Persisted station ordering. */
@Serializable
data class StationOrder(
    /**
     * The stations the user moved, in their chosen order. Ids not in
     * this list keep the server's relative order.
     */
    val orderedIds: List<String> = emptyList(),
) {
    /** `true` when the user has not reordered anything. */
    val isEmpty: Boolean
        get() = orderedIds.isEmpty()

    /**
     * Applies the stored order to a station list.
     *
     * Stations the user moved come first (in their stored order), then
     * the remaining stations in the order the server sent them. Ids in
     * the store that no longer exist are dropped.
     */
    fun apply(stations: List<Station>): List<Station> {
        if (orderedIds.isEmpty()) return stations
        val byId = stations.associateBy { it.id }
        val result = mutableListOf<Station>()
        val used = mutableSetOf<String>()
        for (id in orderedIds) {
            val station = byId[id]
            if (station != null && !used.contains(id)) {
                result.add(station)
                used.add(id)
            }
        }
        for (station in stations) {
            if (!used.contains(station.id)) {
                result.add(station)
                used.add(station.id)
            }
        }
        return result
    }

    /** Records a new order (the ids as the user sees them). */
    fun setOrder(ids: List<String>): StationOrder = StationOrder(ids)

    /**
     * Moves a station to a new position — the ids as the user sees them
     * *before* the move, plus the station to move and its destination
     * index.
     */
    fun move(stationId: String, destination: kotlin.Int, currentOrder: List<String>): StationOrder {
        val ids = currentOrder.toMutableList()
        val from = ids.indexOf(stationId)
        if (from < 0) return this
        ids.removeAt(from)
        val target = destination.coerceIn(0, ids.size)
        ids.add(target, stationId)
        return StationOrder(ids)
    }
}
