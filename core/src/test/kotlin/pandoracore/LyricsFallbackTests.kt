//
//  LyricsFallbackTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/LyricsFallbackTests.swift — the lyrics
//  fallback chain: lrclib's fuzzy search, lyrics.ovh, the candidate
//  scorer, and the chain that ties them together.
//
//  The response shapes are the ones the live services return (verified
//  against both services that session).
//

package pandoracore

import java.net.URI
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFallbackTests {

    private fun track(
        title: String = "Creep",
        artist: String = "Radiohead",
        album: String = "Pablo Honey",
        duration: kotlin.Int = 239,
    ) = Track(
        trackToken = "TT-1",
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        stationId = "S-1",
    )

    // MARK: Scoring

    @Test
    fun exactTitleAndArtistScoresHighest() {
        val candidate = LyricsCandidate(
            id = 1,
            trackName = "Creep",
            artistName = "Radiohead",
            duration = 239.0,
        )
        assertTrue(
            LyricsMatchScorer.score(track = track(), candidate = candidate) >=
                LyricsMatchScorer.MINIMUM_SCORE,
        )
    }

    @Test
    fun differentSongScoresBelowTheMinimum() {
        val candidate = LyricsCandidate(
            id = 2,
            trackName = "Paranoid Android",
            artistName = "Radiohead",
            duration = 383.0,
        )
        assertTrue(
            LyricsMatchScorer.score(track = track(), candidate = candidate) <
                LyricsMatchScorer.MINIMUM_SCORE,
        )
    }

    @Test
    fun sameTitleDifferentArtistIsRejected() {
        // "Brilliant" exists by several artists — the artist has to
        // decide.
        val candidate = LyricsCandidate(
            id = 3,
            trackName = "Brilliant",
            artistName = "Someone Else",
            duration = 239.0,
        )
        assertTrue(
            LyricsMatchScorer.score(
                track = track(title = "Brilliant", artist = "Radiohead"),
                candidate = candidate,
            ) < LyricsMatchScorer.MINIMUM_SCORE,
        )
    }

    @Test
    fun differentLengthIsPenalisedButStillUsable() {
        val candidate = LyricsCandidate(
            id = 4,
            trackName = "Creep",
            artistName = "Radiohead",
            duration = 242.0,
        )
        val score = LyricsMatchScorer.score(track = track(), candidate = candidate)
        assertTrue(score >= LyricsMatchScorer.MINIMUM_SCORE)
    }

    @Test
    fun normalisationIgnoresCasePunctuationAndDiacritics() {
        assertEquals("cafe", LyricsMatchScorer.normalize("Café"))
        assertEquals("a ha", LyricsMatchScorer.normalize("A-ha!"))
        assertEquals("radio head", LyricsMatchScorer.normalize("  Radio   HEAD "))
    }

    @Test
    fun decoratedTitleStillMatchesThePlainOne() {
        // "Creep (Remastered)" against "Creep" — one contains the other.
        val candidate = LyricsCandidate(
            id = 5,
            trackName = "Creep (Remastered)",
            artistName = "Radiohead",
            duration = 239.0,
        )
        assertTrue(
            LyricsMatchScorer.score(track = track(), candidate = candidate) >=
                LyricsMatchScorer.MINIMUM_SCORE,
        )
    }

    // MARK: lrclib search

    @Test
    fun searchPicksTheBestScoringCandidate() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.success(
                    searchJSON(
                        listOf(
                            candidateJSON(id = 10, track = "Paranoid Android", artist = "Radiohead", duration = 383.0),
                            candidateJSON(id = 11, track = "Creep", artist = "Radiohead", duration = 239.0),
                            candidateJSON(id = 12, track = "Creep", artist = "Someone Else", duration = 239.0),
                        ),
                    ).toByteArray(),
                ),
            ),
        )
        val client = LRCLibSearchClient(transport = transport)

        val lyrics = client.lyrics(track())

        assertEquals("the exact match must win", 11L, lyrics.trackID)
        val request = transport.requests.first()
        assertTrue(request.url.startsWith("https://lrclib.net/api/search?"))
        assertTrue(request.url.contains("track_name=Creep"))
        assertTrue(request.url.contains("artist_name=Radiohead"))
        assertNotNull(request.headers["User-Agent"])
    }

    @Test
    fun searchKeepsSyncedLyricsSynchronised() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.success(
                    searchJSON(
                        listOf(
                            candidateJSON(
                                id = 20,
                                track = "Creep",
                                artist = "Radiohead",
                                duration = 239.0,
                                synced = "[00:12.00]When you were here before\n[00:15.00]Couldn't look you in the eye",
                            ),
                        ),
                    ).toByteArray(),
                ),
            ),
        )
        val client = LRCLibSearchClient(transport = transport)

        val lyrics = client.lyrics(track())

        assertTrue(lyrics.isSynced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(12.0, lyrics.lines[0].timestamp, 0.001)
        assertEquals("When you were here before", lyrics.lines[0].text)
    }

    @Test
    fun searchWithNoUsableCandidateIsNotFound() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.success(
                    searchJSON(
                        listOf(
                            candidateJSON(id = 30, track = "Something Else", artist = "Another Band", duration = 100.0),
                        ),
                    ).toByteArray(),
                ),
            ),
        )
        val client = LRCLibSearchClient(transport = transport)

        val e = org.junit.Assert.assertThrows(LyricsError::class.java) {
            kotlinx.coroutines.runBlocking { client.lyrics(track()) }
        }
        assertEquals(LyricsError.NotFound, e)
    }

    @Test
    fun searchRetriesWhenTheServiceIsBusy() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.failure(PandoraTransportError("HTTP 503")),
                Result.success(
                    searchJSON(
                        listOf(
                            candidateJSON(id = 40, track = "Creep", artist = "Radiohead", duration = 239.0),
                        ),
                    ).toByteArray(),
                ),
            ),
        )
        val client = LRCLibSearchClient(transport = transport)

        val lyrics = client.lyrics(track())

        assertEquals(40L, lyrics.trackID)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun searchWithAnEmptyArrayIsNotFound() = runTest {
        val transport = StubGETTransport(listOf(Result.success("[]".toByteArray())))
        val client = LRCLibSearchClient(transport = transport)

        val e = org.junit.Assert.assertThrows(LyricsError::class.java) {
            kotlinx.coroutines.runBlocking { client.lyrics(track()) }
        }
        assertEquals(LyricsError.NotFound, e)
    }

    // MARK: lyrics.ovh

    @Test
    fun lyricsOvhReturnsPlainLyrics() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.success(
                    """{"lyrics":"When you were here before\nCouldn't look you in the eye"}""".toByteArray(),
                ),
            ),
        )
        val client = LyricsOvhClient(transport = transport)

        val lyrics = client.lyrics(track())

        assertFalse(lyrics.isSynced)
        assertEquals(
            "When you were here before\nCouldn't look you in the eye",
            lyrics.plainText,
        )
        val request = transport.requests.first()
        assertTrue(request.url.contains("/v1/Radiohead/Creep"))
    }

    @Test
    fun lyricsOvhEncodesASlashInTheArtistName() = runTest {
        val transport = StubGETTransport(
            listOf(Result.success("""{"lyrics":"Rock on"}""".toByteArray())),
        )
        val client = LyricsOvhClient(transport = transport)

        client.lyrics(track(title = "Back In Black", artist = "AC/DC"))

        val request = transport.requests.first()
        // The slash must be escaped, not treated as a path separator.
        assertTrue(request.url, URI(request.url).rawPath.contains("AC%2FDC"))
    }

    @Test
    fun lyricsOvhNotFoundIsTyped() = runTest {
        val transport = StubGETTransport(
            listOf(Result.failure(PandoraTransportError("HTTP 404"))),
        )
        val client = LyricsOvhClient(transport = transport)

        val e = org.junit.Assert.assertThrows(LyricsError::class.java) {
            kotlinx.coroutines.runBlocking { client.lyrics(track()) }
        }
        assertEquals(LyricsError.NotFound, e)
    }

    @Test
    fun lyricsOvhEmptyLyricsIsNotFound() = runTest {
        val transport = StubGETTransport(
            listOf(Result.success("""{"lyrics":"   "}""".toByteArray())),
        )
        val client = LyricsOvhClient(transport = transport)

        val e = org.junit.Assert.assertThrows(LyricsError::class.java) {
            kotlinx.coroutines.runBlocking { client.lyrics(track()) }
        }
        assertEquals(LyricsError.NotFound, e)
    }

    // MARK: The chain

    @Test
    fun chainFallsThroughToTheNextProvider() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.failure(PandoraTransportError("HTTP 404")), // exact match
                Result.success( // search
                    searchJSON(
                        listOf(
                            candidateJSON(id = 50, track = "Creep", artist = "Radiohead", duration = 239.0),
                        ),
                    ).toByteArray(),
                ),
            ),
        )
        val chain = LyricsProviderChain(
            listOf(
                LRCLibClient(transport = transport),
                LRCLibSearchClient(transport = transport),
            ),
        )

        val lyrics = chain.lyrics(track())

        assertEquals(50L, lyrics.trackID)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun chainReportsNotFoundWhenEveryProviderHasNothing() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.failure(PandoraTransportError("HTTP 404")),
                Result.failure(PandoraTransportError("HTTP 404")),
                Result.failure(PandoraTransportError("HTTP 404")),
            ),
        )
        val chain = LyricsProviderChain(
            listOf(
                LRCLibClient(transport = transport),
                LRCLibSearchClient(transport = transport),
                LyricsOvhClient(transport = transport),
            ),
        )

        val e = org.junit.Assert.assertThrows(LyricsError::class.java) {
            kotlinx.coroutines.runBlocking { chain.lyrics(track()) }
        }
        assertEquals(LyricsError.NotFound, e)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun chainSkipsAProviderThatReturnsEmptyLyrics() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.success(
                    ("""{"id":1,"trackName":"Creep","artistName":"Radiohead","albumName":"x","duration":239,"instrumental":false,"plainLyrics":null,"syncedLyrics":null}""").toByteArray(),
                ),
                Result.success(
                    searchJSON(
                        listOf(
                            candidateJSON(id = 60, track = "Creep", artist = "Radiohead", duration = 239.0),
                        ),
                    ).toByteArray(),
                ),
            ),
        )
        val chain = LyricsProviderChain(
            listOf(
                LRCLibClient(transport = transport),
                LRCLibSearchClient(transport = transport),
            ),
        )

        val lyrics = chain.lyrics(track())

        assertEquals(60L, lyrics.trackID)
    }

    @Test
    fun chainStopsAtTheFirstProviderThatAnswers() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.success(
                    ("""{"id":496,"trackName":"Creep","artistName":"Radiohead","albumName":"Pablo Honey","duration":239,"instrumental":false,"plainLyrics":null,"syncedLyrics":"[00:12.00]When you were here before"}""").toByteArray(),
                ),
            ),
        )
        val chain = LyricsProviderChain(
            listOf(
                LRCLibClient(transport = transport),
                LRCLibSearchClient(transport = transport),
            ),
        )

        val lyrics = chain.lyrics(track())

        assertEquals(496L, lyrics.trackID)
        assertEquals("no fallback request when the first answers", 1, transport.requests.size)
    }

    // MARK: Fixtures

    private fun candidateJSON(
        id: kotlin.Int,
        track: String,
        artist: String,
        duration: Double,
        synced: String? = null,
        plain: String? = "line one",
    ): String {
        val syncedField = synced?.let { "\"${jsonEscaped(it)}\"" } ?: "null"
        val plainField = plain?.let { "\"${jsonEscaped(it)}\"" } ?: "null"
        return """
        {"id":$id,"trackName":"$track","artistName":"$artist",
        "albumName":"Some Album","duration":$duration,"instrumental":false,
        "plainLyrics":$plainField,"syncedLyrics":$syncedField}
        """.trimIndent()
    }

    private fun searchJSON(candidates: List<String>): String =
        "[${candidates.joinToString(",")}]"
}
