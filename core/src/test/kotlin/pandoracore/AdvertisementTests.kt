//
//  AdvertisementTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/AdvertisementTests.swift — ad handling after
//  pydora: playlist ad entries decode as flagged, usually unplayable stubs
//  (with a synthetic id when the entry has no trackToken),
//  `ad.getAdMetadata` / `ad.registerAd` request shapes, and the
//  SessionManager facade (prepareAd: resolve required, register
//  best-effort, skipped entirely without tracking tokens).
//
//  The SessionManagerAdTests suite drives SessionManager; it ports with
//  that unit.
//

package pandoracore

import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// MARK: Playlist decode

class PlaylistAdDecodingTests {

    private fun track(entryJSON: String): Track? {
        val entry = AnyJSON.fromJsonElement(Json.parseToJsonElement(entryJSON))
        val obj = entry.objectValue ?: return null
        return Track.fromEntry(obj)
    }

    @Test
    fun adEntryWithAudioIsKeptAndFlagged() {
        val ad = track(
            """
            {"adToken":"ADT-1","title":"Big Brand Ad","companyName":"Brand Co.",
             "audioUrl":"https://audio.example/ad.mp3","imageUrl":"https://img.example/ad.png",
             "clickThroughUrl":"https://click.example/","stationId":"123",
             "adTrackingTokens":["t1","t2"]}
            """.trimIndent(),
        )!!
        assertTrue(ad.isAdvertisement)
        assertEquals("ADT-1", ad.adToken)
        assertEquals("Big Brand Ad", ad.title)
        assertEquals("Brand Co.", ad.artist)
        assertEquals("ad.ADT-1", ad.id)
        assertEquals("123", ad.stationId)
        // pydora AudioField: bare audioUrl = 64 kbps aacplus high quality.
        val variant = ad.preferredVariant!!
        assertEquals(AudioVariant.Quality.highQuality, variant.quality)
        assertEquals(64, variant.bitrate)
        assertEquals("aacplus", variant.encoding)
        assertNotNull(ad.streamURL)
    }

    @Test
    fun adStubWithoutAudioIsKeptButUnplayable() {
        val ad = track("""{"adToken":"ADT-2","title":"Audio-less Ad","companyName":"Brand Co."}""")!!
        assertTrue(ad.isAdvertisement)
        assertTrue(ad.variants.isEmpty())
        assertNull(ad.streamURL)
    }

    @Test
    fun musicEntryIsUnaffected() {
        val song = track(
            """
            {"trackToken":"TT-1","songName":"Song","artistName":"Artist",
             "albumName":"Album","trackLength":200,"stationId":"123",
             "songRating":0,"trackGain":-1.5}
            """.trimIndent(),
        )!!
        assertFalse(song.isAdvertisement)
        assertNull(song.adToken)
        assertEquals("TT-1", song.id)
    }

    @Test
    fun entryWithoutSongNameOrAdTokenIsDropped() {
        val dropped = track("""{"albumName":"Mystery","stationId":"123"}""")
        assertNull(dropped)
    }

    // MARK: Advertisement metadata decoding

    private val metadataResult = """
        {"title":"Resolved Ad","companyName":"Brand Co.",
         "adTrackingTokens":["tok-1","tok-2"],
         "audioUrl":"https://audio.example/resolved.mp3",
         "imageUrl":"https://img.example/resolved.png",
         "clickThroughUrl":"https://click.example/resolved"}
    """.trimIndent()

    private val metadataJSONObject: Map<String, AnyJSON> by lazy {
        AnyJSON.fromJsonElement(Json.parseToJsonElement(metadataResult)).objectValue ?: emptyMap()
    }

    @Test
    fun adMetadataDecodes() {
        val ad = Advertisement.from("ADT-9", metadataJSONObject)
        assertEquals("ADT-9", ad.adToken)
        assertEquals("Resolved Ad", ad.title)
        assertEquals("Brand Co.", ad.companyName)
        assertEquals(listOf("tok-1", "tok-2"), ad.trackingTokens)
        assertEquals("https://audio.example/resolved.mp3", ad.audioURL)
        assertEquals("https://img.example/resolved.png", ad.imageURL)
        assertEquals("https://click.example/resolved", ad.clickThroughURL)
    }

