//
//  LyricsTranslationTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/LyricsTranslationTests.swift — the
//  translation of a lyric set: the model changes (a plain-text
//  translation has to live somewhere), the pure apply helpers (they
//  refuse a misaligned answer rather than shifting every line), and
//  the translator's three outcomes — a translation, "already English"
//  and a failure — which the player shows to the user.
//

package pandoracore

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTranslationTests {

    // MARK: The model

    @Test
    fun plainTranslationIsStored() {
        val lyrics = Lyrics(plainText = "Je suis un chat")
            .applyingPlainTranslation("I am a cat")

        assertEquals("I am a cat", lyrics.plainTranslation)
        assertTrue(lyrics.hasTranslation)
        assertEquals("Je suis un chat", lyrics.plainText)
    }

    @Test
    fun emptyPlainTranslationIsRefused() {
        val original = Lyrics(plainText = "Je suis un chat")

        assertEquals(original, original.applyingPlainTranslation(null))
        assertEquals(original, original.applyingPlainTranslation(""))
        assertFalse(original.hasTranslation)
    }

    @Test
    fun syncedTranslationFillsEveryLine() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timestamp = 0.0, text = "Je suis un chat"),
                LyricLine(timestamp = 5.0, text = "Chat noir"),
            ),
        ).applyingTranslation(listOf("I am a cat", "Black cat"))

        assertEquals(listOf("I am a cat", "Black cat"), lyrics.lines.map { it.translation })
        assertTrue(lyrics.hasTranslation)
    }

    @Test
    fun misalignedTranslationIsRefused() {
        // One answer for two lines would shift the whole alignment —
        // every line would show the wrong words.
        val original = Lyrics(
            lines = listOf(
                LyricLine(timestamp = 0.0, text = "Je suis un chat"),
                LyricLine(timestamp = 5.0, text = "Chat noir"),
            ),
        )

        val updated = original.applyingTranslation(listOf("I am a cat"))

        assertEquals(original, updated)
        assertFalse(updated.hasTranslation)
    }

    @Test
    fun translationOnPlainLyricsIsRefused() {
        // The line helper only applies to synchronised lyrics.
        val original = Lyrics(plainText = "Je suis un chat")

        assertEquals(original, original.applyingTranslation(listOf("I am a cat")))
    }

    @Test
    fun hasTranslationIgnoresEmptyLineTranslations() {
        val lyrics = Lyrics(
            lines = listOf(
                LyricLine(timestamp = 0.0, text = "Hello", translation = ""),
            ),
        )

        assertFalse(lyrics.hasTranslation)
    }

    @Test
    fun decodingALyricsPayloadWithoutThePlainTranslation() {
        // A payload written before `plainTranslation` existed must still
        // decode — a missing key must not throw the lyrics away.
        val json = """
            {"lines":[],"plainText":"Je suis un chat","instrumental":false,"trackID":42}
        """.trimIndent()

        val lyrics = kotlinx.serialization.json.Json.decodeFromString<Lyrics>(json)

        assertEquals("Je suis un chat", lyrics.plainText)
        assertNull(lyrics.plainTranslation)
        assertEquals(42L, lyrics.trackID)
    }

    @Test
    fun encodingKeepsThePlainTranslation() {
        val lyrics = Lyrics(plainText = "Je suis un chat")
            .applyingPlainTranslation("I am a cat")

        val data = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<Lyrics>(),
            lyrics,
        )
        val decoded = kotlinx.serialization.json.Json.decodeFromString<Lyrics>(data)

        assertEquals(lyrics, decoded)
        assertEquals("I am a cat", decoded.plainTranslation)
    }

    // MARK: The translator's three answers

    @Test
    fun translatorReportsTheTranslation() = runTest {
        val response = """
            [[["I am a cat","Je suis un chat",null,null,3]],null,"fr",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val outcome = client.englishTranslation(listOf("Je suis un chat"))

        assertEquals(LyricsTranslation.Translated(listOf("I am a cat")), outcome)
    }

    @Test
    fun translatorReportsEnglishLyrics() = runTest {
        val response = """
            [[["Hallelujah","Hallelujah",null,null,5]],null,"en",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val outcome = client.englishTranslation(listOf("Hallelujah"))

        assertEquals(LyricsTranslation.AlreadyEnglish, outcome)
    }

    @Test
    fun translatorReportsAFailure() = runTest {
        val transport = StubGETTransport(
            listOf(Result.failure(PandoraTransportError("network failure"))),
        )
        val client = GoogleTranslateClient(transport = transport)

        val outcome = client.englishTranslation(listOf("Bonjour"))

        assertEquals(LyricsTranslation.Failed("network failure"), outcome)
    }

    @Test
    fun translatorReportsAMisalignedAnswerAsAFailure() = runTest {
        // Two lines in, but the answer carries no line breaks to
        // rebuild them from — the alignment cannot be trusted.
        val response = """
            [[["I am a cat, and a black one","Je suis un chat, chat noir",null,null,3]],null,"fr",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val outcome = client.englishTranslation(listOf("Je suis un chat", "Chat noir"))

        assertEquals(LyricsTranslation.Failed("unexpected response"), outcome)
    }

    @Test
    fun aSplitLineIsReassembledFromTheSegmentTexts() = runTest {
        // The service splits a *long* line into several segments — the
        // pieces joined are the line's translation, and the answer is
        // not a misalignment.
        val response = """
            [[["I am a ca","Je suis un ",null,null,3],["t","chat",null,null,3]],null,"fr",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val outcome = client.englishTranslation(listOf("Je suis un chat"))

        assertEquals(LyricsTranslation.Translated(listOf("I am a cat")), outcome)
    }

    // MARK: Empty lines and rate limits

    @Test
    fun emptyLinesAreNotSentAndMapBackEmpty() = runTest {
        val response = """
            [[["I walk alone","Je marche seul",null,null,3],["People I don't know","Des gens que je ne connais pas",null,null,3]],null,"fr",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val outcome = client.englishTranslation(
            listOf("Je marche seul", "", "Des gens que je ne connais pas"),
        )

        // The blank section separator gets "" — never a shifted answer.
        assertEquals(
            LyricsTranslation.Translated(listOf("I walk alone", "", "People I don't know")),
            outcome,
        )
        // The blank line never reaches the wire.
        val url = URI(transport.requests[0].url)
        val query = url.rawQuery.split("&")
            .map { pair -> pair.split("=", limit = 2).let { it[0] to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") } }
            .toMap()
        assertEquals("Je marche seul\nDes gens que je ne connais pas", query["q"])
    }

    @Test
    fun rateLimitRetriesAndThenSucceeds() = runTest {
        // The free endpoint 429s by IP; the client backs off and asks
        // again instead of reporting a failed translation.
        val response = """
            [[["I am a cat","Je suis un chat",null,null,3]],null,"fr",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(
            listOf(
                Result.failure(PandoraTransportError("HTTP 429")),
                Result.success(response),
            ),
        )
        val client = GoogleTranslateClient(transport = transport)

        val outcome = client.englishTranslation(listOf("Je suis un chat"))

        assertEquals(LyricsTranslation.Translated(listOf("I am a cat")), outcome)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun rateLimitGivesUpAfterTwoRetries() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.failure(PandoraTransportError("HTTP 429")),
                Result.failure(PandoraTransportError("HTTP 429")),
                Result.failure(PandoraTransportError("HTTP 429")),
            ),
        )
        val client = GoogleTranslateClient(transport = transport)

        val outcome = client.englishTranslation(listOf("Bonjour"))

        assertEquals(LyricsTranslation.Failed("HTTP 429"), outcome)
        assertEquals(3, transport.requests.size)
    }
}
