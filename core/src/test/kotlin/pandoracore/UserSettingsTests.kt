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
}
