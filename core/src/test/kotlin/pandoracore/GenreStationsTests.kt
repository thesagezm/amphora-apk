//
//  GenreStationsTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/GenreStationsTests.swift — decoding of
//  `station.getGenreStations` from fixture JSON (tolerant entry handling)
//  and the SessionManager facade (request shape + decode under a live
//  session).
//
//  testGetGenreStationsCallsBareAndDecodes and
//  testGetGenreStationsWithoutSessionThrows drive SessionManager; they
//  port with the SessionManager unit.
//

package pandoracore

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreStationsTests {

    // MARK: Decoding

    @Test
    fun decodeFullCatalog() {
        val result = parse(
            """
            {"categories": [
                {"categoryName": "Pop", "stations": [
                    {"stationId": 12345, "stationToken": "G123",
                     "stationName": "Today's Hits", "categoryName": "Pop",
                     "artUrl": "http://cont-1.p-cdn.com/pop.jpg"},
                    {"stationId": "12346", "stationToken": "G124",
                     "stationName": "90s Pop Hits", "categoryName": "Pop"}
                ]},
                {"categoryName": "Rock", "stations": [
                    {"stationToken": "G200", "stationName": "Classic Rock"}
                ]}
            ]}
            """.trimIndent(),
        )
        val catalog = GenreStations.from(result)

        assertEquals(listOf("Pop", "Rock"), catalog.categories.map { it.name })
        assertEquals(2, catalog.categories[0].stations.size)

        val hits = catalog.categories[0].stations[0]
        assertEquals("G123", hits.token)
        assertEquals("12345", hits.stationId)
        assertEquals("Today's Hits", hits.name)
        assertEquals("Pop", hits.categoryName)
        assertEquals("http://cont-1.p-cdn.com/pop.jpg", hits.artUrl)

        // String-form stationId tolerated; artUrl absent → null.
        val nineties = catalog.categories[0].stations[1]
        assertEquals("12346", nineties.stationId)
        assertEquals(null, nineties.artUrl)

        // An entry without its own categoryName inherits the category's.
        val classic = catalog.categories[1].stations[0]
        assertEquals("Rock", classic.categoryName)
    }

    @Test
    fun decodeDropsUnusableEntriesAndDuplicates() {
        val result = parse(
            """
            {"categories": [
                {"categoryName": "Pop", "stations": [
                    {"stationToken": "G1", "stationName": "Kept"},
                    {"stationName": "No token — dropped"},
                    {"stationToken": "", "stationName": "Empty token — dropped"},
                    {"stationToken": "G2"},
                    {"stationToken": "G1", "stationName": "Duplicate — dropped"}
                ]},
                {"categoryName": "Empty", "stations": []},
                {"categoryName": "No stations key"},
                {"stations": [{"stationToken": "G9", "stationName": "Unnamed category"}]}
            ]}
            """.trimIndent(),
        )
        val catalog = GenreStations.from(result)

        assertEquals(1, catalog.categories.size)
        assertEquals("Pop", catalog.categories[0].name)
        assertEquals(listOf("G1"), catalog.categories[0].stations.map { it.token })
        assertEquals("Kept", catalog.categories[0].stations[0].name)
    }

    @Test
    fun missingCategoriesArrayIsMalformed() {
        assertThrows(PandoraMalformedResponseError::class.java) {
            GenreStations.from(emptyMap())
        }
        val notArray = parse("""{"categories": "nope"}""")
        assertThrows(PandoraMalformedResponseError::class.java) {
            GenreStations.from(notArray)
        }
    }

    @Test
    fun emptyCategoriesIsAllowed() {
        val catalog = GenreStations.from(parse("""{"categories": []}"""))
        assertTrue(catalog.categories.isEmpty())
    }

    // MARK: SessionManager facade

    private fun ok(resultObjectJSON: String) = okJSON(resultObjectJSON)

    private fun partnerOK() = okJSON(
        """{"partnerId":"77","partnerAuthToken":"P-TOKEN","syncTime":"$SYNC_TIME_MAIN"}""",
    )

    private fun userOK() = okJSON("""{"userId":"u-1","userAuthToken":"U-TOKEN","isSubscriber":false}""")

    @Test
    fun getGenreStationsCallsBareAndDecodes() = kotlinx.coroutines.test.runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                ok(
                    """
                    {"categories": [
                        {"categoryName": "Country", "stations": [
                            {"stationToken": "G300", "stationName": "Country Hits",
                             "categoryName": "Country"}
                        ]}
                    ]}
                    """.trimIndent(),
                ),
            ),
        )
        val subject = SessionManager(
            client = LegacyPandoraClient(transport = transport),
            modes = PandoraModesClient(transport = MockModesTransport(emptyList()), csrf = MockCSRF()),
            store = null,
            now = ::fixedClock,
        )

        subject.login(username = "me@example.com", password = "hunter2")
        val categories = subject.getGenreStations()

        assertEquals(1, categories.size)
        assertEquals("G300", categories[0].stations[0].token)
        assertEquals("Country Hits", categories[0].stations[0].name)

        // The method takes no parameters — request 3 goes out bare
        // (authenticated calls carry more query params after `method=`,
        // so match on the method pair, not the suffix).
        val requests = transport.requests
        assertEquals(3, requests.size)
        val genreRequest = requests.last()
        assertTrue(genreRequest.url.contains("method=station.getGenreStations"))
    }

    @Test
    fun getGenreStationsWithoutSessionThrows() = kotlinx.coroutines.test.runTest {
        val transport = MockTransport(emptyList())
        val subject = SessionManager(
            client = LegacyPandoraClient(transport = transport),
            modes = PandoraModesClient(transport = MockModesTransport(emptyList()), csrf = MockCSRF()),
            store = null,
            now = ::fixedClock,
        )

        val e = org.junit.Assert.assertThrows(SessionManagerError::class.java) {
            kotlinx.coroutines.runBlocking { subject.getGenreStations() }
        }
        assertEquals(SessionManagerError.NotAuthenticated, e)
        assertTrue(transport.requests.isEmpty())
    }

    // MARK: Helpers

    private fun parse(json: String): Map<String, AnyJSON> {
        val element: JsonElement = Json.parseToJsonElement(json)
        val decoded = AnyJSON.fromJsonElement(element)
        return decoded.objectValue
            ?: throw PandoraMalformedResponseError("fixture was not a JSON object")
    }
}
