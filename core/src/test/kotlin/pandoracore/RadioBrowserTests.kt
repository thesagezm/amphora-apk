//
//  RadioBrowserTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/RadioBrowserTests.swift — the radio-browser
//  directory client: model decoding from captured fixture shapes,
//  request assembly, mirror rotation on failure, and error mapping.
//  No network in any test.
//

package pandoracore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioBrowserTests {

    // MARK: Fixtures

    private val stationsFixture = """
        [
          {
            "changeuuid": "c1",
            "stationuuid": "11111111-1111-1111-1111-111111111111",
            "name": "Jazz Radio",
            "url": "http://example.com/stream.pls",
            "url_resolved": "http://example.com/stream.mp3",
            "homepage": "http://example.com",
            "favicon": "http://example.com/icon.png",
            "tags": "jazz,smooth jazz, saxophone",
            "country": "Germany",
            "countrycode": "DE",
            "state": "",
            "language": "german",
            "votes": 4211,
            "clicks": 98077,
            "codec": "MP3",
            "bitrate": 128
          },
          {
            "stationuuid": "22222222-2222-2222-2222-222222222222",
            "name": "News Talk",
            "url": "https://news.example/live",
            "url_resolved": "",
            "homepage": null,
            "favicon": null,
            "tags": "",
            "country": "",
            "countrycode": "us",
            "language": null,
            "votes": "12",
            "clicks": null,
            "codec": "AAC",
            "bitrate": 64
          },
          { "stationuuid": "", "name": "No uuid" },
          { "stationuuid": "3", "name": "" },
          { "stationuuid": "4", "name": "No stream", "url_resolved": "" }
        ]
    """.trimIndent().toByteArray()

    private val countriesFixture = """
        [
          { "name": "Germany", "iso_3166_1": "DE", "stationcount": 3200 },
          { "name": "United States", "iso_3166_1": "US", "stationcount": 6100 }
        ]
    """.trimIndent().toByteArray()

    private val tagsFixture = """
        [
          { "name": "pop", "stationcount": 9100 },
          { "name": "jazz", "stationcount": 2400 }
        ]
    """.trimIndent().toByteArray()

    // MARK: Model decoding

    @Test
    fun stationsDecodeAndSkipMalformedEntries() {
        val stations = RadioStation.fromData(stationsFixture)
        assertEquals(2, stations.size)

        val jazz = stations[0]
        assertEquals("11111111-1111-1111-1111-111111111111", jazz.uuid)
        assertEquals("Jazz Radio", jazz.name)
        assertEquals("http://example.com/stream.mp3", jazz.streamURL)
        assertEquals(listOf("jazz", "smooth jazz", "saxophone"), jazz.tags)
        assertEquals("Germany", jazz.country)
        assertEquals("DE", jazz.countryCode)
        assertEquals(128, jazz.bitrate)
        assertEquals(4211, jazz.votes)
        assertEquals(98077, jazz.clicks)

        // A gapless entry: no resolved URL falls back to the submitted
        // one; numeric strings decode; nulls stay null.
        val news = stations[1]
        assertEquals("https://news.example/live", news.streamURL)
        assertEquals("US", news.countryCode)
        assertEquals(12, news.votes)
        assertEquals(0, news.clicks)
        assertNull(news.language)
        assertNull(news.faviconURL)
    }

    @Test
    fun garbageBodyYieldsNoStations() {
        assertEquals(emptyList<RadioStation>(), RadioStation.fromData("not json".toByteArray()))
        assertEquals(emptyList<RadioStation>(), RadioStation.fromData("[]".toByteArray()))
    }

    @Test
    fun captionJoinsKnownFacts() {
        val known = RadioStation(
            uuid = "1", name = "A", streamURL = "s",
            country = "Germany", codec = "MP3", bitrate = 128,
        )
        assertEquals("Germany · MP3 · 128 kbps", known.caption)

        val bare = RadioStation(uuid = "2", name = "B", streamURL = "s")
        assertEquals("", bare.caption)
    }

    @Test
    fun countriesAndTagsDecode() = runTest {
        val transport = ScriptedTransport(
            listOf(
                "/json/countries" to Result.success(countriesFixture),
                "/json/tags" to Result.success(tagsFixture),
            ),
        )
        val api = client(transport)
        val countries = api.countries()
        assertEquals(listOf("DE", "US"), countries.map { it.code })
        assertEquals("Germany", countries[0].name)
        assertEquals(3200, countries[0].stationCount)

        val tags = api.tags()
        assertEquals(listOf("pop", "jazz"), tags.map { it.name })
        assertEquals(2400, tags[1].stationCount)
    }

    // MARK: Client behavior

    /** A scripted GET transport: per-URL outcomes plus recording. */
    private class ScriptedTransport(
        private val outcomes: List<Pair<String, Result<ByteArray>>>,
    ) : RadioBrowserTransport {
        private val lock = Any()
        private val used = mutableListOf<String>()

        val requestedURLs: List<String>
            get() = synchronized(lock) { used.toList() }

        override suspend fun get(url: String): ByteArray {
            val outcome = synchronized(lock) {
                used.add(url)
                outcomes.firstOrNull { url.contains(it.first) }
            }
            return outcome?.second?.getOrThrow() ?: throw RadioBrowserError.NetworkFailure
        }
    }

    private fun client(transport: ScriptedTransport) = RadioBrowserClient(
        transport = transport,
        mirrors = listOf(
            RadioBrowserClient.Mirror(baseURL = "https://one.api.radio-browser.info"),
            RadioBrowserClient.Mirror(baseURL = "https://two.api.radio-browser.info"),
        ),
    )

    @Test
    fun searchBuildsVotesOrderedQuery() = runTest {
        val transport = ScriptedTransport(
            listOf("stations/search" to Result.success("[]".toByteArray())),
        )
        val stations = client(transport).searchStations(name = "jazz fm")
        assertTrue(stations.isEmpty())

        val url = transport.requestedURLs.first()
        assertTrue(url.startsWith("https://one.api.radio-browser.info/json/stations/search?"))
        assertTrue(url.contains("name=jazz%20fm"))
        assertTrue(url.contains("order=votes"))
        assertTrue(url.contains("reverse=true"))
        assertTrue(url.contains("hidebroken=true"))
        assertTrue(url.contains("limit=50"))
    }

    @Test
    fun emptySearchNameSkipsTheNetwork() = runTest {
        val transport = ScriptedTransport(emptyList())
        val stations = client(transport).searchStations(name = "   ")
        assertTrue(stations.isEmpty())
        assertTrue(transport.requestedURLs.isEmpty())
    }

    @Test
    fun mirrorRotationSurvivesADeadMirror() = runTest {
        val transport = ScriptedTransport(
            listOf(
                "one.api.radio-browser.info" to Result.failure(RadioBrowserError.NetworkFailure),
                "topvote" to Result.success(stationsFixture),
            ),
        )
        val stations = client(transport).topStations(limit = 25)
        assertEquals(2, stations.size)
        assertEquals(2, transport.requestedURLs.size)
        assertTrue(transport.requestedURLs[0].contains("one.api"))
        assertTrue(transport.requestedURLs[1].contains("two.api"))
        assertTrue(transport.requestedURLs[1].contains("/json/stations/topvote/25"))
    }

    @Test
    fun failedMirrorIsNotRetriedFirstNextCall() = runTest {
        val transport = ScriptedTransport(
            listOf(
                "one.api.radio-browser.info" to Result.failure(RadioBrowserError.NetworkFailure),
                "topvote" to Result.success("[]".toByteArray()),
                "two.api.radio-browser.info" to Result.success("[]".toByteArray()),
            ),
        )
        val api = client(transport)
        api.topStations() // one dead → two answers
        api.topStations() // two is tried directly
        assertEquals(3, transport.requestedURLs.size)
        assertTrue(transport.requestedURLs[2].contains("two.api"))
    }

    @Test
    fun allMirrorsDownSurfacesTypedError() = runTest {
        val transport = ScriptedTransport(
            listOf(
                "one.api" to Result.failure(RadioBrowserError.HttpStatus(503)),
                "two.api" to Result.failure(RadioBrowserError.HttpStatus(503)),
            ),
        )
        val e = org.junit.Assert.assertThrows(RadioBrowserError::class.java) {
            kotlinx.coroutines.runBlocking { client(transport).topStations() }
        }
        assertEquals(RadioBrowserError.HttpStatus(503), e)
    }

    @Test
    fun countryAndTagPathsArePercentEncoded() = runTest {
        val transport = ScriptedTransport(
            listOf(
                "bycountrycodeexact" to Result.success("[]".toByteArray()),
                "bytag" to Result.success("[]".toByteArray()),
            ),
        )
        val api = client(transport)
        api.stationsByCountryCode(countryCode = "de")
        api.stationsByTag(tag = "smooth jazz")
        assertTrue(transport.requestedURLs.first().contains("/json/stations/bycountrycodeexact/DE?"))
        assertTrue(transport.requestedURLs.last().contains("/json/stations/bytag/smooth%20jazz?"))
    }

    @Test
    fun clickPingHitsTheURLPath() = runTest {
        val transport = ScriptedTransport(
            listOf("/json/url/" to Result.success("""{"name":"ok","url":"ok"}""".toByteArray())),
        )
        client(transport).registerClick(uuid = "11111111-1111-1111-1111-111111111111")
        val url = transport.requestedURLs.first()
        assertTrue(url.contains("/json/url/11111111-1111-1111-1111-111111111111"))
    }

    // MARK: Paging (offset)

    @Test
    fun offsetPagesEveryListEndpoint() = runTest {
        val transport = ScriptedTransport(
            listOf(
                "topvote" to Result.success("[]".toByteArray()),
                "bycountrycodeexact" to Result.success("[]".toByteArray()),
                "bytag" to Result.success("[]".toByteArray()),
                "stations/search" to Result.success("[]".toByteArray()),
            ),
        )
        val api = client(transport)
        api.topStations(offset = 50)
        api.stationsByCountryCode(countryCode = "DE", offset = 100)
        api.stationsByTag(tag = "jazz", offset = 25)
        api.searchStations(name = "jazz", offset = 75)
        assertTrue(transport.requestedURLs[0].contains("offset=50"))
        assertTrue(transport.requestedURLs[1].contains("offset=100"))
        assertTrue(transport.requestedURLs[2].contains("offset=25"))
        assertTrue(transport.requestedURLs[3].contains("offset=75"))
    }

    @Test
    fun firstPageOmitsTheOffset() = runTest {
        val transport = ScriptedTransport(
            listOf(
                "topvote" to Result.success("[]".toByteArray()),
                "bycountrycodeexact" to Result.success("[]".toByteArray()),
            ),
        )
        val api = client(transport)
        api.topStations()
        api.stationsByCountryCode(countryCode = "DE")
        assertFalse(transport.requestedURLs[0].contains("offset"))
        assertFalse(transport.requestedURLs[1].contains("offset"))
    }

    // MARK: Playback bridge

    @Test
    fun radioTrackCarriesStationFactsAndLiveDuration() {
        val station = RadioStation(
            uuid = "abc-123",
            name = "Jazz Radio",
            streamURL = "http://example.com/stream.mp3",
            faviconURL = "http://example.com/icon.png",
            country = "Germany",
            codec = "MP3",
            bitrate = 128,
        )
        val track = radioTrack(station)
        assertEquals("radio.abc-123", track.trackToken)
        assertEquals("Jazz Radio", track.title)
        assertEquals("Germany · MP3 · 128 kbps", track.artist)
        assertEquals("http://example.com/icon.png", track.artworkUrl)
        assertEquals(0, track.duration)
        assertEquals(Track.Rating.none, track.rating)
        assertTrue(track.isRadio)
        assertEquals("http://example.com/stream.mp3", track.streamURL)
    }

    @Test
    fun isRadioDistinguishesPandoraTracks() {
        assertFalse(
            Track(
                trackToken = "T0001", title = "Song", artist = "Artist",
                album = "", duration = 180, stationId = "S1",
            ).isRadio,
        )
        assertFalse(
            Track(
                trackToken = "ad.TOKEN", title = "Ad", artist = "",
                album = "", duration = 30, stationId = "S1", isAdvertisement = true,
            ).isRadio,
        )
    }
}
