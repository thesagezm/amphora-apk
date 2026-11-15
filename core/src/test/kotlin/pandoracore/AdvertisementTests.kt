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
