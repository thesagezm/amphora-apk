//
//  LyricsRomanizationTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/LyricsRomanizationTests.swift — the alphabet
//  romanization of non-Latin-script lyrics (Google's `dt=rm` mode),
//  against scripted GET responses. No network in any unit test.
//
//  The reply shapes are the ones the live endpoint returns (verified
//  against the real service: "사랑해" → "salanghae", "君の名は" →
//  "Kiminonaha"; Latin-script input answers with no segments).
//

package pandoracore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRomanizationTests {

    private fun romanizationReply(romanized: String, language: String = "ko"): ByteArray =
        // One segment per call; the text sits at index 3.
        """
        [[[null,null,null,"$romanized"]],null,"$language",null,null,null,1]
        """.trimIndent().toByteArray()

    // MARK: The endpoint

    @Test
    fun romanizesKoreanLine() = runTest {
        val transport = StubGETTransport(listOf(Result.success(romanizationReply("salanghae"))))
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(listOf("사랑해"))

        assertEquals(listOf("salanghae"), romanized)
        val request = transport.requests.first()
        val url = request.url
        assertTrue("the romanization mode must be requested", url.contains("dt=rm"))
        assertTrue(url.contains("sl=auto"))
        assertTrue(url.contains("q="))
        assertNotNull(request.headers["User-Agent"])
    }

    @Test
    fun multiLineReplyPreservesLineAlignment() = runTest {
        // Verified live: the reply keeps the line breaks, so one segment
        // can carry several romanized lines.
        val transport = StubGETTransport(
            listOf(Result.success(romanizationReply("salanghae\\nannyeong"))),
        )
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(listOf("사랑해", "안녕"))

        assertEquals(listOf("salanghae", "annyeong"), romanized)
    }

    @Test
    fun oneSegmentPerLineAlsoAligns() = runTest {
        // The service may also return one segment per line with no
        // embedded breaks.
        val response = """
            [[[null,null,null,"salanghae"],[null,null,null,"annyeong"]],null,"ko",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(listOf("사랑해", "안녕"))

        assertEquals(listOf("salanghae", "annyeong"), romanized)
    }

    @Test
    fun japaneseRomanizesToAlphabet() = runTest {
        val transport = StubGETTransport(
            listOf(Result.success(romanizationReply("Kiminonaha", language = "ja"))),
        )
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(listOf("君の名は"))

        assertEquals(listOf("Kiminonaha"), romanized)
    }

    // MARK: The gates that keep the request count down

    @Test
    fun latinScriptSkipsTheRequestEntirely() = runTest {
        val transport = StubGETTransport(emptyList())
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(listOf("Hallelujah", "je t'aime"))

        assertNull(romanized)
        // English/French lyrics must cost zero extra Google requests —
        // the endpoint rate-limits probing IPs.
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun noSegmentsReplyReturnsNil() = runTest {
        // Latin-script input answers `[null,null,"en",…]` — no segments.
        // Even for non-Latin input the same shape means "nothing to do".
        val response = """
            [null,null,"en",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(listOf("사랑해"))

        assertNull(romanized)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun misalignedReplyIsNeverShown() = runTest {
        // Two input lines but one romanized line — showing it would
        // shift every later line.
        val transport = StubGETTransport(
            listOf(Result.success(romanizationReply("salanghae"))),
        )
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(listOf("사랑해", "안녕"))

        assertNull(romanized)
    }

    @Test
    fun transportFailureReturnsNil() = runTest {
        val transport = StubGETTransport(
            listOf(Result.failure(PandoraTransportError("network failure"))),
        )
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(listOf("사랑해"))

        assertNull(romanized)
    }

    @Test
    fun emptyInputNeedsNoRequest() = runTest {
        val transport = StubGETTransport(emptyList())
        val client = GoogleTranslateClient(transport = transport)

        val romanized = client.romanization(emptyList())

        assertNull(romanized)
        assertTrue(transport.requests.isEmpty())
    }

    // MARK: The script check

    @Test
    fun nonLatinScriptsAreDetected() {
        assertTrue(GoogleTranslateClient.containsNonLatinScript("사랑해")) // Hangul
        assertTrue(GoogleTranslateClient.containsNonLatinScript("君の名は")) // kanji + kana
        assertTrue(GoogleTranslateClient.containsNonLatinScript("ひらがな")) // hiragana
        assertTrue(GoogleTranslateClient.containsNonLatinScript("カタカナ")) // katakana
        assertTrue(GoogleTranslateClient.containsNonLatinScript("我爱你")) // CJK
        assertTrue(GoogleTranslateClient.containsNonLatinScript("สวัสดี")) // Thai
        assertTrue(GoogleTranslateClient.containsNonLatinScript("Привет")) // Cyrillic
    }

    @Test
    fun latinScriptIsNotDetected() {
        assertFalse(GoogleTranslateClient.containsNonLatinScript("Hallelujah"))
        assertFalse(GoogleTranslateClient.containsNonLatinScript("je t'aime déjà"))
        assertFalse(GoogleTranslateClient.containsNonLatinScript("Saudade coração"))
        assertFalse(GoogleTranslateClient.containsNonLatinScript("1234 — hey!"))
        assertFalse(GoogleTranslateClient.containsNonLatinScript(""))
    }

    @Test
    fun mixedScriptIsDetected() {
        // A line with a single non-Latin word still needs romanization.
        assertTrue(GoogleTranslateClient.containsNonLatinScript("I love you, 사랑해"))
    }

    // MARK: The model

    @Test
    fun lyricLineDecodesWithoutRomanization() {
        // A cached lyric line from an older build carries no romanization.
        val data = """{"timestamp":12.0,"text":"사랑해","translation":"love"}"""
        val line = kotlinx.serialization.json.Json.decodeFromString<LyricLine>(data)

        assertEquals("사랑해", line.text)
        assertEquals("love", line.translation)
        assertNull(line.romanization)
    }

    @Test
    fun lyricLineRoundTripsWithRomanization() {
        val line = LyricLine(timestamp = 12.0, text = "사랑해", translation = "love", romanization = "salanghae")
        val data = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<LyricLine>(),
            line,
        )
        val decoded = kotlinx.serialization.json.Json.decodeFromString<LyricLine>(data)

        assertEquals(line, decoded)
    }
}
