//
//  FeedbackTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/FeedbackTests.swift — `station.addFeedback` /
//  `station.deleteFeedback` and the bookmark endpoints (`user.getBookmarks`,
//  `bookmark.add*Bookmark`, `bookmark.delete*Bookmark`) — request shapes
//  and reply decoding.
//

package pandoracore

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackTests {

    // MARK: Rating

    @Test
    fun addFeedbackSendsTokenStationAndPositiveFlag() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("""{"feedbackId":"FB-1"}"""),
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val receipt = subject.addFeedback(
            trackToken = "TT-1",
            stationToken = "ST-1",
            rating = FeedbackRating.loved,
        )
        assertEquals("FB-1", receipt.feedbackId)

        val requests = transport.requests
        assertEquals("partner, user, addFeedback", 3, requests.size)
        val request = requests.last()
        assertTrue(request.url.contains("method=station.addFeedback"))
        val body = decryptedBody(request)
        assertTrue(body, body.contains("\"trackToken\":\"TT-1\""))
        assertTrue(body, body.contains("\"stationToken\":\"ST-1\""))
        assertTrue(body, body.contains("\"isPositive\":true"))
    }

    @Test
    fun thumbsDownSendsIsPositiveFalse() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("""{"feedbackId":"FB-2"}"""),
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        subject.addFeedback(
            trackToken = "TT-2",
            stationToken = "ST-1",
            rating = FeedbackRating.banned,
        )
        val body = decryptedBody(transport.requests.last())
        assertTrue(body, body.contains("\"isPositive\":false"))
    }

    @Test
    fun addFeedbackReplyWithoutIdIsMalformed() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"),
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val e = org.junit.Assert.assertThrows(PandoraMalformedResponseError::class.java) {
            kotlinx.coroutines.runBlocking {
                subject.addFeedback(trackToken = "TT", stationToken = "ST", rating = FeedbackRating.loved)
            }
        }
        assertEquals("addFeedback reply missing feedbackId", e.detail)
    }

    @Test
    fun deleteFeedbackSendsFeedbackIdAndStationToken() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"),
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        subject.deleteFeedback(feedbackId = "FB-1", stationToken = "ST-1")

        val requests = transport.requests
        assertEquals(3, requests.size)
        val request = requests.last()
        assertTrue(request.url.contains("method=station.deleteFeedback"))
        val body = decryptedBody(request)
        assertTrue(body, body.contains("\"feedbackId\":\"FB-1\""))
        assertTrue(body, body.contains("\"stationToken\":\"ST-1\""))
    }

    // MARK: Rating a song that has already played

    @Test
    fun rateTrackTransformsASharedStationFirst() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"), // transformSharedStation
                okJSON("""{"feedbackId":"FB-9"}"""), // addFeedback
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val result = subject.rateTrack(
            trackToken = "TT-OLD",
            station = sharedStation(),
            rating = FeedbackRating.loved,
        )

        assertEquals(FeedbackRating.loved, result.rating)
        assertEquals("FB-9", result.feedbackId)

        val requests = transport.requests
        assertEquals("partner, user, transform, addFeedback", 4, requests.size)
        assertTrue(requests[2].url.contains("method=station.transformSharedStation"))
        assertTrue(requests[3].url.contains("method=station.addFeedback"))
        val transformBody = decryptedBody(requests[2])
        assertTrue(transformBody, transformBody.contains("\"stationToken\":\"ST-SHARED\""))
    }

    @Test
    fun rateTrackDoesNotTransformAPersonalStation() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("""{"feedbackId":"FB-9"}"""),
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        subject.rateTrack(
            trackToken = "TT-OLD",
            station = personalStation(),
            rating = FeedbackRating.loved,
        )

        val requests = transport.requests
        assertEquals("partner, user, addFeedback", 3, requests.size)
        assertTrue(
            "a personal station must not be transformed",
            !requests[2].url.contains("transformSharedStation"),
        )
    }

    @Test
    fun rateTrackTransformsASharedStationOnlyOnce() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"), // transform
                okJSON("""{"feedbackId":"FB-1"}"""), // first rating
                okJSON("""{"feedbackId":"FB-2"}"""), // second rating
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val station = sharedStation()
        subject.rateTrack(trackToken = "TT-1", station = station, rating = FeedbackRating.loved)
        subject.rateTrack(trackToken = "TT-1", station = station, rating = FeedbackRating.banned)

        val transforms = transport.requests.filter {
            it.url.contains("method=station.transformSharedStation")
        }
        assertEquals("the transform happens once per station", 1, transforms.size)
    }

    @Test
    fun rateTrackReplacesAnExistingRatingByItsId() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"), // deleteFeedback
                okJSON("""{"feedbackId":"FB-NEW"}"""), // addFeedback
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val result = subject.rateTrack(
            trackToken = "TT-1",
            station = personalStation(),
            rating = FeedbackRating.banned,
            knownFeedbackId = "FB-OLD",
        )

        assertEquals(FeedbackRating.banned, result.rating)
        assertEquals("FB-NEW", result.feedbackId)
        val requests = transport.requests
        assertTrue(requests[2].url.contains("method=station.deleteFeedback"))
        val deleteBody = decryptedBody(requests[2])
        assertTrue(deleteBody, deleteBody.contains("\"feedbackId\":\"FB-OLD\""))
        assertTrue(requests[3].url.contains("method=station.addFeedback"))
        val addBody = decryptedBody(requests[3])
        assertTrue(addBody, addBody.contains("\"isPositive\":false"))
    }

    @Test
    fun removingARatingWithoutAKnownIdReRatesFirstToObtainIt() = runTest {
        // Pithos' trick: add the opposite rating to learn the feedback
        // id, then delete that feedback.
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("""{"feedbackId":"FB-TEMP"}"""), // addFeedback (opposite)
                okJSON("{}"), // deleteFeedback
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val result = subject.rateTrack(
            trackToken = "TT-1",
            station = personalStation(),
            rating = FeedbackRating.none,
        )

        assertEquals(FeedbackRating.none, result.rating)
        assertNull(result.feedbackId)
        val requests = transport.requests
        assertEquals("partner, user, addFeedback, deleteFeedback", 4, requests.size)
        assertTrue(requests[2].url.contains("method=station.addFeedback"))
        assertTrue(requests[3].url.contains("method=station.deleteFeedback"))
        val deleteBody = decryptedBody(requests[3])
        assertTrue(deleteBody, deleteBody.contains("\"feedbackId\":\"FB-TEMP\""))
    }

    @Test
    fun removingARatingWithAKnownIdSkipsTheReRating() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"), // deleteFeedback only
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val result = subject.rateTrack(
            trackToken = "TT-1",
            station = personalStation(),
            rating = FeedbackRating.none,
            knownFeedbackId = "FB-OLD",
        )

        assertEquals(FeedbackRating.none, result.rating)
        assertNull(result.feedbackId)
        val requests = transport.requests
        assertEquals("partner, user, deleteFeedback", 3, requests.size)
        assertTrue(requests[2].url.contains("method=station.deleteFeedback"))
    }

    @Test
    fun sleepSongSendsTrackToken() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"),
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        subject.sleepSong(trackToken = "TT-9")

        val request = transport.requests.last()
        assertTrue(request.url.contains("method=user.sleepSong"))
        val body = decryptedBody(request)
        assertTrue(body, body.contains("\"trackToken\":\"TT-9\""))
    }

    // MARK: Bookmarks

    @Test
    fun getBookmarksParsesSongsAndArtists() = runTest {
        val store = InMemorySessionStore(record = cachedRecord())
        val transport = MockTransport(
            listOf(
                okJSON(
                    """
                    {
                      "songs": [
                        {
                          "bookmarkToken": "B-SONG",
                          "musicToken": "M-SONG",
                          "artistName": "Band",
                          "songName": "Hit",
                          "albumName": "Album",
                          "artUrl": "https://art",
                          "dateCreated": 1700000000
                        }
                      ],
                      "artists": [
                        {
                          "bookmarkToken": "B-ARTIST",
                          "musicToken": "M-ARTIST",
                          "artistName": "Other Band"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val subject = SessionManager(
            client = LegacyPandoraClient(transport = transport),
            modes = PandoraModesClient(transport = MockModesTransport(emptyList()), csrf = MockCSRF()),
            store = store,
            now = ::fixedClock,
        )
        val restored = subject.restore()
        assertTrue(restored)

        val bookmarks = subject.getBookmarks()
        assertEquals(2, bookmarks.size)

        val song = bookmarks.first { it.kind == Bookmark.Kind.song }
        assertEquals("B-SONG", song.bookmarkToken)
        assertEquals("Hit", song.title)
        assertEquals("Band", song.artistName)
        assertEquals("Album", song.albumName)
        assertEquals("https://art", song.artworkUrl)
        assertEquals(1_700_000_000L, song.createdAt?.epochSecond)

        val artist = bookmarks.first { it.kind == Bookmark.Kind.artist }
        assertEquals("B-ARTIST", artist.bookmarkToken)
        assertEquals("Other Band", artist.title)
        assertNull(artist.songName)

        val request = transport.requests.first()
        assertTrue(request.url.contains("method=user.getBookmarks"))
    }

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

    @Test
    fun addAndDeleteBookmarkEndpoints() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"), // add song bookmark
                okJSON("{}"), // add artist bookmark
                okJSON("{}"), // delete song bookmark
                okJSON("{}"), // delete artist bookmark
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        subject.addSongBookmark(trackToken = "TT")
        subject.addArtistBookmark(trackToken = "TT")
        subject.deleteSongBookmark(bookmarkToken = "B-S")
        subject.deleteArtistBookmark(bookmarkToken = "B-A")

        val requests = transport.requests
        assertEquals(6, requests.size)
        assertTrue(requests[2].url.contains("method=bookmark.addSongBookmark"))
        assertTrue(requests[3].url.contains("method=bookmark.addArtistBookmark"))
        assertTrue(requests[4].url.contains("method=bookmark.deleteSongBookmark"))
        assertTrue(requests[5].url.contains("method=bookmark.deleteArtistBookmark"))

        val addBody = decryptedBody(requests[2])
        assertTrue(addBody, addBody.contains("\"trackToken\":\"TT\""))
        val deleteBody = decryptedBody(requests[4])
        assertTrue(deleteBody, deleteBody.contains("\"bookmarkToken\":\"B-S\""))
    }

    // MARK: Helpers

    /** A station the user owns. */
    private fun personalStation() = Station(
        id = "1", token = "ST-1", name = "My Station",
        isShared = false, isQuickMix = false,
    )

    /** A shared station — one the user has not personalised yet. */
    private fun sharedStation() = Station(
        id = "2", token = "ST-SHARED", name = "Shared Station",
        isShared = true, isQuickMix = false,
    )

    private fun envelopeResult(objectJSON: String): Map<String, AnyJSON> {
        val envelope = """{"stat":"ok","result":$objectJSON}""".toByteArray()
        return ResponseEnvelope.result(envelope)
    }
}
