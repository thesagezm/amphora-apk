//
//  StationModesTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/StationModesTests.swift — listening modes:
//  wire decoding (modeId number-or-string, sparse entries, unavailable
//  stations), the exact request shape of both `interactiveradio`
//  endpoints, the 401 → csrf-refresh → retry-once contract, and the
//  SessionManager facade (premium gate on a free account, 401 →
//  re-login → retry, not-authenticated).
//
//  The StationModesSessionTests suite drives SessionManager; it ports
//  with that unit.
//

package pandoracore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// MARK: Mocks

class MockModesTransport(
    private val responses: List<Result<ModesHTTPResponse>>,
) : ModesTransport {

    data class Call(
        val url: String,
        val body: ByteArray,
        val headers: Map<String, String>,
    )

    private val lock = Any()
    private val recorded = mutableListOf<Call>()
    private var next = 0

    val calls: List<Call>
        get() = synchronized(lock) { recorded.toList() }

    override suspend fun post(url: String, body: ByteArray, headers: Map<String, String>): ModesHTTPResponse {
        val index = synchronized(lock) {
            recorded.add(Call(url, body, headers))
            next++
        }
        if (index >= responses.size) {
            throw PandoraTransportError("mock exhausted at request $index")
        }
        return responses[index].getOrThrow()
    }
}

class MockCSRF : CSRFTokenProviding {
    private val lock = Any()
    private var tokens = 0
    private var refreshes = 0

    val tokenCount: kotlin.Int
        get() = synchronized(lock) { tokens }

    val refreshCount: kotlin.Int
        get() = synchronized(lock) { refreshes }

    override suspend fun token(): String {
        synchronized(lock) { tokens++ }
        return "csrf-token"
    }

    override suspend fun refresh(): String {
        synchronized(lock) { refreshes++ }
        return "csrf-refreshed"
    }
}

// MARK: Decoding tests

class StationModesDecodingTests {

    private fun decode(json: String): StationModes = StationModes.from(json.toByteArray())

    @Test
    fun fullReplyDecodes() {
        val modes = decode(
            """
            {
              "interactiveRadioAvailable": true,
              "currentModeId": 1,
              "availableModesHeader": "Available modes",
              "hasTakeoverModes": false,
              "availableModes": [
                {"modeId":1,"modeName":"My Mix","modeDescription":"A blend of your stations",
                 "isModeAvailable":true,"isPremiumOnly":false},
                {"modeId":2,"modeName":"Deep Cuts","modeDescription":"More obscure tracks",
                 "isModeAvailable":true,"isPremiumOnly":false},
                {"modeId":5,"modeName":"Artist Only","modeDescription":"Just the artist",
                 "isModeAvailable":true,"isPremiumOnly":true}
              ]
            }
            """.trimIndent(),
        )
        assertTrue(modes.isInteractiveRadioAvailable)
        assertEquals(1, modes.currentModeID)
        assertEquals(listOf(1, 2, 5), modes.modes.map { it.id })
        assertEquals("Deep Cuts", modes.modes[1].name)
        assertEquals("More obscure tracks", modes.modes[1].modeDescription)
        assertTrue(modes.modes[1].isAvailable)
        assertFalse(modes.modes[1].isPremiumOnly)
        assertEquals("My Mix", modes.currentMode?.name)
        assertTrue(modes.mode(5)?.isPremiumOnly ?: false)
    }

    @Test
    fun modeIdAsDigitString() {
        val modes = decode(
            """
            {"currentModeId":"3","availableModes":[
              {"modeId":"3","modeName":"Discovery","isModeAvailable":true}]}
            """.trimIndent(),
        )
        assertEquals(3, modes.currentModeID)
        assertEquals(3, modes.modes.first()?.id)
        assertEquals("Discovery", modes.currentMode?.name)
    }

