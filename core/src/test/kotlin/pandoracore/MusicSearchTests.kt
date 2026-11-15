//
//  MusicSearchTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/MusicSearchTests.swift — `music.search`
//  parsing (pithos filter ≥ 80 / genre pass-through / stable score-desc
//  order) and the SessionManager request shape.
//
//  testSearchSendsPithosParamsUnderRestoredSession drives SessionManager;
//  it ports with the SessionManager unit.
//

package pandoracore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class MusicSearchTests {

    // MARK: Parsing (pure)

    @Test
    fun filtersLowScoresAndSortsDescending() {
        val result = envelopeResult(
            """{"artists":[{"artistName":"Low","musicToken":"m1","score":70},{"artistName":"High","musicToken":"m2","score":90}],""" +
                """"songs":[{"songName":"Hit","artistName":"Band","musicToken":"m3","score":95},{"songName":"Edge","artistName":"B2","musicToken":"m4","score":80}],""" +
                """"genreStations":[{"stationName":"Rock","musicToken":"m5","score":50}]}""",
        )
        val hits = MusicSearchResult.from(result)
        // ≥80 for artist/song; genre passes regardless of score.
        assertEquals(listOf("m3", "m2", "m4", "m5"), hits.map { it.musicToken })
        assertEquals(
            listOf(
                MusicSearchResult.Kind.song,
                MusicSearchResult.Kind.artist,
                MusicSearchResult.Kind.song,
                MusicSearchResult.Kind.genre,
            ),
            hits.map { it.kind },
        )
    }

    @Test
    fun scoreTiesKeepArtistThenSongThenGenreOrder() {
        // Python's list.sort is stable; equal scores must preserve the
        // artists → songs → genreStations merge order.
        val result = envelopeResult(
            """{"artists":[{"artistName":"A","musicToken":"a","score":90}],""" +
                """"songs":[{"songName":"S","artistName":"X","musicToken":"s","score":90}],""" +
                """"genreStations":[{"stationName":"G","musicToken":"g","score":90}]}""",
        )
        val hits = MusicSearchResult.from(result)
        assertEquals(listOf("a", "s", "g"), hits.map { it.musicToken })
    }

    @Test
    fun fieldMappingPerKind() {
        val result = envelopeResult(
            """{"artists":[{"artistName":"Weezer","musicToken":"ma","score":88}],""" +
                """"songs":[{"songName":"Buddy Holly","artistName":"Weezer","musicToken":"ms","score":99}],""" +
                """"genreStations":[{"stationName":"90s Alternative","musicToken":"mg","score":10}]}""",
        )
        val hits = MusicSearchResult.from(result)
        assertEquals(3, hits.size)

        val artist = hits.first { it.kind == MusicSearchResult.Kind.artist }
        assertEquals("Weezer", artist.title)
        assertNull(artist.subtitle)

        val song = hits.first { it.kind == MusicSearchResult.Kind.song }
        assertEquals("Buddy Holly", song.title)
        assertEquals("Weezer", song.subtitle)

        val genre = hits.first { it.kind == MusicSearchResult.Kind.genre }
        assertEquals("90s Alternative", genre.title)
    }

    @Test
    fun malformedEntriesAreSkippedNotFatal() {
        // One field missing on each — pithos would KeyError; we drop the
        // entry and keep the well-formed one.
        val result = envelopeResult(
            """{"artists":[{"artistName":"NoToken","score":99},{"artistName":"OK","musicToken":"keep","score":85}],""" +
                """"songs":[{"songName":"NoScore","artistName":"X","musicToken":"t"}],""" +
                """"genreStations":[{"stationName":"NoToken","score":90}]}""",
        )
        val hits = MusicSearchResult.from(result)
        assertEquals(listOf("keep"), hits.map { it.musicToken })
    }

    @Test
    fun emptyResultIsEmptyList() {
        val hits = MusicSearchResult.from(envelopeResult("{}"))
        assertEquals(emptyList<MusicSearchResult>(), hits)
    }

    // MARK: Request shape (SessionManager)

    @Test
    fun searchSendsPithosParamsUnderRestoredSession() = kotlinx.coroutines.test.runTest {
        // Restore-from-store path: no login traffic, exactly one search call.
        val store = InMemorySessionStore(record = cachedRecord(token = "U-TOKEN"))
        val transport = MockTransport(
            listOf(
                okJSON(
                    """{"artists":[{"artistName":"Weezer","musicToken":"m-1","score":91}],"songs":[],"genreStations":[]}""",
                ),
            ),
        )
        val manager = SessionManager(
            client = LegacyPandoraClient(transport = transport),
            modes = PandoraModesClient(transport = MockModesTransport(emptyList()), csrf = MockCSRF()),
            store = store,
            now = ::fixedClock,
        )
        assertTrue(manager.restore())

        val hits = manager.search("weezer")
        assertEquals(listOf("m-1"), hits.map { it.musicToken })

        val requests = transport.requests
        assertEquals(1, requests.size)
        val request = requests.first()
        assertTrue(request.url.contains("method=music.search"))
        assertTrue(request.url.contains("auth_token=U-TOKEN"))

        // Encrypted body: pithos' exact param set, NUL-pad trimmed.
        val hex = request.body.decodeToString()
        val plain = PandoraCrypto.decryptFromHex(hex, PandoraAPI.encryptKey)
        val body = plain.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
        assertTrue(body, body.contains("\"includeGenreStations\":true"))
        assertTrue(body, body.contains("\"includeNearMatches\":true"))
        assertTrue(body, body.contains("\"searchText\":\"weezer\""))
    }

    // MARK: Helpers

    private fun envelopeResult(objectJSON: String): Map<String, AnyJSON> {
        val envelope = """{"stat":"ok","result":$objectJSON}""".toByteArray()
        return ResponseEnvelope.result(envelope)
    }
}
