//
//  RadioStreamMetadataTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/RadioStreamMetadataTests.swift — the ICY
//  now-playing metadata: the byte state machine that finds the metadata
//  blocks in a live stream, and the "Artist - Title" split.
//
//  The byte fixtures are the real thing — the block below is what
//  Europe 1 answered with when the stream was probed live.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioStreamMetadataTests {

    /**
     * Builds the byte flow a stream produces: `interval` bytes of
     * audio, one length byte (in blocks of 16), then the metadata.
     */
    private fun streamBytes(interval: kotlin.Int, metadata: String): ByteArray {
        val data = ByteArray(interval) { 0x41 }
        val paddedLength = ((metadata.toByteArray(Charsets.UTF_8).size + 15) / 16) * 16
        val padded = metadata.padEnd(paddedLength, '\u0000')
        val paddedBytes = padded.toByteArray(Charsets.UTF_8)
        return data + byteArrayOf((paddedBytes.size / 16).toByte()) + paddedBytes
    }

    // MARK: The byte state machine

    @Test
    fun theMetadataBlockIsFoundAfterTheInterval() {
        val parser = ICYMetadataParser()
        parser.setMetadataInterval(8)

        val blocks = parser.consume(
            streamBytes(interval = 8, metadata = "StreamTitle='Europe 1 - La radio libre';StreamUrl='';"),
        )

        assertEquals(1, blocks.size)
        assertTrue(blocks[0].startsWith("StreamTitle='Europe 1 - La radio libre';"))
    }

    @Test
    fun theBlockIsFoundWhenItArrivesInPieces() {
        // The stream delivers whatever it likes: the audio, the length
        // byte and the block can be split across any number of reads.
        val parser = ICYMetadataParser()
        parser.setMetadataInterval(4)
        val bytes = streamBytes(
            interval = 4,
            metadata = "StreamTitle='Artist - Title';StreamUrl='';",
        )

        val blocks = mutableListOf<String>()
        for (byte in bytes) {
            blocks += parser.consume(byteArrayOf(byte))
        }

        assertEquals(1, blocks.size)
        assertTrue(blocks[0].startsWith("StreamTitle='Artist - Title';"))
    }

    @Test
    fun aZeroLengthMeansNothingChanged() {
        // A length byte of 0 is the server saying "same as before" —
        // no block, and the parser stays in step with the audio.
        val parser = ICYMetadataParser()
        parser.setMetadataInterval(4)

        val data = ByteArray(4) { 0x41 } + byteArrayOf(0)
        val blocks = parser.consume(data)

        assertTrue(blocks.isEmpty())
        assertEquals(ICYMetadataParser.State.Audio(remaining = 4), parser.state)
    }

    @Test
    fun twoBlocksInOneRead() {
        val parser = ICYMetadataParser()
        parser.setMetadataInterval(4)

        val data = streamBytes(interval = 4, metadata = "StreamTitle='A - One';") +
            streamBytes(interval = 4, metadata = "StreamTitle='B - Two';")

        val blocks = parser.consume(data)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0].contains("A - One"))
        assertTrue(blocks[1].contains("B - Two"))
    }

    @Test
    fun aStreamWithoutAnIntervalNeverReportsMetadata() {
        val parser = ICYMetadataParser()
        parser.setMetadataInterval(0)

        val blocks = parser.consume(ByteArray(64) { 0x41 })

        assertTrue(blocks.isEmpty())
        assertFalse(parser.isSupported)
        assertEquals(ICYMetadataParser.State.Idle, parser.state)
    }

    @Test
    fun theIntervalIsReadFromTheHeaders() {
        // Servers spell the header every way there is.
        assertEquals(16000, ICYMetadataParser.metadataInterval(mapOf("icy-metaint" to "16000")))
        assertEquals(1024, ICYMetadataParser.metadataInterval(mapOf("Icy-MetaInt" to "1024")))
        assertEquals(8192, ICYMetadataParser.metadataInterval(mapOf("ICY-METAINT" to " 8192 ")))
        assertNull(ICYMetadataParser.metadataInterval(mapOf("content-type" to "audio/mpeg")))
        assertNull(ICYMetadataParser.metadataInterval(mapOf("icy-metaint" to "0")))
        assertNull(ICYMetadataParser.metadataInterval(mapOf("icy-metaint" to "nonsense")))
    }

    // MARK: The announcement

    @Test
    fun theStreamTitleIsExtracted() {
        val block = "StreamTitle='Europe 1 - La radio libre';StreamUrl='';"

        assertEquals(
            "Europe 1 - La radio libre",
            RadioStreamMetadata.streamTitle(block),
        )
    }

    @Test
    fun anEmptyStreamTitleIsNoSong() {
        assertNull(RadioStreamMetadata.streamTitle("StreamTitle='';StreamUrl='1';"))
        assertNull(RadioStreamMetadata.streamTitle("StreamUrl='1';"))
    }

    @Test
    fun theArtistAndTitleAreSplit() {
        val nowPlaying = RadioStreamMetadata.nowPlayingFromStreamTitle(
            "Daft Punk - Around the World",
        )

        assertEquals("Daft Punk", nowPlaying?.artist)
        assertEquals("Around the World", nowPlaying?.title)
        assertEquals("Daft Punk Around the World", nowPlaying?.lookupQuery)
    }

    @Test
    fun aTitleWithoutAnArtistIsKeptWhole() {
        val nowPlaying = RadioStreamMetadata.nowPlayingFromStreamTitle("Around the World")

        assertNull(nowPlaying?.artist)
        assertEquals("Around the World", nowPlaying?.title)
        assertEquals("Around the World", nowPlaying?.lookupQuery)
    }

    @Test
    fun onlyTheFirstSeparatorSplits() {
        // "Artist - Title - Live" must not lose its tail.
        val nowPlaying = RadioStreamMetadata.nowPlayingFromStreamTitle(
            "Nina Simone - Feeling Good - Live At Montreux",
        )

        assertEquals("Nina Simone", nowPlaying?.artist)
        assertEquals("Feeling Good - Live At Montreux", nowPlaying?.title)
    }

    @Test
    fun anApostropheInTheTitleSurvives() {
        val nowPlaying = RadioStreamMetadata.nowPlayingFromStreamTitle(
            "Celine Dion - J'ai tout oublié pour toi",
        )

        assertEquals("Celine Dion", nowPlaying?.artist)
        assertEquals("J'ai tout oublié pour toi", nowPlaying?.title)
    }

    @Test
    fun aBareHyphenIsNotASeparator() {
        // A hyphen without spaces is part of the title far more often
        // than it separates an artist from a title.
        val nowPlaying = RadioStreamMetadata.nowPlayingFromStreamTitle("Love-Hate")

        assertNull(nowPlaying?.artist)
        assertEquals("Love-Hate", nowPlaying?.title)
    }

    @Test
    fun aSpacedHyphenDoesSeparate() {
        val nowPlaying = RadioStreamMetadata.nowPlayingFromStreamTitle("Love - Hate")

        assertEquals("Love", nowPlaying?.artist)
        assertEquals("Hate", nowPlaying?.title)
    }

    @Test
    fun theSongIsReadStraightFromABlock() {
        val nowPlaying = RadioStreamMetadata.nowPlaying(
            "StreamTitle='Europe 1 - La radio libre';StreamUrl='';",
        )

        assertEquals("Europe 1", nowPlaying?.artist)
        assertEquals("La radio libre", nowPlaying?.title)
    }
}
