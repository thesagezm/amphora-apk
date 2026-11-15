//
//  ArtworkURLTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/ArtworkURLTests.swift — the artwork candidate
//  list is the fix for radio stations whose favicon never loaded: the
//  HTTPS-preferred URL is tried first and the raw URL (scheme preserved)
//  is the last resort, so an HTTP-only favicon host still gets a chance.
//
//  These vectors pin the exact candidate lists — the order matters (the
//  raw URL is only reached when the HTTPS attempt failed) and the
//  scheme of the second candidate must survive untouched.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkURLTests {

    // MARK: The raw-HTTP case (the radio favicon)

    @Test
    fun httpRawURLYieldsHTTPSFirstAndRawHTTPSecond() {
        val candidates = ArtworkURLCandidates.candidates("http://example.com/favicon.png")
        assertEquals(2, candidates.size)
        assertEquals("https://example.com/favicon.png", candidates[0])
        assertEquals("http://example.com/favicon.png", candidates[1])
    }

    @Test
    fun httpRawURLKeepsTheQueryStringOnBothCandidates() {
        val candidates = ArtworkURLCandidates.candidates(
            "http://example.com/favicon.png?v=2",
        )
        assertEquals(2, candidates.size)
        assertEquals("https://example.com/favicon.png?v=2", candidates[0])
        assertEquals("http://example.com/favicon.png?v=2", candidates[1])
    }

    // MARK: Already-HTTPS URLs

    @Test
    fun httpsRawURLYieldsASingleCandidate() {
        val candidates = ArtworkURLCandidates.candidates("https://example.com/favicon.png")
        assertEquals(1, candidates.size)
        assertEquals("https://example.com/favicon.png", candidates.first())
    }

    // MARK: Scheme-less URLs (Pandora's artwork URLs are not always absolute)

    @Test
    fun schemelessURLYieldsOneHTTPSCandidate() {
        val candidates = ArtworkURLCandidates.candidates("example.com/favicon.png")
        assertEquals(1, candidates.size)
        assertEquals("https://example.com/favicon.png", candidates.first())
    }

    @Test
    fun schemelessURLWithPathOnlyYieldsOneHTTPSCandidate() {
        val candidates = ArtworkURLCandidates.candidates("images.example.com/favicon.png")
        assertEquals(1, candidates.size)
        assertEquals("https://images.example.com/favicon.png", candidates.first())
    }

    // MARK: Malformed input

    @Test
    fun spacesArePercentEncoded() {
        val candidates = ArtworkURLCandidates.candidates("https://example.com/my icon.png")
        assertEquals(1, candidates.size)
        assertEquals("https://example.com/my%20icon.png", candidates.first())
    }

    @Test
    fun spacesInAnHTTPRawURLArePercentEncodedOnBothCandidates() {
        val candidates = ArtworkURLCandidates.candidates("http://example.com/my icon.png")
        assertEquals(2, candidates.size)
        assertEquals("https://example.com/my%20icon.png", candidates[0])
        assertEquals("http://example.com/my%20icon.png", candidates[1])
    }

    @Test
    fun emptyStringYieldsNoCandidates() {
        assertTrue(ArtworkURLCandidates.candidates("").isEmpty())
    }

    @Test
    fun whitespaceOnlyStringYieldsNoCandidates() {
        assertTrue(ArtworkURLCandidates.candidates("   \n ").isEmpty())
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        val candidates = ArtworkURLCandidates.candidates("  http://example.com/favicon.png  ")
        assertEquals(2, candidates.size)
        assertEquals("https://example.com/favicon.png", candidates[0])
        assertEquals("http://example.com/favicon.png", candidates[1])
    }

    // MARK: The two halves on their own

    @Test
    fun httpsPreferredURLUpgradesHTTP() {
        val url = ArtworkURLCandidates.httpsPreferredURL("http://example.com/favicon.png")!!
        assertTrue(url.startsWith("https://"))
        assertEquals("https://example.com/favicon.png", url)
    }

    @Test
    fun rawURLPreservesTheHTTPItWasGiven() {
        val url = ArtworkURLCandidates.rawURL("http://example.com/favicon.png")!!
        assertTrue(url.startsWith("http://"))
        assertEquals("http://example.com/favicon.png", url)
    }

    @Test
    fun rawURLIsNullWithoutAScheme() {
        // Nothing to preserve — the scheme-less form is handled by the
        // HTTPS-preferred candidate alone.
        assertNull(ArtworkURLCandidates.rawURL("example.com/favicon.png"))
    }

    @Test
    fun rawURLIsNullForAnEmptyString() {
        assertNull(ArtworkURLCandidates.rawURL(""))
    }
}
