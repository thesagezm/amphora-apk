//
//  SleepTimerTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/SleepTimerTests.swift — the sleep timer's
//  deadline math and labels.
//

package pandoracore

import java.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTests {

    private val now = Instant.ofEpochSecond(1_000_000_000)

    @Test
    fun durationTimerExpiresAtDeadline() {
        val timer = SleepTimer(minutes = 15, now = now)
        assertFalse(timer.isOff)
        assertFalse(timer.hasExpired(now = now))
        assertFalse(timer.hasExpired(now = now.plusSeconds(14 * 60)))
        assertTrue(timer.hasExpired(now = now.plusSeconds(15 * 60)))
    }

    @Test
    fun remainingCountsDown() {
        val timer = SleepTimer(minutes = 10, now = now)
        assertEquals(600.0, timer.remaining(now = now), 0.001)
        assertEquals(360.0, timer.remaining(now = now.plusSeconds(240)), 0.001)
        assertEquals(0.0, timer.remaining(now = now.plusSeconds(9_000)), 0.001)
    }

    @Test
    fun minutesBelowOneAreClamped() {
        val timer = SleepTimer(minutes = 0, now = now)
        assertEquals(1, timer.minutes)
        assertEquals(60.0, timer.remaining(now = now), 0.001)
    }

    @Test
    fun endOfTrackTimerHasNoDeadline() {
        val timer = SleepTimer(endOfTrack = true)
        assertEquals(SleepTimer.Mode.endOfTrack, timer.mode)
        assertFalse(timer.hasExpired(now = now))
        assertEquals(0.0, timer.remaining(now = now), 0.0)
    }

    @Test
    fun firedClearsTheTimer() {
        val timer = SleepTimer(minutes = 5, now = now)
        assertTrue(timer.fired().isOff)
    }

    @Test
    fun offTimerIsOff() {
        assertTrue(SleepTimer.off.isOff)
        assertFalse(SleepTimer.off.hasExpired(now = now))
        assertEquals("Off", SleepTimer.off.label(now = now))
    }

    @Test
    fun labelRoundsUpRemainingMinutes() {
        val timer = SleepTimer(minutes = 15, now = now)
        assertEquals("15 min left", timer.label(now = now))
        assertEquals("14 min left", timer.label(now = now.plusSeconds(60)))
        assertEquals("Off", timer.label(now = now.plusSeconds(15 * 60)))
    }

    @Test
    fun endOfTrackLabel() {
        assertEquals("End of track", SleepTimer(endOfTrack = true).label(now = now))
    }

    @Test
    fun serializableRoundTrip() {
        val timer = SleepTimer(minutes = 20, now = now)
        val data = Json.encodeToString(timer)
        val restored = Json.decodeFromString<SleepTimer>(data)
        assertEquals(timer.mode, restored.mode)
        assertEquals(timer.minutes, restored.minutes)
        assertEquals(timer.deadline, restored.deadline)
    }
}
