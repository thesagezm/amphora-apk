//
//  RadioStationIdentityTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/RadioStationIdentityTests.swift — the
//  generated radio-station artwork: the monogram and the hue that comes
//  with it. The hue must be stable across launches (platform hash codes
//  are seeded per process and would change colors on every launch), so
//  the hash is pinned by these vectors — and they pin the ANDROID port
//  to the same numbers the iOS app uses.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStationIdentityTests {

    // MARK: Initials

    @Test
    fun initialsUseTheFirstLetterOfTwoWords() {
        assertEquals("RP", RadioStationIdentity.initials("Radio Paradise"))
    }

    @Test
    fun initialsUseOneLetterForASingleWord() {
        assertEquals("K", RadioStationIdentity.initials("KEXP"))
    }

    @Test
    fun initialsIgnoreSurroundingAndRepeatedWhitespace() {
        assertEquals("RP", RadioStationIdentity.initials("  Radio   Paradise  "))
    }

    @Test
    fun initialsFallBackToRForAnEmptyName() {
        assertEquals("R", RadioStationIdentity.initials(""))
        assertEquals("R", RadioStationIdentity.initials("   "))
    }

    @Test
    fun initialsKeepNonLatinLetters() {
        // The first letter is taken as a whole character, not a byte.
        assertEquals("РР", RadioStationIdentity.initials("Радио Рекорд"))
    }

    // MARK: Hue

    @Test
    fun hueIsInTheUnitRange() {
        for (name in listOf("Radio Paradise", "KEXP", "BBC Radio 4", "Éire FM", "")) {
            val hue = RadioStationIdentity.hue(name)
            assertTrue(hue >= 0.0)
            assertTrue(hue < 1.0)
        }
    }

    @Test
    fun hueIsStableForTheSameName() {
        // The same station must not change color between launches — the
        // whole point of hashing the name instead of a seeded hash.
        assertEquals(
            RadioStationIdentity.hue("Radio Paradise"),
            RadioStationIdentity.hue("Radio Paradise"),
            0.0,
        )
    }

    @Test
    fun hueIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(
            RadioStationIdentity.hue("Radio Paradise"),
            RadioStationIdentity.hue("  radio paradise  "),
            0.0,
        )
    }

    @Test
    fun differentNamesGetDifferentHues() {
        assertNotEquals(
            RadioStationIdentity.hue("Radio Paradise"),
            RadioStationIdentity.hue("Radio Skywave"),
        )
    }

    @Test
    fun hashIsTheKnownFNV1aValue() {
        // FNV-1a over the UTF-8 bytes, lowercased first. Pinned so a
        // change in the hashing algorithm (which would recolor every
        // station on every device) cannot slip through.
        assertEquals(0xfc4c4c1du, RadioStationIdentity.fnv1a("kexp"))
    }
}
