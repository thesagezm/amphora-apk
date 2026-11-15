//
//  UserSettingsTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/UserSettingsTests.swift — `user.getSettings` —
//  the account-level settings. The only field Amphora uses is the
//  explicit-content filter, which pithos reads in
//  `Pandora.explicit_content_filter_state` (pithos' `https=True` only
//  switches the transport to TLS — the body is still Blowfish-encrypted,
//  like every other authenticated call).
//
//  testGetUserSettingsSendsTheMethodAndDecodesTheReply and
//  testGetUserSettingsReAuthsOnAnExpiredSession drive SessionManager;
//  they port with the SessionManager unit.
//

package pandoracore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsTests {

    // MARK: Decoding

    @Test
    fun explicitFilterEnabledIsDecoded() {
        val settings = UserSettings.from(
            mapOf(
                "isExplicitContentFilterEnabled" to AnyJSON.Bool(true),
                "isExplicitContentFilterPINProtected" to AnyJSON.Bool(false),
            ),
        )
        assertTrue(settings.isExplicitContentFilterEnabled)
        assertFalse(settings.isExplicitContentFilterPINProtected)
    }

    @Test
    fun explicitFilterDisabledIsDecoded() {
        val settings = UserSettings.from(
            mapOf("isExplicitContentFilterEnabled" to AnyJSON.Bool(false)),
        )
        assertFalse(settings.isExplicitContentFilterEnabled)
    }

    @Test
    fun pinProtectedStateIsDecoded() {
        val settings = UserSettings.from(
            mapOf(
                "isExplicitContentFilterEnabled" to AnyJSON.Bool(true),
                "isExplicitContentFilterPINProtected" to AnyJSON.Bool(true),
            ),
        )
        assertTrue(settings.isExplicitContentFilterPINProtected)
    }

    @Test
    fun missingKeysMeanTheFilterIsOff() {
        // pithos reads the field with a `False` default — an older
        // server that does not send the key must not be read as "the
        // filter is on".
        val settings = UserSettings.from(emptyMap())
        assertFalse(settings.isExplicitContentFilterEnabled)
        assertFalse(settings.isExplicitContentFilterPINProtected)
    }

    @Test
    fun wrongTypeIsTreatedAsOff() {
        val settings = UserSettings.from(
            mapOf("isExplicitContentFilterEnabled" to AnyJSON.Str("true")),
        )
        assertFalse(settings.isExplicitContentFilterEnabled)
    }

    // MARK: The call

    @Test
    fun getUserSettingsSendsTheMethodAndDecodesTheReply() = kotlinx.coroutines.test.runTest {
        val store = InMemorySessionStore(record = cachedRecord(token = "U-LISTED"))
        val transport = MockTransport(
            listOf(
                Result.success(
                    """{"stat":"ok","result":{"isExplicitContentFilterEnabled":true,"isExplicitContentFilterPINProtected":false}}""".toByteArray(),
                ),
            ),
        )
        val subject = makeManager(transport = transport, store = store)
        assertTrue(subject.restore())

        val settings = subject.getUserSettings()
        assertTrue(settings.isExplicitContentFilterEnabled)
        assertFalse(settings.isExplicitContentFilterPINProtected)

        val requests = transport.requests
        assertEquals(1, requests.size)
        val request = requests.first()
        assertTrue(request.url.contains("method=user.getSettings"))
        assertTrue(request.url.contains("auth_token=U-LISTED"))
    }

    @Test
    fun getUserSettingsReAuthsOnAnExpiredSession() = kotlinx.coroutines.test.runTest {
        // The same 1001 contract as every other authenticated call.
        val store = InMemorySessionStore(record = cachedRecord(token = "U-EXPIRED"))
        val transport = MockTransport(
            listOf(
                Result.success(
                    """{"stat":"fail","code":1001,"message":"Invalid auth token"}""".toByteArray(),
                ),
                Result.success(
                    """{"stat":"ok","result":{"partnerId":"77","partnerAuthToken":"P-TOKEN","syncTime":"$SYNC_TIME_MAIN"}}""".toByteArray(),
                ),
                Result.success(
                    """{"stat":"ok","result":{"userId":"u-1","userAuthToken":"U-FRESH","isSubscriber":true}}""".toByteArray(),
                ),
                Result.success(
                    """{"stat":"ok","result":{"isExplicitContentFilterEnabled":false}}""".toByteArray(),
                ),
            ),
        )
        val subject = makeManager(transport = transport, store = store)
        assertTrue(subject.restore())

        val settings = subject.getUserSettings()
        assertFalse(settings.isExplicitContentFilterEnabled)

        val requests = transport.requests
        assertEquals("original, partner, user, replay", 4, requests.size)
        assertTrue(requests[3].url.contains("auth_token=U-FRESH"))
    }

    private fun makeManager(transport: MockTransport, store: SessionStore?) =
        SessionManager(
            client = LegacyPandoraClient(transport = transport),
            modes = PandoraModesClient(transport = MockModesTransport(emptyList()), csrf = MockCSRF()),
            store = store,
            now = ::fixedClock,
        )
}
