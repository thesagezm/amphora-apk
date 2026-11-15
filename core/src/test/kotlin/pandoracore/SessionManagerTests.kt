//
//  SessionManagerTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/SessionManagerTests.swift — auth lifecycle
//  against scripted responses: partner login → syncTime offset → user
//  login → store, and the 1001 retry-once contract. All crypto fixtures
//  generated from the vendored pithos Blowfish pipeline.
//

package pandoracore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTests {

    // MARK: Login

    @Test
    fun loginRunsPartnerThenUserAndStoresRecord() = runTest {
        val transport = MockTransport(listOf(partnerOK(), userOK()))
        val store = InMemorySessionStore()
        val subject = makeManager(transport = transport, store = store)

        val tokens = subject.login(username = "me@example.com", password = "hunter2")

        assertEquals("77", tokens.partnerId)
        assertEquals("U-TOKEN", tokens.userAuthToken)
        assertEquals(760_000_000.0, tokens.timeOffset, 0.0)

        assertTrue(subject.isSubscriber)
        assertTrue(subject.hasSession)

        val saved = store.load()!!
        assertEquals("me@example.com", saved.credentials.username)
        assertEquals("U-TOKEN", saved.tokens.userAuthToken)
        assertTrue(saved.isSubscriber)

        // Request shape: partner login plain, user login encrypted + scoped.
        val requests = transport.requests
        assertEquals(2, requests.size)

        val partnerReq = requests.first()
        assertEquals("application/json", partnerReq.contentType)
        assertTrue(partnerReq.url.endsWith("?method=auth.partnerLogin"))
        assertEquals(
            """{"deviceModel":"android-generic","password":"AC7IBG09A3DTSYM4R41UJWL07VLN8JI7","username":"android","version":"5"}""",
            partnerReq.body.decodeToString(),
        )

        val userReq = requests.last()
        assertEquals("text/plain", userReq.contentType)
        assertEquals(
            "https://tuner.pandora.com/services/json/?partner_id=77&auth_token=P-TOKEN&method=auth.userLogin",
            userReq.url,
        )
        val userJSON = PandoraCrypto.decryptFromHex(userReq.body.decodeToString(), PandoraAPI.encryptKey)
        val trimmed = userJSON.takeWhile { it != 0.toByte() }.toByteArray()
        assertEquals(
            """{"loginType":"user","partnerAuthToken":"P-TOKEN","password":"hunter2","returnIsSubscriber":true,"syncTime":1760000000,"username":"me@example.com"}""",
            trimmed.decodeToString(),
        )
    }

    @Test
    fun loginPropagatesCredentialRejection() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                failJSON(code = 1002, message = "Invalid username or password"),
            ),
        )
        val subject = makeManager(transport = transport)

        var caught: PandoraAPIError? = null
        try {
            subject.login(username = "me@example.com", password = "wrong")
            org.junit.Assert.fail("expected PandoraAPIError")
        } catch (error: PandoraAPIError) {
            caught = error
        }
        assertTrue(caught?.isInvalidCredentials == true)
        assertFalse("failed login must leave no half-live session", subject.hasSession)
    }

    @Test
    fun shortSyncTimeIsMalformedSession() = runTest {
        val transport = MockTransport(
            listOf(
                okJSON("""{"partnerId":"77","partnerAuthToken":"P-TOKEN","syncTime":"$SYNC_TIME_SHORT"}"""),
            ),
        )
        val subject = makeManager(transport = transport)

        val e = org.junit.Assert.assertThrows(SessionManagerError::class.java) {
            kotlinx.coroutines.runBlocking { subject.login(username = "a", password = "b") }
        }
        assertEquals(
            "syncTime too short after decrypt",
            (e as SessionManagerError.MalformedSession).detail,
        )
    }

    @Test
    fun unparsableSyncTimeIsMalformedSession() = runTest {
        val transport = MockTransport(
            listOf(
                okJSON("""{"partnerId":"77","partnerAuthToken":"P-TOKEN","syncTime":"$SYNC_TIME_UNPARSABLE"}"""),
            ),
        )
        val subject = makeManager(transport = transport)

        val e = org.junit.Assert.assertThrows(SessionManagerError::class.java) {
            kotlinx.coroutines.runBlocking { subject.login(username = "a", password = "b") }
        }
        assertEquals(
            "syncTime digits unparsable",
            (e as SessionManagerError.MalformedSession).detail,
        )
    }

    // MARK: Sign out

    @Test
    fun signOutClearsSessionStoreAndRefusesFurtherRequests() = runTest {
        val transport = MockTransport(listOf(partnerOK(), userOK()))
        val store = InMemorySessionStore()
        val subject = makeManager(transport = transport, store = store)
        subject.login(username = "me@example.com", password = "hunter2")

        subject.signOut()

        assertFalse("sign-out must drop the in-memory session", subject.hasSession)
        assertNull("sign-out must clear the stored record", store.load())

        // The next authenticated call refuses before any network request.
        val e = org.junit.Assert.assertThrows(SessionManagerError::class.java) {
            kotlinx.coroutines.runBlocking { subject.getStationList() }
        }
        assertEquals(SessionManagerError.NotAuthenticated, e)
        assertEquals("sign-out adds no requests", 2, transport.requests.size)
    }

    // MARK: Restore

    @Test
    fun restoreRehydratesContextWithoutNetwork() = runTest {
        val store = InMemorySessionStore(record = cachedRecord(token = "U-CACHED"))
        val transport = MockTransport(emptyList())
        val subject = makeManager(transport = transport, store = store)

        val restored = subject.restore()
        assertTrue(restored)
        assertTrue(subject.hasSession)
        assertEquals("U-CACHED", subject.session?.userAuthToken)

        assertTrue("restore must not hit the network", transport.requests.isEmpty())
    }

    @Test
    fun restoreFailsClosedWhenStoreEmpty() = runTest {
        val subject = makeManager(
            transport = MockTransport(emptyList()),
            store = InMemorySessionStore(),
        )
        val restored = subject.restore()
        assertFalse(restored)
        assertFalse(subject.hasSession)
    }

    // MARK: Authenticated requests + 1001 retry-once

    @Test
    fun authenticatedRequestRequiresSession() = runTest {
        val subject = makeManager(transport = MockTransport(emptyList()))
        val e = org.junit.Assert.assertThrows(SessionManagerError::class.java) {
            kotlinx.coroutines.runBlocking { subject.authenticatedRequest("user.getStationList") }
        }
        assertEquals(SessionManagerError.NotAuthenticated, e)
    }

    @Test
    fun sessionExpiredReauthsAndReplaysOnce() = runTest {
        // Cached session → restore → first call gets 1001 → full re-login
        // (partner + user) → replay succeeds with the fresh token.
        val store = InMemorySessionStore(record = cachedRecord(token = "U-EXPIRED"))
        val transport = MockTransport(
            listOf(
                failJSON(code = 1001, message = "Invalid auth token"), // original call
                partnerOK(), // re-login step 1
                userOK(token = "U-FRESH"), // re-login step 2
                okJSON("""{"stations":[]}"""), // replay
            ),
        )
        val subject = makeManager(transport = transport, store = store)
        assertTrue(subject.restore())

        val result = subject.authenticatedRequest("user.getStationList")
        assertEquals(emptyList<AnyJSON>(), result["stations"]?.arrayValue ?: emptyList<AnyJSON>())

        val requests = transport.requests
        assertEquals("original, partner, user, replay", 4, requests.size)
        assertTrue(requests[3].url.contains("auth_token=U-FRESH"))
        assertEquals("U-FRESH", store.load()?.tokens?.userAuthToken)
        assertEquals("U-FRESH", subject.session?.userAuthToken)
    }

    @Test
    fun secondExpirySurfacesErrorWithoutLooping() = runTest {
        val store = InMemorySessionStore(record = cachedRecord(token = "U-EXPIRED"))
        val transport = MockTransport(
            listOf(
                failJSON(code = 1001, message = "Invalid auth token"), // original
                partnerOK(), // re-login 1
                userOK(token = "U-STILL-BAD"), // re-login 2
                failJSON(code = 1001, message = "Invalid auth token"), // replay
            ),
        )
        val subject = makeManager(transport = transport, store = store)
        assertTrue(subject.restore())

        val e = org.junit.Assert.assertThrows(PandoraAPIError::class.java) {
            kotlinx.coroutines.runBlocking { subject.authenticatedRequest("user.getStationList") }
        }
        assertTrue(e.isSessionExpired)
        assertEquals("exactly one replay — no retry loop", 4, transport.requests.size)
    }

    // MARK: getStationList

    @Test
    fun getStationListSendsParamsAndDecodesResult() = runTest {
        // Restored (cached) session → no login traffic; single
        // user.getStationList call under the stored token.
        val store = InMemorySessionStore(record = cachedRecord(token = "U-LISTED"))
        val transport = MockTransport(
            listOf(
                okJSON(
                    """{"stations":[{"stationId":"1","stationToken":"S1","stationName":"A","isShared":false,"isQuickMix":false},{"stationId":"2","stationToken":"S2","stationName":"B","isShared":true,"isQuickMix":true,"quickMixStationIds":["1"]}],"checksum":"xyz"}""",
                ),
            ),
        )
        val subject = makeManager(transport = transport, store = store)
        assertTrue(subject.restore())

        val list = subject.getStationList()

        assertEquals("xyz", list.checksum)
        assertEquals(listOf("A", "B"), list.stations.map { it.name })
        assertEquals(listOf("1", "2"), list.stations.map { it.id })
        assertTrue(list.stations[1].isQuickMix)

        val requests = transport.requests
        assertEquals(1, requests.size)
        val request = requests.first()
        assertTrue(request.url.contains("method=user.getStationList"))
        assertTrue(request.url.contains("auth_token=U-LISTED"))

        // Encrypted body carries both reference-derived params (pithos:
        // returnAllStations; pydora: includeStationArtUrl). Trim the
        // per-chunk NUL pad before substring checks.
        val hex = request.body.decodeToString()
        val plain = PandoraCrypto.decryptFromHex(hex, PandoraAPI.encryptKey)
        val body = plain.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
        assertTrue(body, body.contains("\"returnAllStations\":true"))
        assertTrue(body, body.contains("\"includeStationArtUrl\":true"))
        assertTrue(body, body.contains("\"userAuthToken\":\"U-LISTED\""))
    }

    @Test
    fun getStationListExpiredSessionReauthsThenReplays() = runTest {
        // Same 1001 contract as authenticatedRequest, through the typed API.
        val store = InMemorySessionStore(record = cachedRecord(token = "U-EXPIRED"))
        val transport = MockTransport(
            listOf(
                failJSON(code = 1001, message = "Invalid auth token"),
                partnerOK(),
                userOK(token = "U-FRESH"),
                okJSON("""{"stations":[]}"""),
            ),
        )
        val subject = makeManager(transport = transport, store = store)
        assertTrue(subject.restore())

        val list = subject.getStationList()
        assertEquals(emptyList<Station>(), list.stations)

        val requests = transport.requests
        assertEquals("original, partner, user, replay", 4, requests.size)
        assertTrue(requests[3].url.contains("auth_token=U-FRESH"))
    }

    // MARK: Station create / rename / delete

    @Test
    fun createStationFromMusicTokenSendsParamAndDecodesReply() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("""{"stationId":"555","stationToken":"S555","stationName":"New Station","isShared":false,"isQuickMix":false}"""),
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val created = subject.createStation(musicToken = "MUSIC-1")

        assertEquals("555", created.id)
        assertEquals("S555", created.token)
        assertEquals("New Station", created.name)
        assertFalse(created.isQuickMix)

        val requests = transport.requests
        assertEquals("partner, user, create", 3, requests.size)
        val request = requests.last()
        assertTrue(request.url.contains("method=station.createStation"))
        val body = decryptedBody(request)
        assertTrue(body, body.contains("\"musicToken\":\"MUSIC-1\""))
    }

    @Test
    fun createStationFromTrackTokenSendsBothParams() = runTest {
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("""{"stationId":"666","stationToken":"S666","stationName":"From Song","isShared":false,"isQuickMix":false}"""),
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val created = subject.createStation(trackToken = "TRACK-1", musicType = "song")
        assertEquals("666", created.id)

        val requests = transport.requests
        val request = requests.last()
        val body = decryptedBody(request)
        assertTrue(body, body.contains("\"trackToken\":\"TRACK-1\""))
        assertTrue(body, body.contains("\"musicType\":\"song\""))
    }

    @Test
    fun renameTransformsSharedStationFirstThenRenames() = runTest {
        // pithos: shared → transformSharedStation, then renameStation.
        val transport = MockTransport(
            listOf(
                partnerOK(),
                userOK(),
                okJSON("{}"), // transform
                okJSON("{}"), // rename
            ),
        )
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val shared = Station(
            id = "1", token = "ST-1", name = "Old Name",
            isShared = true, isQuickMix = false,
        )
        subject.renameStation(shared, "New Name")

        val requests = transport.requests
        assertEquals("partner, user, transform, rename", 4, requests.size)
        assertTrue(requests[2].url.contains("method=station.transformSharedStation"))
        assertTrue(requests[3].url.contains("method=station.renameStation"))
        val renameBody = decryptedBody(requests[3])
        assertTrue(renameBody, renameBody.contains("\"stationName\":\"New Name\""))
        assertTrue(renameBody, renameBody.contains("\"stationToken\":\"ST-1\""))
    }

    @Test
    fun renamePersonalStationSkipsTransformAndNoOpRenameSendsNothing() = runTest {
        val transport = MockTransport(listOf(partnerOK(), userOK(), okJSON("{}")))
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val personal = Station(
            id = "2", token = "ST-2", name = "Mine",
            isShared = false, isQuickMix = false,
        )
        subject.renameStation(personal, "Renamed")
        assertEquals("partner, user, rename — no transform", 3, transport.requests.size)
        assertTrue(transport.requests[2].url.contains("method=station.renameStation"))

        // Same name → no call at all (pithos: `if new_name != self.name`).
        subject.renameStation(personal, "Mine")
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun deleteStationSendsToken() = runTest {
        val transport = MockTransport(listOf(partnerOK(), userOK(), okJSON("{}")))
        val subject = makeManager(transport = transport)
        subject.login(username = "u", password = "p")

        val doomed = Station(
            id = "3", token = "ST-3", name = "Bye",
            isShared = false, isQuickMix = false,
        )
        subject.deleteStation(doomed)

        val requests = transport.requests
        assertEquals("partner, user, delete", 3, requests.size)
        val request = requests.last()
        assertTrue(request.url.contains("method=station.deleteStation"))
        val body = decryptedBody(request)
        assertTrue(body, body.contains("\"stationToken\":\"ST-3\""))
    }

    // MARK: syncTime offset math (static — no actor)

    @Test
    fun timeOffsetFromEncryptedSyncTime() {
        val offset = SessionManager.timeOffset(
            encryptedSyncTime = SYNC_TIME_MAIN,
            localTime = fixedClock(),
        )
        assertEquals(760_000_000.0, offset, 0.0)
    }

    @Test
    fun timeOffsetRejectsShortDecryptedPayload() {
        val e = org.junit.Assert.assertThrows(SessionManagerError::class.java) {
            SessionManager.timeOffset(encryptedSyncTime = SYNC_TIME_SHORT, localTime = fixedClock())
        }
        assertEquals(
            "syncTime too short after decrypt",
            (e as SessionManagerError.MalformedSession).detail,
        )
    }

    @Test
    fun timeOffsetRejectsNonHex() {
        val e = org.junit.Assert.assertThrows(PandoraCryptoException::class.java) {
            SessionManager.timeOffset(encryptedSyncTime = "zz", localTime = fixedClock())
        }
        assertEquals(PandoraCryptoException.InvalidHex, e)
    }
}
