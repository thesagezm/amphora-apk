//
//  SleepTimer.kt
//  pandoracore
//
//  Port of PandoraCore/SleepTimer.swift — the sleep timer (AGENTS.md
//  feature set: "Sleep timer").
//
//  Pure logic, no timers of its own: the caller supplies the current
//  time and the timer reports when the deadline has passed. Two modes
//  match what music apps do:
//
//  - `duration` — stop after N minutes.
//  - `endOfTrack` — stop when the current track finishes (the caller
//    fires `fired()` when the track ends).
//
//  No Android, no UI — pandoracore stays portable.
//

package pandoracore

import java.time.Instant
import kotlinx.serialization.Serializable

/** A sleep timer with an optional deadline. */
@Serializable
data class SleepTimer(
    /** How the timer ends. */
    val mode: Mode,
    /**
     * When the timer fires (epoch seconds). Null for `endOfTrack` until
     * the track actually ends.
     */
    @kotlinx.serialization.Serializable(with = InstantEpochSecondsSerializer::class)
    val deadline: Instant?,
    /** Minutes requested by the user (0 when the timer is off). */
    val minutes: kotlin.Int,
) {
    enum class Mode {
        /** No timer is set. */
        off,
        /** Stop after a fixed number of minutes. */
        duration,
        /** Stop when the current track ends. */
        endOfTrack,
    }

    /** A timer that stops after `minutes` minutes from `now`. */
    constructor(minutes: kotlin.Int, now: Instant = Instant.now()) : this(
        mode = Mode.duration,
        deadline = now.plusSeconds(maxOf(1, minutes) * 60L),
        minutes = maxOf(1, minutes),
    )

    /** A timer that stops when the current track ends. */
    constructor(endOfTrack: Boolean) : this(
        mode = Mode.endOfTrack,
        deadline = null,
        minutes = 0,
    )

    private constructor() : this(mode = Mode.off, deadline = null, minutes = 0)

    /** `true` when no timer is set. */
    val isOff: Boolean
        get() = mode == Mode.off

    /** `true` when the deadline has passed (duration mode). */
    fun hasExpired(now: Instant = Instant.now()): Boolean =
        deadline?.let { !now.isBefore(it) } ?: false

    /** Seconds left before the deadline (0 when off or expired). */
    fun remaining(now: Instant = Instant.now()): Double =
        deadline?.let { maxOf(0.0, it.epochSecond - now.epochSecond + (it.nano - now.nano) / 1e9) } ?: 0.0

    /**
     * The timer as it should be after the current track ended: a
     * duration timer has expired, an end-of-track timer is done.
     */
    fun fired(): SleepTimer = off

    /** A short label for the UI ("12 min left", "End of track", "Off"). */
    fun label(now: Instant = Instant.now()): String = when (mode) {
        Mode.off -> "Off"
        Mode.endOfTrack -> "End of track"
        Mode.duration -> {
            val deadline = deadline ?: return "Off"
            val remaining = maxOf(0.0, deadline.epochSecond - now.epochSecond + (deadline.nano - now.nano) / 1e9)
            val totalMinutes = kotlin.math.ceil(remaining / 60).toInt()
            if (totalMinutes <= 0) "Off" else "$totalMinutes min left"
        }
    }

    companion object {
        /** The timer is off (no deadline set). */
        val off = SleepTimer()
    }
}
