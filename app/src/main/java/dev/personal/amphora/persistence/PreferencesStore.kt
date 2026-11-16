//
//  PreferencesStore.kt
//  app
//
//  Port of Amphora/Persistence/PreferencesStore.swift — small
//  kotlinx-serialization-to-file store for user preferences that are
//  not secrets: the station order, the last played station and the
//  sleep-timer preference. One JSON file in app-private storage.
//
//  (Credentials and session tokens live in AndroidKeyStore — never
//  here.)
//
//  Port delta: iOS nests the file under Application Support/Amphora;
//  Android's `filesDir` is already app-scoped, so the file sits at the
//  top level — the same decision `KeystoreSessionStore` made for its
//  seal file. The JSON keeps the Swift field names so a hand-carried
//  file decodes either way.
//

package dev.personal.amphora.persistence

import dev.personal.amphora.AmphoraContextProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pandoracore.RadioStation
import pandoracore.SleepTimer
import pandoracore.StationOrder

/** The app's non-secret preferences. */
@Serializable
data class Preferences(
    /** The user's station ordering. */
    val stationOrder: StationOrder = StationOrder(),
    /** The station that was playing when the app was last used. */
    val lastStationId: String? = null,
    /** The sleep timer preference (off by default). */
    val sleepTimer: SleepTimer = SleepTimer.off,
    /** Whether scrobbling to Last.fm is enabled (only meaningful while a
     *  Last.fm session key exists in the Keystore). */
    val scrobblingEnabled: Boolean = false,
    /** Starred radio stations (Shortwave's favorites), device-local. */
    val radioFavorites: List<RadioStation> = emptyList(),
    /** Recently played radio stations, most recent first. */
    val radioRecents: List<RadioStation> = emptyList(),
    /** User-added custom stations (a name + a raw stream URL), device-local. */
    val radioCustomStations: List<RadioStation> = emptyList(),
    /**
     * The app's playback volume (0...1). Optional on purpose: a
     * preferences file written before this key existed must still
     * decode — a missing key must not silently reset every other
     * preference. `null` means "full volume".
     */
    val playbackVolume: Float? = null,
)

/** Reads and writes [Preferences] as JSON in app-private storage. */
class PreferencesStore(directory: File? = null) {

    companion object {
        const val fileName = "preferences.json"

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
     * Loads the preferences; a missing or unreadable file yields the
     * defaults (fail closed, never crash).
     */
    fun load(): Preferences {
        val contents = try {
            file.readText()
        } catch (_: Exception) {
            return Preferences()
        }
        return try {
            json.decodeFromString<Preferences>(contents)
        } catch (_: Exception) {
            Preferences()
        }
    }

    /**
     * Saves the preferences; failures are ignored (best-effort). The
     * write is atomic (temp file + rename) — a crash mid-write must
     * never leave a truncated file.
     */
    fun save(preferences: Preferences) {
        val data = try {
            json.encodeToString(preferences)
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
