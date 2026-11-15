//
//  SkipLimitTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/SkipLimitTests.swift — Pandora's daily skip
//  limit. The legacy API signals it by fault **message** on
//  `station.getPlaylist`, not by a numeric code — the reference (Elpis
//  `Libs/PandoraSharp/Station.cs:232`) compares the exception message
//  against "PLAYLIST_END" and "DAILY_SKIP_LIMIT_REACHED", and pithos has
//  no special case at all (its `json_call` raises `ApiError` carrying the
//  server's message).
//
//  The core turns those faults into a typed `PlaylistError`, so the app
//  can tell the user what happened and stop asking — AGENTS.md:
//  "Respect skip limits and rate limits. Never hammer the API."
//
//  The SessionManager-driven tests port with that unit:
//  testGetPlaylistMapsDailySkipLimitToTypedError,
//  testGetPlaylistMapsPlaylistEndToTypedError,
//  testOtherPlaylistFaultsStayAPandoraAPIError, testSkipLimitIsNotRetried.
//

package pandoracore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipLimitTests {

    // MARK: Message detection

    @Test
    fun dailySkipLimitMessageIsRecognised() {
        val error = PandoraAPIError(code = 1004, message = "DAILY_SKIP_LIMIT_REACHED")
        assertTrue(error.isSkipLimitReached)
        assertFalse(error.isPlaylistEnd)
    }

    @Test
    fun playlistEndMessageIsRecognised() {
        val error = PandoraAPIError(code = 1004, message = "PLAYLIST_END")
        assertTrue(error.isSkipLimitReached)
        assertTrue(error.isPlaylistEnd)
    }

    @Test
    fun skipLimitDetectionToleratesCaseAndWhitespace() {
        val error = PandoraAPIError(code = 1004, message = "  daily_skip_limit_reached \n")
        assertTrue(error.isSkipLimitReached)
    }

    @Test
    fun skipLimitDetectionAcceptsAMessageWithContext() {
        // The server may append its own sentence to the token.
        val error = PandoraAPIError(
            code = 1004,
            message = "DAILY_SKIP_LIMIT_REACHED: try again tomorrow",
        )
        assertTrue(error.isSkipLimitReached)
    }

    @Test
    fun unrelatedFaultsAreNotSkipLimits() {
        val expired = PandoraAPIError(code = 1001, message = "Invalid auth token")
        assertFalse(expired.isSkipLimitReached)
        assertFalse(expired.isPlaylistEnd)

        val other = PandoraAPIError(code = 1039, message = "Too many playlist requests")
        assertFalse(other.isSkipLimitReached)
        assertFalse(other.isPlaylistEnd)
    }

    // MARK: Typed mapping

    @Test
    fun playlistEndedAccessorDistinguishesTheTwoSignals() {
        assertTrue(PlaylistError.SkipLimitReached(playlistEnded = true).playlistEnded)
        assertFalse(PlaylistError.SkipLimitReached(playlistEnded = false).playlistEnded)
    }

    // MARK: The getPlaylist mapping (SessionManager)

    /** A restored session — no login traffic, so the first response is
     *  the playlist request's. */
    private fun makeManager(transport: MockTransport): SessionManager {
        val store = InMemorySessionStore(record = cachedRecord(token = "U-LISTED"))
        return SessionManager(
            client = LegacyPandoraClient(transport = transport),
            modes = PandoraModesClient(transport = MockModesTransport(emptyList()), csrf = MockCSRF()),
            store = store,
            now = ::fixedClock,
        )
    }

    private fun makeStation() = Station(
        id = "1", token = "S1", name = "Deep Cuts",
        isShared = false, isQuickMix = false,
    )

    @Test
    fun getPlaylistMapsDailySkipLimitToTypedError() = kotlinx.coroutines.test.runTest {
        val transport = MockTransport(listOf(failJSON(1004, "DAILY_SKIP_LIMIT_REACHED")))
        val subject = makeManager(transport)
        assertTrue(subject.restore())

        val e = org.junit.Assert.assertThrows(PlaylistError::class.java) {
            kotlinx.coroutines.runBlocking { subject.getPlaylist(makeStation()) }
        }
        assertTrue(e is PlaylistError.SkipLimitReached)
        assertFalse((e as PlaylistError.SkipLimitReached).playlistEnded)
    }

    @Test
    fun getPlaylistMapsPlaylistEndToTypedError() = kotlinx.coroutines.test.runTest {
        val transport = MockTransport(listOf(failJSON(1004, "PLAYLIST_END")))
        val subject = makeManager(transport)
        assertTrue(subject.restore())

        val e = org.junit.Assert.assertThrows(PlaylistError::class.java) {
            kotlinx.coroutines.runBlocking { subject.getPlaylist(makeStation()) }
        }
        assertTrue(e is PlaylistError.SkipLimitReached)
        assertTrue((e as PlaylistError.SkipLimitReached).playlistEnded)
    }

    @Test
    fun otherPlaylistFaultsStayAPandoraAPIError() = kotlinx.coroutines.test.runTest {
        val transport = MockTransport(listOf(failJSON(1039, "Too many playlist requests")))
        val subject = makeManager(transport)
        assertTrue(subject.restore())

        val e = org.junit.Assert.assertThrows(PandoraAPIError::class.java) {
            kotlinx.coroutines.runBlocking { subject.getPlaylist(makeStation()) }
        }
        assertEquals(1039, e.code)
        assertFalse(e.isSkipLimitReached)
    }

    @Test
    fun skipLimitIsNotRetried() = kotlinx.coroutines.test.runTest {
        // The request must be made exactly once: the server refuses it,
        // and hammering it would only deepen the limit. (Compare with
        // the 1001 contract, where one replay is correct.)
        val transport = MockTransport(listOf(failJSON(1004, "DAILY_SKIP_LIMIT_REACHED")))
        val subject = makeManager(transport)
        assertTrue(subject.restore())

        runCatching { kotlinx.coroutines.runBlocking { subject.getPlaylist(makeStation()) } }
        assertEquals(1, transport.requests.size)
    }
}
