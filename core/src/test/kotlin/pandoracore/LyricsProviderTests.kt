//
//  LyricsProviderTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/LyricsProviderTests.swift — the lrclib.net
//  client and the translation client, against scripted GET responses.
//  No network in any unit test.
//
//  The response shapes are the ones the live service returns (verified
//  against lrclib's own server source, `server/src/routes/*.rs`).
//

package pandoracore

import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escapes a string for a JSON fixture (the newline must become `\n`). */
internal fun jsonEscaped(text: String): String = text
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

class LyricsProviderTests {

    private fun track(
        title: String = "Creep",
        artist: String = "Radiohead",
        album: String = "Pablo Honey",
        duration: kotlin.Int = 239,
    ) = Track(
        trackToken = "TT-1",
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        stationId = "S-1",
    )

    private fun recordJSON(
        synced: String? = "[00:12.00]When you were here before\n[00:15.00]Couldn't look you in the eye",
        plain: String? = "When you were here before",
        instrumental: Boolean = false,
    ): ByteArray {
        val syncedField = synced?.let { "\"${jsonEscaped(it)}\"" } ?: "null"
        val plainField = plain?.let { "\"${jsonEscaped(it)}\"" } ?: "null"
        return """
        {"id":496,"name":"Creep","trackName":"Creep","artistName":"Radiohead",
        "albumName":"Pablo Honey","duration":239.0,"instrumental":$instrumental,
        "hasWordSync":false,"plainLyrics":$plainField,"syncedLyrics":$syncedField,
        "lyricsfile":null}
        """.trimIndent().toByteArray()
    }

    // MARK: lrclib

    @Test
    fun fetchesSyncedLyricsByMetadata() = runTest {
        val transport = StubGETTransport(listOf(Result.success(recordJSON())))
        val client = LRCLibClient(transport = transport)

        val lyrics = client.lyrics(track())

        assertTrue(lyrics.isSynced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(12.0, lyrics.lines[0].timestamp, 0.001)
        assertEquals("When you were here before", lyrics.lines[0].text)
        assertEquals(496L, lyrics.trackID)
        assertTrue(!lyrics.instrumental)

        val request = transport.requests.first()
        val url = request.url
        assertTrue(url.startsWith("https://lrclib.net/api/get?"))
        assertTrue(url.contains("track_name=Creep"))
        assertTrue(url.contains("artist_name=Radiohead"))
        assertTrue(url.contains("album_name=Pablo%20Honey"))
        assertTrue(url.contains("duration=239"))
        // The service asks clients to identify themselves.
        assertNotNull(request.headers["User-Agent"])
        assertNotNull(request.headers["Lrclib-Client"])
    }

    @Test
    fun plainLyricsAreKeptWhenNotSynced() = runTest {
        val transport = StubGETTransport(
            listOf(Result.success(recordJSON(synced = null, plain = "Line one\nLine two"))),
        )
        val client = LRCLibClient(transport = transport)

        val lyrics = client.lyrics(track())

        assertTrue(!lyrics.isSynced)
        assertEquals("Line one\nLine two", lyrics.plainText)
    }

    @Test
    fun instrumentalTrackIsFlagged() = runTest {
        val transport = StubGETTransport(
            listOf(Result.success(recordJSON(synced = null, plain = null, instrumental = true))),
        )
        val client = LRCLibClient(transport = transport)

        val lyrics = client.lyrics(track())

        assertTrue(lyrics.instrumental)
    }

    @Test
    fun notFoundIsTyped() = runTest {
        val transport = StubGETTransport(
            listOf(Result.failure(PandoraTransportError("HTTP 404"))),
        )
        val client = LRCLibClient(transport = transport)

        val e = org.junit.Assert.assertThrows(LyricsError::class.java) {
            kotlinx.coroutines.runBlocking { client.lyrics(track()) }
        }
        assertEquals(LyricsError.NotFound, e)
    }

    @Test
    fun busyServiceIsRetriedThenReported() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.failure(PandoraTransportError("HTTP 503")),
                Result.failure(PandoraTransportError("HTTP 503")),
                Result.failure(PandoraTransportError("HTTP 503")),
            ),
        )
        val client = LRCLibClient(transport = transport, maxRetries = 2)

        val e = org.junit.Assert.assertThrows(LyricsError::class.java) {
            kotlinx.coroutines.runBlocking { client.lyrics(track()) }
        }
        assertEquals(LyricsError.ServiceBusy, e)
        // The initial attempt plus two retries.
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun busyServiceRecoversOnRetry() = runTest {
        val transport = StubGETTransport(
            listOf(
                Result.failure(PandoraTransportError("HTTP 503")),
                Result.success(recordJSON()),
            ),
        )
        val client = LRCLibClient(transport = transport, maxRetries = 2)

        val lyrics = client.lyrics(track())

        assertTrue(lyrics.isSynced)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun malformedResponseIsTyped() = runTest {
        val transport = StubGETTransport(listOf(Result.success("not json".toByteArray())))
        val client = LRCLibClient(transport = transport)

        val e = org.junit.Assert.assertThrows(LyricsError::class.java) {
            kotlinx.coroutines.runBlocking { client.lyrics(track()) }
        }
        assertEquals(LyricsError.MalformedResponse, e)
    }

    // MARK: Translation

    @Test
    fun translatesForeignLyrics() = runTest {
        val response = """
            [[["I am a cat","Je suis un chat",null,null,3]],null,"fr",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val translated = client.englishTranslation(listOf("Je suis un chat"))

        assertEquals(LyricsTranslation.Translated(listOf("I am a cat")), translated)
        val request = transport.requests.first()
        assertTrue(request.url.contains("tl=en"))
        assertTrue(request.url.contains("sl=auto"))
    }

    @Test
    fun englishLyricsNeedNoTranslation() = runTest {
        val response = """
            [[["Hallelujah","Hallelujah",null,null,5]],null,"en",null,null,null,1]
        """.trimIndent().toByteArray()
        val transport = StubGETTransport(listOf(Result.success(response)))
        val client = GoogleTranslateClient(transport = transport)

        val translated = client.englishTranslation(listOf("Hallelujah"))

        assertEquals(LyricsTranslation.AlreadyEnglish, translated)
    }

    @Test
    fun translationFailureIsTyped() = runTest {
        val transport = StubGETTransport(
            listOf(Result.failure(PandoraTransportError("network failure"))),
        )
        val client = GoogleTranslateClient(transport = transport)

        val translated = client.englishTranslation(listOf("Bonjour"))

        assertEquals(LyricsTranslation.Failed("network failure"), translated)
    }

    // MARK: Query decode helper

    private fun queryValue(url: String, name: String): String? =
        URI(url).rawQuery.split("&")
            .map { pair -> pair.split("=", limit = 2).let { it[0] to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") } }
            .toMap()[name]
}