    @Test
    fun adMetadataToleratesMissingFields() {
        val ad = Advertisement.from("ADT-9", emptyMap())
        assertEquals("", ad.title)
        assertTrue(ad.trackingTokens.isEmpty())
        assertNull(ad.audioURL)
    }

    @Test
    fun asTrackBuildsPlayableSlot() {
        val ad = Advertisement.from("ADT-9", metadataJSONObject)
        val slot = ad.asTrack(stationId = "123")
        assertTrue(slot.isAdvertisement)
        assertEquals("ADT-9", slot.adToken)
        assertEquals("ad.ADT-9", slot.id)
        assertEquals("Resolved Ad", slot.title)
        assertEquals("Brand Co.", slot.artist)
        assertEquals("123", slot.stationId)
        assertEquals(64, slot.preferredVariant?.bitrate)
        assertNotNull(slot.streamURL)
    }

    @Test
    fun asTrackWithoutAudioHasNoVariants() {
        val slot = Advertisement(adToken = "ADT-9").asTrack(stationId = "123")
        assertTrue(slot.variants.isEmpty())
        assertNull(slot.streamURL)
    }
}

// MARK: JSONValue array encoding

class JSONValueArrayTests {

    @Test
    fun arrayEncodesWithSortedKeys() {
        val body = JSONValue.encoded(
            mapOf(
                "stationId" to JSONValue.Str("123"),
                "adTrackingTokens" to JSONValue.Array(listOf(JSONValue.Str("t2"), JSONValue.Str("t1"))),
            ),
        )
        // Keys sorted; array preserves element order.
        assertEquals("""{"adTrackingTokens":["t2","t1"],"stationId":"123"}""", body.decodeToString())
    }

    @Test
    fun emptyArrayEncodes() {
        val body = JSONValue.encoded(mapOf("adTrackingTokens" to JSONValue.Array(emptyList())))
        assertEquals("""{"adTrackingTokens":[]}""", body.decodeToString())
    }
}

// MARK: SessionManager facade

class SessionManagerAdTests {

    private fun ok(resultObjectJSON: String) = okJSON(resultObjectJSON)
    private fun partnerOK() = okJSON("""{"partnerId":"77","partnerAuthToken":"P-TOKEN","syncTime":"$SYNC_TIME_MAIN"}""")

    private val metadataReply = """
        {"title":"Resolved Ad","companyName":"Brand Co.",
         "adTrackingTokens":["tok-1","tok-2"],
         "audioUrl":"https://audio.example/resolved.mp3"}
    """.trimIndent()

    private val adTrack = Track(
        trackToken = "ad.ADT-1",
        title = "Big Brand Ad",
        artist = "Brand Co.",
        album = "",
        duration = 0,
        stationId = "123",
        isAdvertisement = true,
        adToken = "ADT-1",
    )

    private fun makeManager(
        legacyResponses: List<Result<ByteArray>>,
    ): Pair<SessionManager, MockTransport> {
        val legacy = MockTransport(legacyResponses)
        val manager = SessionManager(
            client = LegacyPandoraClient(transport = legacy),
            modes = PandoraModesClient(transport = MockModesTransport(emptyList()), csrf = MockCSRF()),
            store = null,
            now = ::fixedClock,
        )
        return manager to legacy
    }

    @Test
    fun getAdMetadataSendsPydoraParams() = kotlinx.coroutines.test.runTest {
        val (manager, transport) = makeManager(
            listOf(partnerOK(), userOK(), ok(metadataReply)),
        )
        manager.login(username = "me@example.com", password = "hunter2")

        val ad = manager.getAdMetadata(adToken = "ADT-1")
        assertEquals("Resolved Ad", ad.title)
        assertEquals(listOf("tok-1", "tok-2"), ad.trackingTokens)
        assertEquals("https://audio.example/resolved.mp3", ad.audioURL)

        val request = transport.requests.last()
        assertTrue(request.url.contains("method=ad.getAdMetadata"))
        val body = decryptedBody(request)
        assertTrue(body.contains("\"adToken\":\"ADT-1\""))
        assertTrue(body.contains("\"returnAdTrackingTokens\":true"))
        assertTrue(body.contains("\"supportAudioAds\":true"))
    }

