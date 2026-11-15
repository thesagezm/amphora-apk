//
//  LyricsTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/LyricsTests.swift — the LRC parser:
//  timestamps, multiple stamps per line, metadata tags, plain lyrics,
//  and the "which line is playing?" lookup.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTests {

    // MARK: Parsing

    @Test
    fun parsesTimestampedLines() {
        val raw = """
            [00:12.00] First line
            [00:15.30] Second line
        """.trimIndent()
        val lyrics = LRC.parse(raw)
        assertTrue(lyrics.isSynced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(12.0, lyrics.lines[0].timestamp, 0.001)
        assertEquals("First line", lyrics.lines[0].text)
        assertEquals(15.3, lyrics.lines[1].timestamp, 0.001)
        assertEquals("Second line", lyrics.lines[1].text)
    }

    @Test
    fun parsesMinutesAndHours() {
        assertEquals(90.0, LRC.timestamp("01:30")!!, 0.001)
        assertEquals(600.0, LRC.timestamp("10:00")!!, 0.001)
    }

    @Test
    fun parsesFractionalSeconds() {
        assertEquals(83.4, LRC.timestamp("01:23.4")!!, 0.001)
        assertEquals(83.45, LRC.timestamp("01:23.45")!!, 0.001)
        assertEquals(83.456, LRC.timestamp("01:23.456")!!, 0.001)
        // `mm:ss:ff` — the third field is centiseconds.
        assertEquals(83.45, LRC.timestamp("01:23:45")!!, 0.001)
    }

    @Test
    fun rejectsInvalidTimestamps() {
        assertNull(LRC.timestamp("abc"))
        assertNull(LRC.timestamp("1:2:3:4"))
        assertNull(LRC.timestamp("xx:yy"))
    }

    @Test
    fun multipleTimestampsOnOneLine() {
        val raw = "[00:10.00][01:10.00] chorus"
        val lyrics = LRC.parse(raw)
        assertEquals(2, lyrics.lines.size)
        assertEquals(10.0, lyrics.lines[0].timestamp, 0.001)
        assertEquals(70.0, lyrics.lines[1].timestamp, 0.001)
        assertTrue(lyrics.lines.all { it.text == "chorus" })
    }

    @Test
    fun metadataTagsAreNotLyrics() {
        val raw = """
            [ar:The Artist]
            [ti:The Title]
            [al:The Album]
            [by:Someone]
            [00:05.00] A real line
        """.trimIndent()
        val lyrics = LRC.parse(raw)
        assertEquals(1, lyrics.lines.size)
        assertEquals("A real line", lyrics.lines[0].text)
    }

    @Test
    fun offsetTagShiftsTimestamps() {
        val raw = """
            [offset:500]
            [00:10.00] shifted
        """.trimIndent()
        val lyrics = LRC.parse(raw)
        // A positive offset shifts the lyrics *earlier* by 500 ms.
        assertEquals(9.5, lyrics.lines.firstOrNull()?.timestamp ?: 0.0, 0.001)
    }

    @Test
    fun plainLyricsAreKeptAsText() {
        val raw = """
            Just some words
            on two lines
        """.trimIndent()
        val lyrics = LRC.parse(raw)
        assertFalse(lyrics.isSynced)
        assertEquals("Just some words\non two lines", lyrics.plainText)
    }

    @Test
    fun linesAreSortedByTimestamp() {
        val raw = """
            [00:30.00] later
            [00:10.00] earlier
            [00:20.00] middle
        """.trimIndent()
        val lyrics = LRC.parse(raw)
        assertEquals(listOf("earlier", "middle", "later"), lyrics.lines.map { it.text })
    }

    @Test
    fun emptyDocument() {
        val lyrics = LRC.parse("")
        assertTrue(lyrics.isEmpty)
        assertFalse(lyrics.isSynced)
    }

    // MARK: Playback lookup

    private fun sampleLyrics(): Lyrics = LRC.parse(
        """
        [00:00.00] one
        [00:10.00] two
        [00:20.00] three
        """.trimIndent(),
    )

    @Test
    fun activeLineAtTime() {
        val lyrics = sampleLyrics()
        assertNull(lyrics.activeLine(at = -1.0))
        assertEquals("one", lyrics.activeLine(at = 0.0)?.text)
        assertEquals("one", lyrics.activeLine(at = 9.99)?.text)
        assertEquals("two", lyrics.activeLine(at = 10.0)?.text)
        assertEquals("three", lyrics.activeLine(at = 25.0)?.text)
    }

    @Test
    fun nextLine() {
        val lyrics = sampleLyrics()
        assertEquals("two", lyrics.nextLine(after = 0.0)?.text)
        assertEquals("three", lyrics.nextLine(after = 15.0)?.text)
        assertNull(lyrics.nextLine(after = 25.0))
    }

    @Test
    fun progressRunsFromZeroToOne() {
        val lyrics = sampleLyrics()
        assertEquals(0.0, lyrics.progress(at = 0.0), 0.001)
        assertEquals(0.5, lyrics.progress(at = 5.0), 0.001)
        assertEquals(0.0, lyrics.progress(at = 10.0), 0.001)
    }

    @Test
    fun translationFallsBackToOriginalText() {
        val plain = LyricLine(timestamp = 0.0, text = "Bonjour")
        assertEquals("Bonjour", plain.displayText)
        val translated = LyricLine(timestamp = 0.0, text = "Bonjour", translation = "Hello")
        assertEquals("Hello", translated.displayText)
    }
}
