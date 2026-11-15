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
}
