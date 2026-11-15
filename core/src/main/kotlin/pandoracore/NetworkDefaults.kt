//
//  NetworkDefaults.kt
//  pandoracore
//
//  Port of PandoraCore/NetworkDefaults.swift — shared network defaults for
//  every transport in this package — and the app target, which imports
//  this module.
//

package pandoracore

object NetworkDefaults {
    /**
     * The request timeout each transport applies to its request, in
     * seconds.
     *
     * A bare HTTP request inherits generous defaults, so on a connection
     * that was accepted but never answers (a captive portal, a suspended
     * socket, a hostile middlebox) the send hangs effectively forever and
     * the UI spins with it. Every transport therefore names an interval
     * explicitly (radio's directory pings use a shorter one, 15 s, by
     * design).
     *
     * 30 s is long enough for a slow cellular fetch on any path —
     * playlists, station modes, lyrics, scrobbles, artwork — and short
     * enough that the app's error/retry UX engages within a human
     * attention span.
     */
    const val requestTimeoutInterval: Double = 30.0
}
