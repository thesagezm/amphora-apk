//
//  FeedbackTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/FeedbackTests.swift — `station.addFeedback` /
//  `station.deleteFeedback` and the bookmark endpoints (`user.getBookmarks`,
//  `bookmark.add*Bookmark`, `bookmark.delete*Bookmark`) — request shapes and
//  reply decoding.
//
//  SessionManager-driven tests (login flow, rateTrack, restore) port with
//  the SessionManager unit: testAddFeedbackSendsTokenStationAndPositiveFlag,
//  testThumbsDownSendsIsPositiveFalse, testAddFeedbackReplyWithoutIdIsMalformed,
//  testDeleteFeedbackSendsFeedbackIdAndStationToken,
//  testRateTrackTransformsASharedStationFirst,
//  testRateTrackDoesNotTransformAPersonalStation,
//  testRateTrackTransformsASharedStationOnlyOnce,
//  testRateTrackReplacesAnExistingRatingByItsId,
//  testRemovingARatingWithoutAKnownIdReRatesFirstToObtainIt,
//  testRemovingARatingWithAKnownIdSkipsTheReRating,
//  testSleepSongSendsTrackToken, testGetBookmarksParsesSongsAndArtists,
//  testAddAndDeleteBookmarkEndpoints.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackTests {

    @Test
    fun malformedBookmarkEntriesAreSkipped() {
        val result = envelopeResult(
            """
            {
              "songs": [
                { "musicToken": "M" },
                { "bookmarkToken": "B", "musicToken": "M2", "artistName": "A" }
              ],
              "artists": []
            }
            """.trimIndent(),
        )
        val bookmarks = Bookmark.from(result)
        assertEquals(listOf("B"), bookmarks.map { it.bookmarkToken })
    }

    // MARK: Helpers

    private fun envelopeResult(objectJSON: String): Map<String, AnyJSON> {
        val envelope = """{"stat":"ok","result":$objectJSON}""".toByteArray()
        return ResponseEnvelope.result(envelope)
    }
}
