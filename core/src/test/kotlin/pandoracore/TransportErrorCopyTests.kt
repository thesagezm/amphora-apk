//
//  TransportErrorCopyTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/TransportErrorCopyTests.swift — the transport
//  error's user-facing copy: the raw `reason` strings the transports
//  produce ("unreachable", "timeout", "network failure", "HTTP 502",
//  "non-HTTP response") must each map to words a user can act on, and
//  nothing else may leak into the UI.
//
//  Divergence from iOS: `testURLErrorClassification` is not ported here —
//  URLError is Apple-only. The cause classification (timeout / unreachable
//  / network failure) lives in the app-side OkHttp adapter, whose tests
//  cover the same mapping.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportErrorCopyTests {

    @Test
    fun unreachableCopy() {
        assertEquals(
            "No internet connection. Amphora will try again.",
            PandoraTransportError("unreachable").localizedDescription,
        )
    }

    @Test
    fun timeoutCopy() {
        assertEquals(
            "Pandora took too long to respond. Check your connection and try again.",
            PandoraTransportError("timeout").localizedDescription,
        )
    }

    @Test
    fun networkFailureCopy() {
        assertEquals(
            "Couldn't reach Pandora. Check your connection and try again.",
            PandoraTransportError("network failure").localizedDescription,
        )
    }

    @Test
    fun httpStatusCodeIsExtracted() {
        val error = PandoraTransportError("HTTP 502")
        assertEquals(502, error.httpStatusCode)
        assertEquals(
            "Pandora's servers had a problem (HTTP 502). Try again.",
            error.localizedDescription,
        )
    }

    @Test
    fun httpReasonWithoutCodeFallsBackGenerically() {
        val error = PandoraTransportError("HTTP ")
        assertNull(error.httpStatusCode)
        assertEquals(
            "Something went wrong talking to Pandora. Try again.",
            error.localizedDescription,
        )
    }

    @Test
    fun unknownReasonNeverLeaksTheRawString() {
        val error = PandoraTransportError("some internal detail")
        assertEquals(
            "Something went wrong talking to Pandora. Try again.",
            error.localizedDescription,
        )
        // The raw reason stays available for the log.
        assertEquals("some internal detail", error.reason)
    }
}
