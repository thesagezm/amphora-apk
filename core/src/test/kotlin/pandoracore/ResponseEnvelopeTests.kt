//
//  ResponseEnvelopeTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/ResponseEnvelopeTests.swift.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseEnvelopeTests {

    @Test
    fun okEnvelopeReturnsResult() {
        val payload =
            """{"stat":"ok","result":{"partnerId":"123","partnerAuthToken":"PT"}}""".toByteArray()
        val result = ResponseEnvelope.result(payload)
        assertEquals("123", result.string("partnerId"))
        assertEquals("PT", result.string("partnerAuthToken"))
    }

    @Test
    fun failEnvelopeThrowsTypedAPIError() {
        val payload =
            """{"stat":"fail","code":1002,"message":"Invalid username or password"}""".toByteArray()
        val e = assertThrows(PandoraAPIError::class.java) {
            ResponseEnvelope.result(payload)
        }
        assertEquals(1002, e.code)
        // Raw server message stays verbatim; the friendly copy is separate.
        assertEquals("Invalid username or password", e.message)
        assertTrue(e.isInvalidCredentials)
        assertTrue(!e.isSessionExpired)
    }

    @Test
    fun invalidAuthTokenIsSessionExpiry() {
        val payload = """{"stat":"fail","code":1001,"message":"auth token invalid"}""".toByteArray()
        val e = assertThrows(PandoraAPIError::class.java) {
            ResponseEnvelope.result(payload)
        }
        assertTrue(e.isSessionExpired)
    }

    @Test
    fun undocumentedCodeStillTyped() {
        val payload = """{"stat":"fail","code":9999,"message":"mystery"}""".toByteArray()
        val e = assertThrows(PandoraAPIError::class.java) {
            ResponseEnvelope.result(payload)
        }
        assertEquals(9999, e.code)
        assertEquals("Pandora error 9999: mystery", e.localizedDescription)
    }

    @Test
    fun malformedBodyIsTypedError() {
        assertThrows(PandoraMalformedResponseError::class.java) {
            ResponseEnvelope.result("not json".toByteArray())
        }
    }

    @Test
    fun okWithoutResultIsTypedError() {
        assertThrows(PandoraMalformedResponseError::class.java) {
            ResponseEnvelope.result("""{"stat":"ok"}""".toByteArray())
        }
    }

    @Test
    fun unknownStatIsTypedError() {
        assertThrows(PandoraMalformedResponseError::class.java) {
            ResponseEnvelope.result("""{"stat":"weird","result":{}}""".toByteArray())
        }
    }

    @Test
    fun friendlyMessagesForCuratedCodes() {
        val invalidLogin = PandoraAPIError(1002, "server said no")
        assertEquals("Invalid username or password.", invalidLogin.localizedDescription)
        val maintenance = PandoraAPIError(1, "maintenance")
        assertTrue(maintenance.localizedDescription.contains("maintenance"))
    }
}
