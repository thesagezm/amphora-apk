//
//  LastFmTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/LastFmTests.swift — signature known-answer
//  vectors (including the API spec's own example), request shapes against
//  a scripted transport, error-code mapping, and the pithos scrobble-rule
//  boundaries. No network in any unit test.
//

package pandoracore

import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmTests {

    // MARK: Signature

    /**
     * The API spec's own example (last.fm/api/authspec): params
     * api_key=xxxxxxxx, method=auth.getSession, token=yyyyyy; the
     * secret "ilovecher". Expected MD5 precomputed.
     */
    @Test
    fun signatureMatchesSpecExample() {
        val signature = LastFmSigning.signature(
            mapOf(
                "api_key" to "xxxxxxxx",
                "method" to "auth.getSession",
                "token" to "yyyyyy",
            ),
            secret = "ilovecher",
        )
        assertEquals("1333ebf6f7dec747486b6ce965cca66b", signature)
    }

    /**
     * `format` and `callback` are excluded from the signed string; an
     * alphabetical sort of the remaining names is used (scrobble-shaped
     * parameter set, MD5 precomputed).
     */
    @Test
    fun signatureExcludesFormatAndCallbackAndSorts() {
        val base = mapOf(
            "api_key" to "K123",
            "artist" to "Artist Name",
            "duration" to "213",
            "method" to "track.scrobble",
            "sk" to "SESS",
            "timestamp" to "1690000000",
            "track" to "Song Title",
        )
        val withExcluded = base + mapOf("format" to "json", "callback" to "cb")
        assertEquals(
            "8d620eabaf3d00bf836f5b33ba078706",
            LastFmSigning.signature(withExcluded, secret = "SECRET"),
        )
        assertEquals(
            "8d620eabaf3d00bf836f5b33ba078706",
            LastFmSigning.signature(base, secret = "SECRET"),
        )
    }

    // MARK: Transport mock

    private class MockLastFmTransport(
        private val responses: List<Result<Pair<kotlin.Int, ByteArray>>>,
    ) : LastFmTransport {

        data class RecordedRequest(
            val url: String,
            val form: ByteArray,
            val headers: Map<String, String>,
        )

        private val lock = Any()
        private val recorded = mutableListOf<RecordedRequest>()
        private var next = 0

        val requests: List<RecordedRequest>
            get() = synchronized(lock) { recorded.toList() }

        override suspend fun post(url: String, form: ByteArray, headers: Map<String, String>): LastFmHTTPResponse {
            val index = synchronized(lock) {
                recorded.add(RecordedRequest(url, form, headers))
                next++
            }
            if (index >= responses.size) {
                throw LastFmError.Transport("mock exhausted at request $index")
            }
            val (status, body) = responses[index].getOrThrow()
            return LastFmHTTPResponse(status, body)
        }
    }

    // MARK: Helpers

    /** Decodes an `application/x-www-form-urlencoded` body back into its
     *  parameters. */
    private fun formFields(form: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val body = form.decodeToString()
        for (pair in body.split("&")) {
            val halves = pair.split("=", limit = 2)
            if (halves.size != 2) continue
            val key = URLDecoder.decode(halves[0], "UTF-8")
            val value = URLDecoder.decode(halves[1], "UTF-8")
            result[key] = value
        }
        return result
    }

    private fun ok(json: String) = Result.success(200 to json.toByteArray())

    private fun makeClient(transport: MockLastFmTransport) = LastFmClient(transport = transport)

    // MARK: Authentication

    @Test
    fun authenticateSendsMobileSessionAndDecodes() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("""{"session":{"name":"scrobbler","key":"K-SESSION","subscriber":0}}""")),
        )
        val session = makeClient(transport)
            .authenticate(username = "me@example.com", password = "hunter2")

        assertEquals("scrobbler", session.username)
        assertEquals("K-SESSION", session.key)
        assertFalse(session.isSubscriber)

        val request = transport.requests.first()
        assertEquals(LastFmClient.ENDPOINT, request.url)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        val fields = formFields(request.form)
        assertEquals("auth.getMobileSession", fields["method"])
        assertEquals(LastFmClient.API_KEY, fields["api_key"])
        assertEquals("me@example.com", fields["username"])
        assertEquals("hunter2", fields["password"])
        // The signature covers everything except itself (and format).
        val signed = fields.toMutableMap()
        val sent = signed.remove("api_sig")
        assertEquals(32, sent?.length)
        assertEquals(
            LastFmSigning.signature(signed, secret = LastFmClient.API_SECRET),
            sent,
        )
    }

    @Test
    fun authenticateInvalidCredentialsMapsError4() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("""{"error":4,"message":"Authentication Failed"}""")),
        )
        val e = org.junit.Assert.assertThrows(LastFmError::class.java) {
            kotlinx.coroutines.runBlocking {
                makeClient(transport).authenticate(username = "u", password = "p")
            }
        }
        assertEquals(LastFmError.InvalidCredentials, e)
    }

    @Test
    fun authenticateMalformedSessionIsMalformed() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("""{"session":{"name":"x"}}""")),
        )
        val e = org.junit.Assert.assertThrows(LastFmError::class.java) {
            kotlinx.coroutines.runBlocking {
                makeClient(transport).authenticate(username = "u", password = "p")
            }
        }
        assertEquals(LastFmError.Malformed, e)
    }

    // MARK: Scrobbling

    @Test
    fun scrobbleSendsSessionTrackAndTimestamp() = runTest {
        val transport = MockLastFmTransport(
            listOf(
                ok(
                    """{"scrobbles":{"@attr":{"accepted":1,"ignored":0},"scrobble":{"artist":{"corrected":"0"},"track":{"corrected":"0"}}}}""",
                ),
            ),
        )
        makeClient(transport).scrobble(
            sessionKey = "K-SESSION",
            artist = "Artist Name",
            track = "Song Title",
            album = "Album Name",
            timestamp = 1_690_000_000L,
            duration = 213.0,
        )

        val request = transport.requests.first()
        val fields = formFields(request.form)
        assertEquals("track.scrobble", fields["method"])
        assertEquals("K-SESSION", fields["sk"])
        assertEquals("Artist Name", fields["artist"])
        assertEquals("Song Title", fields["track"])
        assertEquals("Album Name", fields["album"])
        assertEquals("1690000000", fields["timestamp"])
        assertEquals("213", fields["duration"])
        val signed = fields.toMutableMap()
        signed.remove("api_sig")
        assertEquals(
            LastFmSigning.signature(signed, secret = LastFmClient.API_SECRET),
            fields["api_sig"],
        )
    }

    @Test
    fun scrobbleOmitsAbsentAlbumAndDuration() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("""{"scrobbles":{"@attr":{"accepted":1,"ignored":0}}}""")),
        )
        makeClient(transport).scrobble(
            sessionKey = "K",
            artist = "A",
            track = "T",
            album = null,
            timestamp = 100L,
            duration = null,
        )
        val fields = formFields(transport.requests[0].form)
        assertNull(fields["album"])
        assertNull(fields["duration"])
    }

    /** Unicode and reserved characters survive the form round trip. */
    @Test
    fun scrobbleEncodesUnicodeAndReservedCharacters() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("""{"scrobbles":{"@attr":{"accepted":1,"ignored":0}}}""")),
        )
        makeClient(transport).scrobble(
            sessionKey = "K",
            artist = "Sigur Rós",
            track = "Hoppípolla & Vaka",
            timestamp = 100L,
        )
        val fields = formFields(transport.requests[0].form)
        assertEquals("Sigur Rós", fields["artist"])
        assertEquals("Hoppípolla & Vaka", fields["track"])
    }

    // MARK: Now playing

    @Test
    fun updateNowPlayingSendsAndAccepts() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("""{"nowplaying":{"track":{"corrected":"0"},"artist":{"corrected":"0"}}}""")),
        )
        makeClient(transport).updateNowPlaying(
            sessionKey = "K-SESSION",
            artist = "Artist",
            track = "Track",
            album = "Album",
            duration = 180.0,
        )
        val fields = formFields(transport.requests[0].form)
        assertEquals("track.updateNowPlaying", fields["method"])
        assertEquals("K-SESSION", fields["sk"])
        assertEquals("Artist", fields["artist"])
        assertEquals("Album", fields["album"])
        assertEquals("180", fields["duration"])
    }

    @Test
    fun updateNowPlayingInvalidSessionMapsError9() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("""{"error":9,"message":"Invalid session key"}""")),
        )
        val e = org.junit.Assert.assertThrows(LastFmError::class.java) {
            kotlinx.coroutines.runBlocking {
                makeClient(transport).updateNowPlaying(sessionKey = "K", artist = "A", track = "T")
            }
        }
        assertEquals(LastFmError.InvalidSessionKey, e)
    }

    // MARK: Response format (live-verified 2026-11-07)

    /**
     * Last.fm replies in XML unless `format=json` is sent — and the
     * client only decodes JSON. Every call must ask for JSON; a fixture
     * transport returning JSON regardless would hide its absence, so
     * assert the parameter is on the wire for all three methods.
     */
    @Test
    fun everyRequestAsksForJSON() = runTest {
        val transport = MockLastFmTransport(
            listOf(
                ok("""{"session":{"name":"n","key":"K","subscriber":0}}"""),
                ok("""{"nowplaying":{}}"""),
                ok("""{"scrobbles":{}}"""),
            ),
        )
        val client = makeClient(transport)
        client.authenticate(username = "u", password = "p")
        client.updateNowPlaying(sessionKey = "K", artist = "A", track = "T")
        client.scrobble(sessionKey = "K", artist = "A", track = "T", timestamp = 1L)

        assertEquals(3, transport.requests.size)
        for (request in transport.requests) {
            assertEquals(
                "every Last.fm call must ask for JSON; the default reply is XML",
                "json",
                formFields(request.form)["format"],
            )
        }
    }

    /**
     * `format=json` is added after signing and is excluded from the
     * signature, so the api_sig stays valid with it present.
     */
    @Test
    fun formatParameterIsExcludedFromSignature() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("""{"nowplaying":{}}""")),
        )
        makeClient(transport).updateNowPlaying(sessionKey = "K", artist = "A", track = "T")
        val fields = formFields(transport.requests[0].form)
        val signed = fields.toMutableMap()
        val sent = signed.remove("api_sig")
        assertEquals(LastFmSigning.signature(signed, secret = LastFmClient.API_SECRET), sent)
    }

    // MARK: Error mapping

    @Test
    fun errorCodesMapToTypedErrors() = runTest {
        suspend fun errorFor(code: kotlin.Int): LastFmError {
            val transport = MockLastFmTransport(
                listOf(ok("""{"error":$code,"message":"m"}""")),
            )
            return try {
                LastFmClient(transport = transport)
                    .scrobble(sessionKey = "K", artist = "A", track = "T", timestamp = 1L)
                LastFmError.Malformed
            } catch (error: LastFmError) {
                error
            }
        }

        val rateLimited = errorFor(29)
        val invalidSignature = errorFor(13)
        val invalidApiKey = errorFor(10)
        val suspended = errorFor(26)
        assertEquals(LastFmError.RateLimited, rateLimited)
        assertEquals(LastFmError.InvalidSignature, invalidSignature)
        assertEquals(LastFmError.InvalidApiKey, invalidApiKey)
        assertEquals(LastFmError.Rejected(26, "m"), suspended)
    }

    @Test
    fun non2xxWithoutErrorBodyMapsHttpStatus() = runTest {
        val transport = MockLastFmTransport(
            listOf(Result.success(500 to "Bad Gateway".toByteArray())),
        )
        val e = org.junit.Assert.assertThrows(LastFmError::class.java) {
            kotlinx.coroutines.runBlocking {
                makeClient(transport).updateNowPlaying(sessionKey = "K", artist = "A", track = "T")
            }
        }
        assertEquals(LastFmError.HttpStatus(500), e)
    }

    @Test
    fun malformedBodyMapsMalformed() = runTest {
        val transport = MockLastFmTransport(
            listOf(ok("this is not json")),
        )
        val e = org.junit.Assert.assertThrows(LastFmError::class.java) {
            kotlinx.coroutines.runBlocking {
                makeClient(transport).updateNowPlaying(sessionKey = "K", artist = "A", track = "T")
            }
        }
        assertEquals(LastFmError.Malformed, e)
    }

    // MARK: Scrobble policy (pithos rule)

    @Test
    fun policyMirrorsPithosRule() {
        // Ads never scrobble.
        assertFalse(ScrobblePolicy.shouldScrobble(duration = 200.0, position = 200.0, isAdvertisement = true))
        // Tracks of 30 s or shorter never scrobble (pithos: duration > 30).
        assertFalse(ScrobblePolicy.shouldScrobble(duration = 30.0, position = 30.0, isAdvertisement = false))
        // Past the midpoint, but not at it.
        assertFalse(ScrobblePolicy.shouldScrobble(duration = 100.0, position = 50.0, isAdvertisement = false))
        assertTrue(ScrobblePolicy.shouldScrobble(duration = 100.0, position = 50.5, isAdvertisement = false))
        // Past 240 s, but not at it.
        assertFalse(ScrobblePolicy.shouldScrobble(duration = 600.0, position = 240.0, isAdvertisement = false))
        assertTrue(ScrobblePolicy.shouldScrobble(duration = 600.0, position = 241.0, isAdvertisement = false))
        // Early skip: neither rule met.
        assertFalse(ScrobblePolicy.shouldScrobble(duration = 600.0, position = 30.0, isAdvertisement = false))
    }

    // MARK: Form encoding

    @Test
    fun percentEncodedFormRoundTrips() {
        val form = LastFmClient.percentEncodedForm(
            mapOf(
                "b" to "two words",
                "a" to "at&sign=plus+tilde~",
                "c" to "Sigur Rós",
            ),
        )
        // Three pairs, sorted by key, strict percent-encoding ('~' and
        // other RFC 3986 unreserved characters stay literal).
        assertEquals(3, form.split("&").size)
        assertTrue(form.startsWith("a=at%26sign%3Dplus%2Btilde~&"))
        assertEquals(
            "a=two%20words",
            LastFmClient.percentEncodedForm(mapOf("a" to "two words")),
        )
        assertEquals(
            "c=Sigur%20R%C3%B3s",
            LastFmClient.percentEncodedForm(mapOf("c" to "Sigur Rós")),
        )
        // No raw space, '&', '=' or '+' ever survives inside a value.
        assertFalse(form.contains(" "))
        for (pair in form.split("&")) {
            val halves = pair.split("=", limit = 2)
            assertEquals(2, halves.size)
            if (halves.size == 2) {
                assertFalse(halves[1].contains("+"))
                assertFalse(halves[1].contains("&"))
            }
        }
    }
}
