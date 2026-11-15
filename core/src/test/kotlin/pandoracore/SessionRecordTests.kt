//
//  SessionRecordTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/SessionRecordTests.swift — serialization
//  round-trip for the persisted session record (what the app's Keystore
//  store encodes) plus InMemorySessionStore semantics. A key rename here
//  would strand existing installs' stored sessions and break warm-launch
//  auto-login, so the literal fixture is load-bearing.
//

package pandoracore

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRecordTests {

    private val record = SessionRecord(
        credentials = Credentials(username = "me@example.com", password = "hunter2"),
        tokens = SessionTokens(
            partnerId = "77",
            partnerAuthToken = "P-TOKEN",
            userId = "u-1",
            userAuthToken = "U-TOKEN",
            timeOffset = 760_000_000.5,
        ),
        isSubscriber = true,
    )

    @Test
    fun sessionRecordEncodesAndDecodesLosslessly() {
        val data = Json.encodeToString(record)
        val decoded = Json.decodeFromString<SessionRecord>(data)
        assertEquals(record, decoded)
    }

    @Test
    fun sessionRecordDecodesFromFixedJSONFixture() {
        // Exact shape the store layer persists. Decoding a literal guards
        // against a synthesized-serializer property rename, which would
        // silently discard stored sessions on upgrade.
        val json = """
            {"credentials":{"username":"me@example.com","password":"hunter2"},
            "tokens":{"partnerId":"77","partnerAuthToken":"P-TOKEN","userId":"u-1",
            "userAuthToken":"U-TOKEN","timeOffset":760000000.5},
            "isSubscriber":true}
        """.trimIndent()
        val decoded = Json.decodeFromString<SessionRecord>(json)
        assertEquals(record, decoded)
    }

    @Test
    fun inMemorySessionStoreSaveLoadClear() {
        val store = InMemorySessionStore()
        assertNull(store.load())
        store.save(record)
        assertEquals(record, store.load())
        store.clear()
        assertNull(store.load())
    }
}
