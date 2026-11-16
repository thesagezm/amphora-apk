//
//  HistoryStore.kt
//  app
//
//  Port of Amphora/Persistence/HistoryStore.swift — Codable-to-disk
//  persistence for the playback history. The file lives in app-private
//  storage — no secret ever goes here (the history is titles, artists
//  and timestamps).
//
//  Port delta: iOS nests the file under Application Support/Amphora;
//  Android's `filesDir` is already app-scoped, so the file sits at the
//  top level — the same decision `PreferencesStore` made. The JSON
//  keeps the Swift field names so a hand-carried file decodes either
//  way (`PlayHistory` is the pandoracore port, tested in the core
//  suite).
//

package dev.personal.amphora.persistence

import dev.personal.amphora.AmphoraContextProvider
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pandoracore.PlayHistory

/** Reads and writes `PlayHistory` as JSON in app-private storage. */
class HistoryStore(directory: File? = null) {

    companion object {
        const val fileName = "history.json"

        private val json = Json {
            // A file written by a newer/older build must still decode;
            // unknown keys are ignored, missing keys fall back to
            // defaults — the Swift decoder's behavior for both.
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    private val directory: File = directory
        ?: AmphoraContextProvider.context.filesDir

    private val file: File
        get() = File(directory, fileName)

    /**
     * Loads the history; a missing or unreadable file yields an empty
     * history (fail closed, never crash).
     */
    fun load(): PlayHistory {
        val contents = try {
            file.readText()
        } catch (_: Exception) {
            return PlayHistory()
        }
        return try {
            json.decodeFromString<PlayHistory>(contents)
        } catch (_: Exception) {
            PlayHistory()
        }
    }

    /**
     * Saves the history; failures are ignored (history is best-effort).
     * The write is atomic (temp file + rename) — a crash mid-write must
     * never leave a truncated file.
     */
    fun save(history: PlayHistory) {
        val data = try {
            json.encodeToString(history)
        } catch (_: Exception) {
            return
        }
        try {
            directory.mkdirs()
            val temp = File(directory, "$fileName.tmp")
            temp.writeText(data)
            if (!temp.renameTo(file)) {
                // Rename can fail across filesystem quirks; fall back to
                // a direct write rather than losing the save.
                file.writeText(data)
                temp.delete()
            }
        } catch (_: Exception) {
            // Best-effort — the next save retries.
        }
    }
}
