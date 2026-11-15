//
//  URLBuilderTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/URLBuilderTests.swift — URL/param building must
//  match `pithos.pandora.Pandora.json_call` byte-for-byte (query order,
//  token precedence, syncTime injection, deterministic sorted-key JSON,
//  encryption on/off per method).
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class URLBuilderTests {

    private val fixedNow = 1_000_000_000.0

    private fun builder(
        partnerId: String? = null,
        partnerAuthToken: String? = null,
        userId: String? = null,
        userAuthToken: String? = null,
        timeOffset: Double? = null,
    ) = URLBuilder(
        context = URLBuilder.Context(
            partnerId = partnerId,
            partnerAuthToken = partnerAuthToken,
            userId = userId,
            userAuthToken = userAuthToken,
            timeOffset = timeOffset,
        ),
    )

    // MARK: Query string

    @Test
    fun partnerLoginURLHasOnlyMethod() {
        val url = builder().endpointURL(PandoraAPI.Method.partnerLogin)
        assertEquals(
            "https://tuner.pandora.com/services/json/?method=auth.partnerLogin",
            url,
        )
    }

    @Test
    fun queryOrderIsPartnerIdUserIdAuthTokenMethod() {
        val url = builder(
            partnerId = "123",
            userId = "u-9",
            userAuthToken = "UT",
        ).endpointURL("user.getStationList")
        assertEquals(
            "https://tuner.pandora.com/services/json/?partner_id=123&user_id=u-9&auth_token=UT&method=user.getStationList",
            url,
        )
    }

    @Test
    fun userTokenTakesPrecedenceOverPartnerToken() {
        val url = builder(
            partnerId = "123",
            partnerAuthToken = "PT",
            userAuthToken = "UT",
        ).endpointURL("station.getPlaylist")
        assertTrue(url.contains("auth_token=UT"))
        assertFalse(url.contains("PT"))
    }

    @Test
    fun partnerTokenUsedBeforeUserLogin() {
        val url = builder(
            partnerId = "123",
            partnerAuthToken = "PT",
        ).endpointURL(PandoraAPI.Method.userLogin)
        assertEquals(
            "https://tuner.pandora.com/services/json/?partner_id=123&auth_token=PT&method=auth.userLogin",
            url,
        )
    }

    // MARK: Body: partnerLogin (plain JSON, no session fields yet)

    @Test
    fun partnerLoginBodyIsPlainSortedJSON() {
        val body = builder().requestBody(
            parameters = mapOf(
                "username" to JSONValue.Str(PandoraAPI.partnerUsername),
                "password" to JSONValue.Str(PandoraAPI.partnerPassword),
                "deviceModel" to JSONValue.Str(PandoraAPI.partnerDeviceModel),
                "version" to JSONValue.Str(PandoraAPI.version),
            ),
            encrypt = false,
            now = fixedNow,
        )
        assertEquals(
            """{"deviceModel":"android-generic","password":"AC7IBG09A3DTSYM4R41UJWL07VLN8JI7","username":"android","version":"5"}""",
            body.decodeToString(),
        )
    }

    // MARK: Body: session fields

    @Test
    fun syncTimeInjectedWhenOffsetKnown() {
        val body = builder(partnerId = "123", timeOffset = 123.5).requestBody(
            parameters = mapOf("username" to JSONValue.Str("u")),
            encrypt = false,
            now = fixedNow,
        )
        // Int(now + offset) truncates like Python int(): 1_000_000_123.5 → 1_000_000_123
        assertEquals("""{"syncTime":1000000123,"username":"u"}""", body.decodeToString())
    }

    @Test
    fun noSyncTimeBeforeOffsetIsKnown() {
        val body = builder().requestBody(
            parameters = mapOf("username" to JSONValue.Str("u")),
            encrypt = false,
            now = fixedNow,
        )
        assertEquals("""{"username":"u"}""", body.decodeToString())
    }

    @Test
    fun partnerAuthTokenMergedIntoBody() {
        val body = builder(partnerAuthToken = "PT").requestBody(
            parameters = mapOf("username" to JSONValue.Str("u")),
            encrypt = false,
            now = fixedNow,
        )
        assertEquals("""{"partnerAuthToken":"PT","username":"u"}""", body.decodeToString())
    }

    @Test
    fun userAuthTokenWinsOverPartnerAuthTokenInBody() {
        val body = builder(
            partnerAuthToken = "PT",
            userAuthToken = "UT",
        ).requestBody(parameters = emptyMap(), encrypt = false, now = fixedNow)
        assertEquals("""{"userAuthToken":"UT"}""", body.decodeToString())
    }

    // MARK: Body: encryption (userLogin path)

    @Test
    fun encryptedBodyRoundTripsToExpectedJSON() {
        val b = builder(partnerId = "123", partnerAuthToken = "PT", timeOffset = 0.0)
        val body = b.requestBody(
            parameters = mapOf(
                "username" to JSONValue.Str("me@example.com"),
                "password" to JSONValue.Str("hunter2"),
                "loginType" to JSONValue.Str("user"),
                "returnIsSubscriber" to JSONValue.Bool(true),
            ),
            encrypt = true,
            now = fixedNow,
        )

        // Output is hex, not JSON.
        val hex = body.decodeToString()
        assertTrue("body must be hex, got ${hex.take(32)}…", hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })

        // Decrypting with the same key recovers the expected JSON. Trailing
        // zero-padding from the per-chunk pad survives decryption — trim it
        // for comparison (callers do the same for syncTime fields).
        val decrypted = PandoraCrypto.decryptFromHex(hex, PandoraAPI.encryptKey)
        val trimmed = decrypted.takeWhile { it != 0.toByte() }.toByteArray()
        assertEquals(
            """{"loginType":"user","partnerAuthToken":"PT","password":"hunter2","returnIsSubscriber":true,"syncTime":1000000000,"username":"me@example.com"}""",
            trimmed.decodeToString(),
        )
    }

    // MARK: Escaping

    @Test
    fun stringEscapingInJSONBody() {
        val body = builder().requestBody(
            parameters = mapOf("note" to JSONValue.Str("""say "hi"\path""" + "\n")),
            encrypt = false,
            now = fixedNow,
        )
        assertEquals("""{"note":"say \"hi\"\\path\n"}""", body.decodeToString())
    }

    @Test
    fun controlCharacterEscapedAsUnicode() {
        val body = builder().requestBody(
            parameters = mapOf("note" to JSONValue.Str("\u0001")),
            encrypt = false,
            now = fixedNow,
        )
        assertEquals("""{"note":"\u0001"}""", body.decodeToString())
    }

    @Test
    fun boolAndIntLiterals() {
        val body = builder().requestBody(
            parameters = mapOf(
                "a" to JSONValue.Bool(false),
                "b" to JSONValue.Int(42),
            ),
            encrypt = false,
            now = fixedNow,
        )
        assertEquals("""{"a":false,"b":42}""", body.decodeToString())
    }
}
