//
//  StationTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/StationTests.swift — model decoding for
//  `user.getStationList` results from captured-shape fixtures: full
//  pithos-required + pydora-optional fields, sparse minimal stations,
//  string-or-number ids, and envelope-level errors.
//
//  `testStationListRequiresSession` ports with the SessionManager unit
//  (it constructs one).
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTests {

    /** Full station as the API returns it: pithos' required set plus
     *  pydora's optional set. One literal — splitting it across lines
     *  previously produced a doubled comma and an undecodable envelope. */
    private val fullStationJSON =
        """{"stationId":"123456","stationToken":"ST123","stationName":"Weezer Radio","isShared":false,"isQuickMix":false,"isThumbprint":false,"isGenreStation":false,"artUrl":"https://art.example/s.png","allowAddMusic":true,"allowDelete":true,"allowRename":true,"thumbCount":7}"""

    @Test
    fun decodesFullStationFields() {
        val json = """{"stations":[$fullStationJSON],"checksum":"abc"}"""
        val list = StationList.from(result(json))

        assertEquals(1, list.stations.size)
        assertEquals("abc", list.checksum)
        val s = list.stations.first()
        assertEquals("123456", s.id)
        assertEquals("ST123", s.token)
        assertEquals("Weezer Radio", s.name)
        assertFalse(s.isShared)
        assertFalse(s.isQuickMix)
        assertFalse(s.isThumbprint)
        assertFalse(s.isGenreStation)
        assertEquals("https://art.example/s.png", s.artUrl)
        assertEquals(emptyList<String>(), s.quickMixStationIds)
    }

    @Test
    fun decodesMinimalStationWithDefaults() {
        // pithos' required set only — everything else defaults.
        val json =
            """{"stations":[{"stationId":99,"stationToken":"T9","stationName":"Shared Mix","isShared":true,"isQuickMix":false}]}"""
        val list = StationList.from(result(json))
        val s = list.stations.first()
        // Numeric wire ids decode to the string form.
        assertEquals("99", s.id)
        assertEquals("T9", s.token)
        assertTrue(s.isShared)
        assertFalse(s.isQuickMix)
        assertNull(s.artUrl)
        assertNull(list.checksum)
    }

    @Test
    fun decodesQuickMixStationIds() {
        val json =
            """{"stations":[{"stationId":"q","stationToken":"Q","stationName":"QuickMix","isShared":false,"isQuickMix":true,"quickMixStationIds":["1","2"]}]}"""
        val list = StationList.from(result(json))
        val q = list.stations.first()
        assertTrue(q.isQuickMix)
        assertEquals(listOf("1", "2"), q.quickMixStationIds)
    }

    @Test
    fun missingStationsArrayIsTypedError() {
        val e = assertThrows(PandoraMalformedResponseError::class.java) {
            StationList.from(mapOf("checksum" to AnyJSON.Str("c")))
        }
        assertEquals("station list missing stations array", e.detail)
    }

    @Test
    fun stationListRequiresSession() = kotlinx.coroutines.test.runTest {
        val subject = makeManager()
        val e = org.junit.Assert.assertThrows(SessionManagerError::class.java) {
            kotlinx.coroutines.runBlocking { subject.getStationList() }
        }
        assertEquals(SessionManagerError.NotAuthenticated, e)
    }

    // MARK: Helpers

    private fun makeManager() = SessionManager(
        client = LegacyPandoraClient(transport = MockTransport(emptyList())),
        modes = PandoraModesClient(transport = MockModesTransport(emptyList()), csrf = MockCSRF()),
        store = null,
        now = ::fixedClock,
    )

    private fun result(objectJSON: String): Map<String, AnyJSON> {
        val envelope = """{"stat":"ok","result":$objectJSON}""".toByteArray()
        return ResponseEnvelope.result(envelope)
    }
}