    @Test
    fun sparseFlagsDefault() {
        // No isModeAvailable/isPremiumOnly/interactiveRadioAvailable at all.
        val modes = decode("""{"availableModes":[{"modeId":7,"modeName":"Mystery Mode"}]}""")
        assertEquals(1, modes.modes.size)
        val mode = modes.modes.first()
        assertFalse(mode.isAvailable)
        assertFalse(mode.isPremiumOnly)
        assertEquals("", mode.modeDescription)
    }

    @Test
    fun unusableEntriesAreDropped() {
        val modes = decode(
            """
            {"availableModes":[
              {"modeName":"No id"},
              {"modeId":2,"modeName":""},
              {"modeId":"x","modeName":"Bad id"},
              {"modeId":3,"modeName":"First"},
              {"modeId":3,"modeName":"Duplicate"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("First"), modes.modes.map { it.name })
    }

    @Test
    fun unavailableStationDecodesWithoutModes() {
        val modes = decode("""{"interactiveRadioAvailable":false}""")
        assertFalse(modes.isInteractiveRadioAvailable)
        assertTrue(modes.modes.isEmpty())
        assertNull(modes.currentModeID)
    }

    @Test
    fun missingModesArrayOnAvailableStationIsMalformed() {
        assertThrows(PandoraMalformedResponseError::class.java) {
            decode("""{"interactiveRadioAvailable":true}""")
        }
    }

    @Test
    fun errorCodeBodyIsMalformed() {
        assertThrows(PandoraMalformedResponseError::class.java) {
            decode("""{"errorCode":1023,"message":"bad"}""")
        }
    }

    @Test
    fun statFailBodyIsMalformed() {
        assertThrows(PandoraMalformedResponseError::class.java) {
            decode("""{"stat":"fail","code":1001}""")
        }
    }

    @Test
    fun selectableGate() {
        val free = StationMode(id = 5, name = "Artist Only", isAvailable = true, isPremiumOnly = true)
        assertTrue(free.isSelectable(isSubscriber = true))
        assertFalse(free.isSelectable(isSubscriber = false))
        val plain = StationMode(id = 2, name = "Deep Cuts", isAvailable = true)
        assertTrue(plain.isSelectable(isSubscriber = false))
        val unoffered = StationMode(id = 9, name = "Elsewhere", isAvailable = false)
        assertFalse(unoffered.isSelectable(isSubscriber = true))
    }
}

// MARK: Client tests

class StationModesClientTests {

    private fun ok(json: String) = Result.success(ModesHTTPResponse(200, json.toByteArray()))
    private fun status(code: kotlin.Int) = Result.success(ModesHTTPResponse(code, "{}".toByteArray()))

    private fun makeClient(
        responses: List<Result<ModesHTTPResponse>>,
        csrf: MockCSRF = MockCSRF(),
    ): Triple<PandoraModesClient, MockModesTransport, MockCSRF> {
        val transport = MockModesTransport(responses)
        val client = PandoraModesClient(transport = transport, csrf = csrf)
        return Triple(client, transport, csrf)
    }

    private val modesReply = """
        {"currentModeId":1,"availableModes":[
          {"modeId":1,"modeName":"My Mix","isModeAvailable":true}]}
    """.trimIndent()

    /** A reply for a successful switch to mode 3 (stale-mode guard passes). */
    private val setReply = """
        {"currentModeId":3,"availableModes":[
          {"modeId":3,"modeName":"Deep Cuts","isModeAvailable":true}]}
    """.trimIndent()

    @Test
    fun getModesRequestShape() = runTest {
        val (client, transport, _) = makeClient(listOf(ok(modesReply)))
        client.getModes(userAuthToken = "U-TOKEN", stationId = "123")

        val calls = transport.calls
        assertEquals(1, calls.size)
        val call = calls.first()
        assertEquals(
            "https://www.pandora.com/api/v1/interactiveradio/getAvailableModesSimple",
            call.url,
        )
        assertEquals("""{"stationId":"123"}""", call.body.decodeToString())
        assertEquals("application/json", call.headers["Content-Type"])
        assertEquals("application/json, text/plain, */*", call.headers["Accept"])
        assertEquals("U-TOKEN", call.headers["X-AuthToken"])
        assertEquals("csrf-token", call.headers["X-CsrfToken"])
        assertEquals("https://www.pandora.com", call.headers["Origin"])
        assertEquals("https://www.pandora.com/", call.headers["Referer"])
        assertEquals("XMLHttpRequest", call.headers["X-Requested-With"])
        assertFalse(call.headers["User-Agent"]?.isEmpty() ?: true)
    }

    @Test
    fun setModeRequestShape() = runTest {
        // Reply carries currentModeId 3 so the stale-mode guard passes.
        val (client, transport, _) = makeClient(listOf(ok(setReply)))
        client.setMode(userAuthToken = "U-TOKEN", stationId = "123", modeId = 3, previousModeId = 1)

        val call = transport.calls.first()
        assertEquals(
            "https://www.pandora.com/api/v1/interactiveradio/setAndGetAvailableModes",
            call.url,
        )
        assertEquals("""{"modeId":3,"previousModeId":1,"stationId":"123"}""", call.body.decodeToString())
    }

    @Test
    fun setModeOmitsAbsentPreviousMode() = runTest {
        val (client, transport, _) = makeClient(listOf(ok(setReply)))
        client.setMode(userAuthToken = "U-TOKEN", stationId = "123", modeId = 3, previousModeId = null)

        val call = transport.calls.first()
        assertEquals("""{"modeId":3,"stationId":"123"}""", call.body.decodeToString())
    }

    @Test
    fun unauthorizedRefreshesCsrfAndRetriesOnce() = runTest {
        val (client, transport, csrf) = makeClient(listOf(status(401), ok(modesReply)))
        val modes = client.getModes(userAuthToken = "U-TOKEN", stationId = "123")
        assertEquals(1, modes.currentModeID)

        assertEquals(1, csrf.tokenCount)
        assertEquals(1, csrf.refreshCount)
        val calls = transport.calls
        assertEquals(2, calls.size)
        assertEquals("csrf-token", calls.first().headers["X-CsrfToken"])
        assertEquals("csrf-refreshed", calls.last().headers["X-CsrfToken"])
    }

    @Test
    fun unauthorizedAfterRetrySurfaces() = runTest {
        val (client, _, _) = makeClient(listOf(status(401), status(403)))
        val e = assertThrows(StationModesError::class.java) {
            kotlinx.coroutines.runBlocking { client.getModes(userAuthToken = "U-TOKEN", stationId = "123") }
        }
        assertEquals(StationModesError.Unauthorized, e)
    }

    @Test
    fun otherStatusSurfaces() = runTest {
        val (client, _, _) = makeClient(listOf(status(500)))
        val e = assertThrows(StationModesError::class.java) {
            kotlinx.coroutines.runBlocking { client.getModes(userAuthToken = "U-TOKEN", stationId = "123") }
        }
        assertEquals(StationModesError.HttpStatus(500), e)
    }

    @Test
    fun setModeServerKeptOldModeFails() = runTest {
        // 200 reply but currentModeId did not move to the requested mode.
        val (client, _, _) = makeClient(listOf(ok(modesReply)))
        val e = assertThrows(StationModesError::class.java) {
            kotlinx.coroutines.runBlocking {
                client.setMode(userAuthToken = "U-TOKEN", stationId = "123", modeId = 3, previousModeId = null)
            }
        }
        assertEquals(StationModesError.ModeNotOffered, e)
    }

    @Test
    fun setModeWithoutCurrentModeIdSucceeds() = runTest {
        val (client, _, _) = makeClient(
            listOf(ok("""{"availableModes":[{"modeId":3,"modeName":"Deep Cuts","isModeAvailable":true}]}""")),
        )
        val modes = client.setMode(userAuthToken = "U-TOKEN", stationId = "123", modeId = 3, previousModeId = null)
        assertEquals("Deep Cuts", modes.mode(3)?.name)
    }
}