    @Test
    fun registerAdSendsTokensAndStation() = kotlinx.coroutines.test.runTest {
        val (manager, transport) = makeManager(
            listOf(partnerOK(), userOK(), ok("{}")),
        )
        manager.login(username = "me@example.com", password = "hunter2")

        manager.registerAd(stationId = "123", trackingTokens = listOf("tok-1", "tok-2"))

        val request = transport.requests.last()
        assertTrue(request.url.contains("method=ad.registerAd"))
        val body = decryptedBody(request)
        assertTrue(body.contains("\"adTrackingTokens\":[\"tok-1\",\"tok-2\"]"))
        assertTrue(body.contains("\"stationId\":\"123\""))
    }

    @Test
    fun registerAdRefusesEmptyTokensWithoutNetwork() = kotlinx.coroutines.test.runTest {
        val (manager, transport) = makeManager(
            listOf(partnerOK(), userOK()),
        )
        manager.login(username = "me@example.com", password = "hunter2")

        val e = org.junit.Assert.assertThrows(AdvertisementError::class.java) {
            kotlinx.coroutines.runBlocking {
                manager.registerAd(stationId = "123", trackingTokens = emptyList())
            }
        }
        assertEquals(AdvertisementError.MissingTrackingTokens, e)
        // Only the two login calls ever hit the transport.
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun prepareAdResolvesAndRegisters() = kotlinx.coroutines.test.runTest {
        val (manager, transport) = makeManager(
            listOf(partnerOK(), userOK(), ok(metadataReply), ok("{}")),
        )
        manager.login(username = "me@example.com", password = "hunter2")

        val metadata = manager.prepareAd(adTrack)
        assertEquals("ADT-1", metadata.adToken)
        assertEquals(listOf("tok-1", "tok-2"), metadata.trackingTokens)

        val requests = transport.requests
        assertEquals(4, requests.size)
        assertTrue(requests.last().url.contains("method=ad.registerAd"))
    }

    @Test
    fun prepareAdUsesTrackStationWhenNotOverridden() = kotlinx.coroutines.test.runTest {
        val (manager, transport) = makeManager(
            listOf(partnerOK(), userOK(), ok(metadataReply), ok("{}")),
        )
        manager.login(username = "me@example.com", password = "hunter2")

        manager.prepareAd(adTrack)

        val registerBody = decryptedBody(transport.requests.last())
        assertTrue(registerBody.contains("\"stationId\":\"123\""))
    }

    @Test
    fun prepareAdSkipsRegistrationWithoutTrackingTokens() = kotlinx.coroutines.test.runTest {
        val (manager, transport) = makeManager(
            listOf(partnerOK(), userOK(), ok("""{"title":"No tokens"}""")),
        )
        manager.login(username = "me@example.com", password = "hunter2")

        val metadata = manager.prepareAd(adTrack)
        assertEquals("No tokens", metadata.title)

        // partner + user + metadata only — no registerAd call (pydora
        // prepare_playback skips registration the same way).
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun prepareAdSurvivesRegisterFailure() = kotlinx.coroutines.test.runTest {
        val (manager, transport) = makeManager(
            listOf(
                partnerOK(), userOK(), ok(metadataReply),
                Result.failure(PandoraTransportError("register exploded")),
            ),
        )
        manager.login(username = "me@example.com", password = "hunter2")

        // Deliberate deviation from pydora: a failed impression
        // registration must not block playback.
        val metadata = manager.prepareAd(adTrack)
        assertEquals("Resolved Ad", metadata.title)

        assertEquals(4, transport.requests.size)
    }

    @Test
    fun prepareAdRequiresAnAdvertisement() = kotlinx.coroutines.test.runTest {
        val (manager, transport) = makeManager(listOf(partnerOK(), userOK()))
        manager.login(username = "me@example.com", password = "hunter2")

        val song = Track(
            trackToken = "TT-1", title = "Song", artist = "Artist", album = "",
            duration = 200, stationId = "123",
        )
        val e = org.junit.Assert.assertThrows(AdvertisementError::class.java) {
            kotlinx.coroutines.runBlocking { manager.prepareAd(song) }
        }
        assertEquals(AdvertisementError.NotAnAdvertisement, e)
        assertEquals(2, transport.requests.size)
    }
}
