//
//  TransportTimeoutTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/TransportTimeoutTests.swift — the transport's
//  own timeout: the system default leaves long resource timeouts, so a
//  connection that was accepted but never answers would hang the request —
//  and the UI — effectively forever. `TransportRequest.make` is pure, so
//  these assert the wire shape without a network.
//
//  Divergence from iOS: the Swift tests construct `URLSessionTransport`;
//  the JVM port tests the pure `TransportRequest` assembly that the
//  app-side OkHttp adapter consumes (the adapter's own timeout application
//  is covered by its app-side tests).
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTimeoutTests {
    private val url = "https://tuner.pandora.com/services/json/"

    @Test
    fun requestCarriesTheDefaultTimeout() {
        val request = TransportRequest.make(
            url = url, body = ByteArray(0), contentType = "application/x-www-form-urlencoded",
        )
        assertEquals(TransportRequest.DEFAULT_TIMEOUT_INTERVAL, request.timeoutSeconds, 0.0)
        assertEquals(NetworkDefaults.requestTimeoutInterval, TransportRequest.DEFAULT_TIMEOUT_INTERVAL, 0.0)
    }

    @Test
    fun customTimeoutIsHonored() {
        val request = TransportRequest.make(
            url = url, body = ByteArray(0), contentType = "application/json", timeoutInterval = 8.0,
        )
        assertEquals(8.0, request.timeoutSeconds, 0.0)
    }

    @Test
    fun requestAssemblyIsUnchanged() {
        val request = TransportRequest.make(
            url = url, body = "enc=payload".toByteArray(), contentType = "application/x-www-form-urlencoded",
        )
        assertEquals("POST", request.method)
        assertEquals("application/x-www-form-urlencoded", request.contentType)
        assertEquals("Amphora", request.userAgent)
        assertTrue(request.body.contentEquals("enc=payload".toByteArray()))
        assertEquals(url, request.url)
    }

    @Test
    fun timeoutIsWellBounded() {
        // A hang must recover inside a human attention span, and a
        // legitimate fetch (slow cellular playlist) must not time out.
        val interval = TransportRequest.DEFAULT_TIMEOUT_INTERVAL
        assertTrue(interval >= 15)
        assertTrue(interval <= 60)
    }

    @Test
    fun mainTransportSharesThePackageDefault() {
        // The main transport and the shared constant must not drift
        // apart — every other transport (lyrics GET, Last.fm, station
        // modes, CSRF bootstrap, the app's artwork cache) applies
        // NetworkDefaults.requestTimeoutInterval directly.
        assertEquals(
            NetworkDefaults.requestTimeoutInterval,
            TransportRequest.DEFAULT_TIMEOUT_INTERVAL,
            0.0,
        )
        assertTrue(NetworkDefaults.requestTimeoutInterval > 0)
    }
}
