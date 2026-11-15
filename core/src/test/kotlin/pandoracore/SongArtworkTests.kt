//
//  SongArtworkTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/SongArtworkTests.swift — the iTunes Search
//  artwork client, against scripted GET responses. No network in any unit
//  test.
//
//  The response shape is the one the live service returns (verified
//  against itunes.apple.com/search that session); the motivating refusal
//  case is real: a "Céline Dion" query answered with an "Olivier Dion"
//  track — and the top window is *full* of other artists for that song
//  (Floran, Olivier Dion ×2, Stromae, Rose were its first five answers
//  live). The client scans the window and runs an artist-only second
//  pass; these tests script both passes.
//

package pandoracore

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SongArtworkTests {

    // MARK: Fixtures

    /** One iTunes result as JSON, with only the fields the client
     *  reads; the API sends many more and they are ignored here too. */
    private fun resultJSON(
        artist: String?,
        track: String?,
        artwork: String? = "https://is1-ssl.mzstatic.com/image/thumb/Music/1.jpg/100x100bb.jpg",
    ): String {
        val fields = mutableListOf<String>()
        artist?.let { fields.add("\"artistName\": \"$it\"") }
        track?.let { fields.add("\"trackName\": \"$it\"") }
        artwork?.let { fields.add("\"artworkUrl100\": \"$it\"") }
        fields.add("\"collectionName\": \"An album\"")
        return "{${fields.joinToString(", ")}, \"kind\": \"song\", \"trackId\": 1}"
    }

    private fun searchJSON(results: List<String>): ByteArray =
        """{"resultCount": ${results.size}, "results": [${results.joinToString(", ")}]}""".toByteArray()

    private fun success(data: ByteArray): Result<ByteArray> = Result.success(data)

    /** A client whose first pass answers `first`. When the first pass
     *  finds nothing verified, the client runs the artist-only second
     *  pass — `second` scripts it (default: empty results). */
    private fun makeClient(
        first: Result<ByteArray>,
        second: Result<ByteArray>? = null,
    ) = ITunesSongArtworkClient(
        transport = StubGETTransport(listOf(first, second ?: success(searchJSON(emptyList())))),
    )

    private fun makeClient(responses: List<Result<ByteArray>>) = ITunesSongArtworkClient(
        transport = StubGETTransport(responses),
    )

    // MARK: The wire request

    @Test
    fun requestCarriesTermEntityAndWindow() = runTest {
        val transport = StubGETTransport(
            listOf(success(searchJSON(emptyList())), success(searchJSON(emptyList()))),
        )
        val client = ITunesSongArtworkClient(transport = transport)
        client.artwork("Céline Dion", "Tous les chemins")
        val request = transport.requests.first()
        val query = URI(request.url).rawQuery
            .split("&")
            .map { pair -> pair.split("=", limit = 2).let { it[0] to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") } }
            .toMap()
        assertEquals("Céline Dion Tous les chemins", query["term"])
        assertEquals("song", query["entity"])
        assertEquals("25", query["limit"])
        assertEquals("itunes.apple.com", URI(request.url).host)
        assertEquals("/search", URI(request.url).path)
        // The lookup sends no headers.
        assertTrue(request.headers.isEmpty())
    }

    // MARK: The happy path

    @Test
    fun matchingResultYieldsArtwork() = runTest {
        val client = makeClient(
            success(
                searchJSON(
                    listOf(
                        resultJSON(
                            artist = "Céline Dion",
                            track = "Tous les chemins mènent à l'amour",
                        ),
                    ),
                ),
            ),
        )
        val artwork = client.artwork(
            artist = "Céline Dion",
            title = "Tous les chemins mènent à l'amour",
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/1.jpg/100x100bb.jpg",
            artwork?.artworkURL,
        )
    }

    @Test
    fun verifiedMatchBelowTheTopResultIsFound() = runTest {
        // The live case: the top of the window is other artists' same-
        // titled songs; the asked-for song sits further down. The scan
        // must find it — and stop after the first pass.
        val client = makeClient(
            success(
                searchJSON(
                    listOf(
                        resultJSON(artist = "Floran", track = "Tous les chemins mènent à toi"),
                        resultJSON(artist = "Olivier Dion", track = "Tous les chemins"),
                        resultJSON(artist = "Céline Dion", track = "Tous les chemins mènent à l'amour"),
                    ),
                ),
            ),
        )
        val artwork = client.artwork(
            artist = "Céline Dion",
            title = "Tous les chemins mènent à l'amour",
        )
        assertNotNull(artwork)
    }

    @Test
    fun artistOnlySecondPassFindsTheSongTheCombinedQueryMissed() = runTest {
        val stub = StubGETTransport(
            listOf(
                success(
                    searchJSON(
                        listOf(
                            resultJSON(artist = "Olivier Dion", track = "Tous les chemins mènent à l'amour"),
                        ),
                    ),
                ),
                success(
                    searchJSON(
                        listOf(
                            resultJSON(artist = "Céline Dion", track = "Tous les chemins mènent à l'amour"),
                        ),
                    ),
                ),
            ),
        )
        val client = ITunesSongArtworkClient(transport = stub)
        val artwork = client.artwork(
            artist = "Céline Dion",
            title = "Tous les chemins mènent à l'amour",
        )
        assertNotNull(artwork)
        // Two requests: the combined term, then the artist alone.
        assertEquals(2, stub.requests.size)
        val second = URI(stub.requests[1].url)
        val secondTerm = second.rawQuery.split("&")
            .map { pair -> pair.split("=", limit = 2).let { it[0] to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") } }
            .toMap()["term"]
        assertEquals("Céline Dion", secondTerm)
    }

    // MARK: Size tokens

    @Test
    fun largeArtworkReplacesTheSizeToken() {
        val artwork = SongArtwork(
            artworkURL = "https://is1-ssl.mzstatic.com/image/thumb/Music/1.jpg/100x100bb.jpg",
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/1.jpg/600x600bb.jpg",
            artwork.largeArtworkURL,
        )
    }

    @Test
    fun artworkWithoutSizeTokenIsReturnedUnchanged() {
        val url = "https://example.com/cover.jpg"
        val artwork = SongArtwork(artworkURL = url)
        assertEquals(url, artwork.largeArtworkURL)
    }

    // MARK: The verification — a wrong answer yields no artwork

    @Test
    fun wrongArtistIsRefused() = runTest {
        // The real case: the fuzzy search answered a "Céline Dion"
        // query with an "Olivier Dion" track — and nothing else in
        // either pass is the asked-for song. No artwork, never a
        // wrong cover.
        val client = makeClient(
            success(
                searchJSON(
                    listOf(
                        resultJSON(artist = "Olivier Dion", track = "Tous les chemins mènent à l'amour"),
                    ),
                ),
            ),
            second = success(
                searchJSON(
                    listOf(
                        resultJSON(artist = "Olivier Dion", track = "Tous les chemins mènent à l'amour"),
                    ),
                ),
            ),
        )
        val artwork = client.artwork(
            artist = "Céline Dion",
            title = "Tous les chemins mènent à l'amour",
        )
        assertNull(artwork)
    }

    @Test
    fun differentTitleIsRefused() = runTest {
        val client = makeClient(
            success(
                searchJSON(
                    listOf(
                        resultJSON(artist = "Céline Dion", track = "Un tout autre titre"),
                    ),
                ),
            ),
        )
        val artwork = client.artwork(artist = "Céline Dion", title = "Tous les chemins")
        assertNull(artwork)
    }

    @Test
    fun guestArtistIsAccepted() = runTest {
        // Stations announce the guests ("Céline Dion feat. …") — the
        // primary-artist comparison accepts it, the guest is not
        // promoted to the artist.
        val client = makeClient(
            success(
                searchJSON(
                    listOf(
                        resultJSON(artist = "Céline Dion feat. a stranger", track = "Tous les chemins mènent à l'amour"),
                    ),
                ),
            ),
        )
        val artwork = client.artwork(
            artist = "Céline Dion",
            title = "Tous les chemins mènent à l'amour",
        )
        assertNotNull(artwork)
    }

    @Test
    fun diacriticsAndCaseDoNotRefuse() = runTest {
        // "Céline" vs "celine", "Beyoncé" vs "beyonce" — same artist.
        val client = makeClient(
            success(searchJSON(listOf(resultJSON(artist = "beyonce", track = "Halo")))),
        )
        val artwork = client.artwork(artist = "Beyoncé", title = "Halo")
        assertNotNull(artwork)
    }

    @Test
    fun titleVariantIsAcceptedByLooseContainment() = runTest {
        // Stations announce "Title (Radio Edit)" / "Title - Live"; the
        // store may carry the plain form. Either containment matches.
        val decorated = makeClient(
            success(
                searchJSON(
                    listOf(
                        resultJSON(artist = "Céline Dion", track = "Tous les chemins (Radio Edit)"),
                    ),
                ),
            ),
        )
        val variant = decorated.artwork(artist = "Céline Dion", title = "Tous les chemins")
        assertNotNull(variant)

        val reversed = makeClient(
            success(
                searchJSON(
                    listOf(
                        resultJSON(artist = "Céline Dion", track = "Tous les chemins"),
                    ),
                ),
            ),
        )
        val other = reversed.artwork(
            artist = "Céline Dion",
            title = "Tous les chemins mènent à l'amour",
        )
        assertNotNull(other)
    }

    // MARK: Empty / broken answers

    @Test
    fun noResultsYieldsNoArtwork() = runTest {
        val client = makeClient(success(searchJSON(emptyList())))
        val artwork = client.artwork(artist = "Nobody", title = "Nothing")
        assertNull(artwork)
    }

    @Test
    fun resultWithoutArtworkURLYieldsNoArtwork() = runTest {
        val client = makeClient(
            success(
                searchJSON(
                    listOf(
                        resultJSON(artist = "Céline Dion", track = "Halo", artwork = null),
                    ),
                ),
            ),
        )
        val artwork = client.artwork(artist = "Céline Dion", title = "Halo")
        assertNull(artwork)
    }

    @Test
    fun resultWithoutArtistOrTrackFieldsIsRefused() = runTest {
        val client = makeClient(
            success(searchJSON(listOf(resultJSON(artist = null, track = null)))),
        )
        val artwork = client.artwork(artist = "Céline Dion", title = "Halo")
        assertNull(artwork)
    }

    @Test
    fun malformedJSONThrows() {
        val client = makeClient(success("<html>503</html>".toByteArray()))
        val e = assertThrows(SongArtworkError::class.java) {
            runBlocking { client.artwork(artist = "Céline Dion", title = "Halo") }
        }
        assertEquals(SongArtworkError.MalformedResponse, e)
    }

    @Test
    fun transportFailurePropagates() {
        val client = makeClient(Result.failure(PandoraTransportError("network failure")))
        val e = assertThrows(PandoraTransportError::class.java) {
            runBlocking { client.artwork(artist = "Céline Dion", title = "Halo") }
        }
        assertEquals("network failure", e.reason)
    }

    // MARK: The normalization itself

    @Test
    fun normalizationFoldsDiacriticsCaseAndWhitespace() {
        assertEquals("celine dion", ITunesSongArtworkClient.normalized("  Céline  DION "))
        assertEquals("beyonce", ITunesSongArtworkClient.normalized("Beyoncé"))
        assertEquals("bjork", ITunesSongArtworkClient.normalized("Björk"))
    }

    @Test
    fun primaryArtistCutsTheGuests() {
        assertEquals(
            "celine dion",
            ITunesSongArtworkClient.primaryArtist("celine dion feat. a stranger"),
        )
        assertEquals("milow", ITunesSongArtworkClient.primaryArtist("milow & anouk"))
        assertEquals("k's choice", ITunesSongArtworkClient.primaryArtist("k's choice featuring sarah"))
    }

    @Test
    fun primaryArtistNeverCutsAnArtistAway() {
        // An artist *named* by a marker word keeps its full name.
        assertEquals("with", ITunesSongArtworkClient.primaryArtist("with"))
        assertEquals("feat", ITunesSongArtworkClient.primaryArtist("feat"))
    }
}
