//
//  PlayHistoryTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/PlayHistoryTests.swift — the bounded,
//  newest-first playback history.
//

package pandoracore

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryTests {

    private fun track(token: String, title: String = "Song") = Track(
        trackToken = token,
        title = title,
        artist = "Artist",
        album = "Album",
        duration = 200,
        stationId = "1",
    )

    @Test
    fun recordPutsNewestFirst() {
        val history = PlayHistory()
        history.record(track("A"), at = Instant.ofEpochSecond(100))
        history.record(track("B"), at = Instant.ofEpochSecond(200))
        assertEquals(listOf("B", "A"), history.items.map { it.trackToken })
        assertEquals(Instant.ofEpochSecond(200), history.items[0].playedAt)
    }

    @Test
    fun repeatedTrackMovesToFrontInsteadOfDuplicating() {
        val history = PlayHistory()
        history.record(track("A"))
        history.record(track("B"))
        history.record(track("A"))
        assertEquals(listOf("A", "B"), history.items.map { it.trackToken })
        assertEquals(2, history.count)
    }

    @Test
    fun capacityDropsOldestEntries() {
        val history = PlayHistory(capacity = 3)
        for (index in 0 until 5) {
            history.record(track("T$index"))
        }
        assertEquals(3, history.count)
        assertEquals(listOf("T4", "T3", "T2"), history.items.map { it.trackToken })
    }

    @Test
    fun entryCarriesTrackMetadata() {
        val history = PlayHistory()
        history.record(track("A", title = "Hit"))
        val entry = history.items.first()
        assertEquals("Hit", entry.title)
        assertEquals("Artist", entry.artist)
        assertEquals("Album", entry.album)
    }

    @Test
    fun serializableRoundTrip() {
        val history = PlayHistory()
        history.record(track("A"), at = Instant.ofEpochSecond(123))
        val data = Json.encodeToString(history)
        val restored = Json.decodeFromString<PlayHistory>(data)
        assertEquals(history, restored)
    }

    // MARK: Rating a song that has already played

    @Test
    fun entryCarriesTheStationToken() {
        val history = PlayHistory()
        history.record(track("A"), stationToken = "ST-1")
        assertEquals("ST-1", history.items.first().stationToken)
    }

    @Test
    fun setRatingStoresAndReadsTheRating() {
        val history = PlayHistory()
        history.record(track("A"))
        assertTrue(history.setRating(FeedbackRating.loved, "A"))
        assertEquals(FeedbackRating.loved, history.rating("A"))
        assertEquals(FeedbackRating.loved, history.items.first().rating)
    }

    @Test
    fun setRatingOnAnUnknownTrackReturnsFalse() {
        val history = PlayHistory()
        history.record(track("A"))
        assertFalse(history.setRating(FeedbackRating.loved, "NOPE"))
    }

    @Test
    fun relisteningKeepsTheRatingTheUserAlreadyGave() {
        val history = PlayHistory()
        history.record(track("A"))
        history.setRating(FeedbackRating.loved, "A")
        // The track plays again — the rating belongs to the song, so it
        // must not be lost.
        history.record(track("A"))
        assertEquals(FeedbackRating.loved, history.rating("A"))
        assertEquals(1, history.count)
    }

    @Test
    fun removingARatingClearsIt() {
        val history = PlayHistory()
        history.record(track("A"))
        history.setRating(FeedbackRating.loved, "A")
        history.setRating(null, "A")
        assertNull(history.rating("A"))
    }

    @Test
    fun historyFromBeforeTheStationFieldStillDecodes() {
        // An entry written before the station token existed must still
        // decode (the field is optional).
        val json = """
            {"entries":[{"trackToken":"A","title":"S","artist":"B","album":"C",
            "playedAt":100}],"capacity":100}
        """.trimIndent()
        val restored = Json.decodeFromString<PlayHistory>(json)
        assertEquals(null, restored.items.first().stationToken)
        assertEquals(null, restored.items.first().rating)
    }
}
