//
//  StationOrderTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/StationOrderTests.swift — the persisted
//  station ordering.
//

package pandoracore

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationOrderTests {

    private fun station(id: String, name: String = "S") = Station(
        id = id,
        token = "T-$id",
        name = name,
        isShared = false,
        isQuickMix = false,
    )

    @Test
    fun emptyOrderKeepsServerOrder() {
        val stations = listOf(station("1"), station("2"), station("3"))
        val order = StationOrder()
        assertTrue(order.isEmpty)
        assertEquals(listOf("1", "2", "3"), order.apply(stations).map { it.id })
    }

    @Test
    fun storedIdsComeFirstInStoredOrder() {
        val stations = listOf(station("1"), station("2"), station("3"))
        val order = StationOrder(orderedIds = listOf("3", "1"))
        assertEquals(listOf("3", "1", "2"), order.apply(stations).map { it.id })
    }

    @Test
    fun unknownStoredIdsAreDropped() {
        val stations = listOf(station("1"), station("2"))
        val order = StationOrder(orderedIds = listOf("ghost", "2"))
        assertEquals(listOf("2", "1"), order.apply(stations).map { it.id })
    }

    @Test
    fun moveReordersTheList() {
        var order = StationOrder()
        val current = listOf("1", "2", "3")
        order = order.move(stationId = "1", destination = 2, currentOrder = current)
        assertEquals(listOf("2", "3", "1"), order.orderedIds)
    }

    @Test
    fun moveClampsDestination() {
        var order = StationOrder()
        order = order.move(stationId = "1", destination = 99, currentOrder = listOf("1", "2", "3"))
        assertEquals(listOf("2", "3", "1"), order.orderedIds)
        order = order.move(stationId = "3", destination = -5, currentOrder = order.orderedIds)
        assertEquals(listOf("3", "2", "1"), order.orderedIds)
    }

    @Test
    fun moveOfUnknownStationIsIgnored() {
        val order = StationOrder().move(stationId = "ghost", destination = 0, currentOrder = listOf("1", "2"))
        assertTrue(order.orderedIds.isEmpty())
    }

    @Test
    fun serializableRoundTrip() {
        val order = StationOrder(orderedIds = listOf("a", "b"))
        val data = Json.encodeToString(order)
        val restored = Json.decodeFromString<StationOrder>(data)
        assertEquals(order, restored)
        assertFalse(restored.isEmpty)
    }
}
