//
//  PlaylistTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/PlaylistTests.swift — `station.getPlaylist`
//  decoding (wire fields, quality promotion, ad flagging) and the
//  SessionManager request shape.
//
//  `testGetPlaylistSendsPithosParamsAndDecodesItems` drives SessionManager;
//  it ports with the SessionManager unit.
//

package pandoracore

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTests {

    // MARK: Parsing

    @Test
    fun decodesFullEntry() {
        val result = envelopeResult(
            """
            {
              "items": [
                {
                  "albumName": "Album",
                  "artistName": "Band",
                  "trackToken": "TT-1",
                  "songRating": 1,
                  "stationId": "42",
                  "songName": "Title",
                  "songDetailUrl": "https://x",
                  "songExplorerUrl": "https://y",
                  "albumArtUrl": "https://art",
                  "trackLength": 215,
                  "trackGain": "0.75",
                  "adToken": "AD",
                  "audioUrlMap": {
                    "highQuality": {
                      "encoding": "aacplus",
                      "bitrate": "64",
                      "audioUrl": "https://stream/a"
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        val tracks = Track.from(result)
        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertEquals("TT-1", track.trackToken)
        assertEquals("Title", track.title)
        assertEquals("Band", track.artist)
        assertEquals("Album", track.album)
        assertEquals("https://art", track.artworkUrl)
        assertEquals(215, track.duration)
        assertEquals("42", track.stationId)
        assertEquals(Track.Rating.loved, track.rating)
        assertEquals(0.75, track.gain, 0.0)
        assertTrue(track.isAdvertisement)
        assertEquals("https://stream/a", track.streamURL)
        assertEquals(64, track.preferredVariant?.bitrate)
        assertEquals("aacplus", track.preferredVariant?.encoding)
    }

    @Test
    fun entriesWithoutSongNameAreSkipped() {
        // pithos filters these out: `[Song(...) for i in playlist if 'songName' in i]`
        val result = envelopeResult(
            """
            {
              "items": [
                { "adToken": "AD-1", "trackToken": "TT-AD" },
                { "songName": "Real", "trackToken": "TT-2", "artistName": "A" }
              ]
            }
            """.trimIndent(),
        )
        val tracks = Track.from(result)
        assertEquals(listOf("TT-2"), tracks.map { it.trackToken })
        assertFalse(tracks[0].isAdvertisement)
    }

    @Test
    fun nonObjectEntriesAreIgnored() {
        val result = envelopeResult(
            """{ "items": ["nope", { "songName": "Ok", "trackToken": "TT" }] }""",
        )
        assertEquals(listOf("TT"), Track.from(result).map { it.trackToken })
    }

    // MARK: Quality selection

    @Test
    fun additionalUrlsPromoteMp3WhenHighQualityIsBelow128() {
        // pithos' rule: the extra 128 MP3 beats a <128 high quality, and the
        // old high becomes the medium variant.
        val result = envelopeResult(
            """
            {
              "items": [
                {
                  "songName": "T",
                  "trackToken": "TT",
                  "audioUrlMap": {
                    "highQuality": {
                      "encoding": "aacplus",
                      "bitrate": "64",
                      "audioUrl": "https://aac64"
                    }
                  },
                  "additionalAudioUrl": ["https://aac32", "https://mp3-128"]
                }
              ]
            }
            """.trimIndent(),
        )
        val track = Track.from(result).first()
        assertEquals(
            listOf(AudioVariant.Quality.highQuality, AudioVariant.Quality.mediumQuality),
            track.variants.map { it.quality },
        )
        assertEquals("mp3", track.variants[0].encoding)
        assertEquals(128, track.variants[0].bitrate)
        assertEquals("https://mp3-128", track.variants[0].url)
        assertEquals("https://aac64", track.variants[1].url)
        assertEquals("https://mp3-128", track.streamURL)
    }

    @Test
    fun additionalUrlsBecomeLowQualityFallbackWhenHighIsAlready128() {
        val result = envelopeResult(
            """
            {
              "items": [
                {
                  "songName": "T",
                  "trackToken": "TT",
                  "audioUrlMap": {
                    "highQuality": {
                      "encoding": "mp3",
                      "bitrate": "128",
                      "audioUrl": "https://mp3-128"
                    }
                  },
                  "additionalAudioUrl": ["https://aac32", "https://mp3-128"]
                }
              ]
            }
            """.trimIndent(),
        )
        val track = Track.from(result).first()
        assertEquals(
            listOf(AudioVariant.Quality.highQuality, AudioVariant.Quality.lowQuality),
            track.variants.map { it.quality },
        )
        assertEquals(32, track.variants[1].bitrate)
        assertEquals("https://aac32", track.variants[1].url)
        assertEquals("https://mp3-128", track.streamURL)
    }

    @Test
    fun bareAudioUrlIsTreatedAsHighQualityAacplus() {
        // pydora's AudioField: some tokens answer with a plain audioUrl.
        val result = envelopeResult(
            """
            {
              "items": [
                { "songName": "T", "trackToken": "TT", "audioUrl": "https://bare" }
              ]
            }
            """.trimIndent(),
        )
        val track = Track.from(result).first()
        assertEquals(1, track.variants.size)
        assertEquals(AudioVariant.Quality.highQuality, track.variants[0].quality)
        assertEquals("aacplus", track.variants[0].encoding)
        assertEquals(64, track.variants[0].bitrate)
        assertEquals("https://bare", track.streamURL)
    }

    @Test
    fun entryWithoutAnyAudioHasNoStream() {
        val result = envelopeResult(
            """
            {
              "items": [
                { "songName": "T", "trackToken": "TT", "adToken": "AD" }
              ]
            }
            """.trimIndent(),
        )
        val track = Track.from(result).first()
        assertTrue(track.variants.isEmpty())
        assertNull(track.streamURL)
        assertTrue(track.isAdvertisement)
    }

    @Test
    fun variantsAreOrderedBestQualityFirst() {
        val result = envelopeResult(
            """
            {
              "items": [
                {
                  "songName": "T",
                  "trackToken": "TT",
                  "audioUrlMap": {
                    "lowQuality": {
                      "encoding": "aacplus",
                      "bitrate": "32",
                      "audioUrl": "https://low"
                    },
                    "mediumQuality": {
                      "encoding": "aacplus",
                      "bitrate": "64",
                      "audioUrl": "https://med"
                    },
                    "highQuality": {
                      "encoding": "aacplus",
                      "bitrate": "128",
                      "audioUrl": "https://high"
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        val track = Track.from(result).first()
        assertEquals(
            listOf("https://high", "https://med", "https://low"),
            track.variants.map { it.url },
        )
    }

    // MARK: Playlist expiry

    @Test
    fun playlistExpiresAfterOneHour() {
        val fetched = Instant.ofEpochSecond(1_000_000_000)
        val playlist = Playlist(tracks = emptyList(), fetchedAt = fetched)
        assertFalse(playlist.isExpired(now = fetched.plusSeconds(3_599)))
        assertTrue(playlist.isExpired(now = fetched.plusSeconds(3_600)))
    }

    // MARK: Request shape (SessionManager)

    @Test
    fun getPlaylistSendsPithosParamsAndDecodesItems() = kotlinx.coroutines.test.runTest {
        val store = InMemorySessionStore(record = cachedRecord(token = "U-LISTED"))
        val transport = MockTransport(
            listOf(
                okJSON(
                    """
                    {
                      "items": [
                        {
                          "songName": "Song",
                          "artistName": "Artist",
                          "trackToken": "TT",
                          "stationId": "7",
                          "trackLength": 100,
                          "audioUrlMap": {
                            "highQuality": {
                              "encoding": "aacplus",
                              "bitrate": "64",
                              "audioUrl": "https://stream"
                            }
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
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

        val station = Station(
            id = "7", token = "ST-7", name = "Rock", isShared = false, isQuickMix = false,
        )
        val playlist = manager.getPlaylist(station)

        assertEquals(1, playlist.tracks.size)
        assertEquals("Song", playlist.tracks.first()?.title)
        assertEquals("https://stream", playlist.tracks.first()?.streamURL)

        val requests = transport.requests
        assertEquals(1, requests.size)
        val request = requests.first()
        assertTrue(request.url.contains("method=station.getPlaylist"))
        assertTrue(request.url.contains("auth_token=U-LISTED"))

        val hex = request.body.decodeToString()
        val plain = PandoraCrypto.decryptFromHex(hex, PandoraAPI.encryptKey)
        val body = plain.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
        assertTrue(body, body.contains("\"stationToken\":\"ST-7\""))
        assertTrue(body, body.contains("\"includeTrackLength\":true"))
        assertTrue(
            body,
            body.contains("\"additionalAudioUrl\":\"HTTP_32_AACPLUS,HTTP_128_MP3\""),
        )
    }

    // MARK: Helpers

    private fun envelopeResult(objectJSON: String): Map<String, AnyJSON> {
        val envelope = """{"stat":"ok","result":$objectJSON}""".toByteArray()
        return ResponseEnvelope.result(envelope)
    }
}
