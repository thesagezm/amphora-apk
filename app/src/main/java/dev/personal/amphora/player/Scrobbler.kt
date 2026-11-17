//
//  Scrobbler.kt
//  app
//
//  Port of Amphora/Player/Scrobbler.swift — Last.fm scrobbling for the
//  app target, composition of the pandoracore `LastFmClient` with the
//  pithos playback semantics:
//
//  - `nowPlaying` when a track starts (pithos `_on_song_changed`).
//  - one `track.scrobble` when a track stops being the current one
//    (natural end, skip, station switch), if pithos's rule
//    (`ScrobblePolicy`) says it was earned (pithos `_on_song_ended`).
//
//  The session key is a secret — the Keystore-sealed file only, never
//  preferences. The enable preference is a non-secret and lives in
//  `Preferences` (the store owns the write; this class exposes the
//  flag and reports its flips through `onEnabledChanged`).
//
//  Scrobbling must never disturb playback (Priority 1): failures log
//  and, when Last.fm rejects the session (error 9 — or error 4, which
//  is how a dead key surfaces on the scrobble methods), the state
//  becomes `.needsAuth` — the user signs in again from Settings. The
//  credentials sentence ("rejected that username or password") is the
//  sign-in form's answer only; playback-time rejections are session
//  failures, not credential failures.
//
//  Port deltas (documented per AGENTS.md):
//  - iOS `@MainActor @Observable` → Compose `mutableStateOf` fields;
//    the fire-and-forget network tasks run on a main-dispatcher scope
//    (the client's transport hops to IO itself).
//  - iOS's `Task { }` per hook → `scope.launch`; ordering within one
//    hook type is preserved by the main dispatcher's FIFO queue.
//  - AppLog has no debug level yet (the A6.1 delta); the "rule not
//    met" line logs at info.
//

package dev.personal.amphora.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.personal.amphora.pandora.KeystoreSecretStore
import dev.personal.amphora.support.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import pandoracore.LastFmClient
import pandoracore.LastFmError
import pandoracore.LastFmSession
import pandoracore.ScrobblePolicy
import pandoracore.Track

class Scrobbler(keychain: KeystoreSecretStore) {

    sealed class AuthState {
        data object SignedOut : AuthState()

        data class SignedIn(val username: String) : AuthState()

        /** Last.fm rejected the stored session key (error 9) — the user
         *  must sign in again; the dead key is discarded. */
        data class NeedsAuth(val message: String) : AuthState()
    }

    private val client = LastFmClient(transport = dev.personal.amphora.pandora.OkHttpLastFmTransport())
    private val keychain: KeystoreSecretStore = keychain

    private val json = Json

    var authState: AuthState by mutableStateOf(AuthState.SignedOut)
        private set

    /** Master switch, persisted via the store's preferences. Only
     *  meaningful while signed in. */
    var isEnabled: Boolean by mutableStateOf(false)

    /**
     * Fires when the scrobbler itself flips the enable flag (sign-in
     * enables it, a rejected session key or sign-out disables it).
     * The store persists the preference here — without it, a fresh
     * sign-in enabled scrobbling only in memory and the preference
     * read back on the next launch was still `false`.
     */
    var onEnabledChanged: ((Boolean) -> Unit)? = null

    /** True while an `auth.getMobileSession` round trip is in flight. */
    var isAuthenticating: Boolean by mutableStateOf(false)
        private set

    /** The last authentication failure, for the Settings sheet. */
    var authError: String? by mutableStateOf(null)
        private set

    /**
     * What the scrobbler last did, for the Settings status line —
     * "Now playing: …" / "Scrobbled …" / the last error. Without this
     * the user cannot tell a working connection from a silently
     * failing one (scrobble failures deliberately never interrupt
     * playback, so the Settings row is the only place they surface).
     */
    var lastActivity: String? by mutableStateOf(null)
        private set

    /** True when `lastActivity` is an error rather than a success. */
    var lastActivityIsError: Boolean by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        loadSession()?.let { session ->
            authState = AuthState.SignedIn(session.username)
        }
    }

    // MARK: - Authentication

    /**
     * Exchanges Last.fm credentials for a session key (`auth.
     * getMobileSession`) and stores it in the Keystore. The password is
     * never persisted. Enabling follows automatically — pithos
     * scrobbles as soon as the account is authorized.
     */
    fun authenticate(username: String, password: String) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            authError = "Enter your Last.fm username and password."
            return
        }
        isAuthenticating = true
        authError = null
        scope.launch {
            try {
                val session = client.authenticate(username = user, password = password)
                keychain.set(json.encodeToString(LastFmSession.serializer(), session).toByteArray(Charsets.UTF_8))
                authState = AuthState.SignedIn(session.username)
                isEnabled = true
                onEnabledChanged?.invoke(true)
                lastActivity = "Signed in. Play a track to scrobble."
                lastActivityIsError = false
                AppLog.info("last.fm: signed in as ${session.username}")
            } catch (error: Exception) {
                AppLog.error("last.fm: sign-in failed ($error)")
                authError = userMessage(error)
            }
            isAuthenticating = false
        }
    }

    /** Discards the session key and turns scrobbling off. */
    fun signOut() {
        keychain.remove()
        authState = AuthState.SignedOut
        isEnabled = false
        onEnabledChanged?.invoke(false)
        authError = null
        lastActivity = null
        lastActivityIsError = false
        AppLog.info("last.fm: signed out")
    }

    /**
     * Clears a stale sign-in failure. The message must answer the
     * attempt that caused it, not sit under the form forever — the
     * fields' edits call this, so the error disappears the moment the
     * user starts typing a corrected entry.
     */
    fun clearAuthError() {
        authError = null
    }

    // MARK: - Playback hooks

    /** `track.updateNowPlaying` for a track that just started. Never
     *  blocks or disturbs playback. */
    fun nowPlaying(track: Track) {
        if (!isReady || track.isAdvertisement) return
        val key = sessionKey()
        if (key.isEmpty()) return
        AppLog.info("last.fm: now playing \"${track.title}\" — ${track.artist}")
        scope.launch {
            try {
                client.updateNowPlaying(
                    sessionKey = key,
                    artist = track.artist,
                    track = track.title,
                    album = track.album,
                    duration = track.duration.toDouble(),
                )
                lastActivity = "Now playing on Last.fm: ${track.title}"
                lastActivityIsError = false
            } catch (error: Exception) {
                handle(error, while_ = track.title)
            }
        }
    }

    /**
     * Scrobbles the track that just stopped being current (natural
     * end, skip, station switch) if pithos's rule says it was earned.
     * `startedAt` is when the track began playing — Last.fm wants the
     * start time, not the scrobble time.
     */
    fun scrobbleIfEarned(track: Track, reachedPosition: Double, startedAt: Long?) {
        if (!isReady) return
        val duration = track.duration.toDouble()
        if (!ScrobblePolicy.shouldScrobble(
                duration = duration,
                position = reachedPosition,
                isAdvertisement = track.isAdvertisement,
            )
        ) {
            AppLog.info(
                "last.fm: not scrobbling \"${track.title}\" (rule not met, reached ${reachedPosition.toInt()}s)",
                AppLog.Category.playback,
            )
            return
        }
        val key = sessionKey()
        if (key.isEmpty()) return
        val timestamp = (startedAt ?: System.currentTimeMillis()) / 1000L
        AppLog.info("last.fm: scrobbling \"${track.title}\" — ${track.artist}")
        scope.launch {
            try {
                client.scrobble(
                    sessionKey = key,
                    artist = track.artist,
                    track = track.title,
                    album = track.album,
                    timestamp = timestamp,
                    duration = duration,
                )
                lastActivity = "Scrobbled: ${track.title}"
                lastActivityIsError = false
            } catch (error: Exception) {
                handle(error, while_ = track.title)
            }
        }
    }

    // MARK: - Internals

    private val isReady: Boolean
        get() = isEnabled && authState !is AuthState.SignedOut

    /** Reads the session key; `.needsAuth` state has no key to send.
     *  Empty string means "skip the network call". */
    private fun sessionKey(): String {
        if (authState is AuthState.NeedsAuth) return ""
        val session = loadSession()
        if (session == null) {
            authState = AuthState.NeedsAuth("Last.fm session lost. Sign in again.")
            return ""
        }
        return session.key
    }

    /**
     * Scrobble failures only log — except a rejected *session* (the
     * key, error 9 — or error 4, which is how Last.fm reports a dead
     * key on the scrobble/now-playing methods), which disables
     * scrobbling until re-auth.
     */
    private fun handle(error: Exception, while_: String) {
        if (error is LastFmError.InvalidSessionKey) {
            sessionEnded()
            return
        }
        // "Invalid username or password" during *playback* is not the
        // credentials sentence's business — the password is never
        // stored. Last.fm reports a dead session key as error 4 on
        // these methods, so the session failure gets the same
        // re-auth handling as error 9 (user report 2026-11-12: the
        // credentials sentence appeared in the status line and the
        // logs for what is a session ending).
        if (error is LastFmError.InvalidCredentials) {
            sessionEnded()
            return
        }
        lastActivity = "Last.fm error on \"$while_\": ${userMessage(error)}"
        lastActivityIsError = true
        AppLog.error(
            "last.fm: update failed for \"$while_\": $error",
            AppLog.Category.playback,
        )
    }

    /** The stored session is no longer valid: drop it, disable
     *  scrobbling and put the sign-in form back. */
    private fun sessionEnded() {
        AppLog.error("last.fm: session rejected — scrobbling paused")
        authState = AuthState.NeedsAuth("Last.fm session ended. Sign in again.")
        isEnabled = false
        onEnabledChanged?.invoke(false)
        keychain.remove()
        lastActivity = "Last.fm session ended. Sign in again."
        lastActivityIsError = true
    }

    /** The user-facing sentence for a Last.fm failure (the port of
     *  `Scrobbler.userMessage(for:)`). */
    private fun userMessage(error: Exception): String = when (error) {
        LastFmError.InvalidCredentials ->
            "Last.fm rejected that username or password."
        LastFmError.RateLimited ->
            "Last.fm is rate limiting. Wait a moment and try again."
        LastFmError.InvalidApiKey,
        LastFmError.InvalidSignature,
        LastFmError.Malformed,
        -> "Last.fm sent an unexpected response. Try again."
        is LastFmError.Rejected ->
            if (error.rejectedMessage.isNotEmpty()) {
                "Last.fm: ${error.rejectedMessage}"
            } else {
                "Sign-in failed. Try again."
            }
        is LastFmError.HttpStatus ->
            "Couldn't reach Last.fm. Try again."
        is LastFmError.Transport ->
            "Couldn't reach Last.fm. Check your connection."
        else -> "Sign-in failed. Try again."
    }

    private fun loadSession(): LastFmSession? = try {
        val data = keychain.data() ?: return null
        json.decodeFromString(LastFmSession.serializer(), data.decodeToString())
    } catch (_: Exception) {
        null
    }
}
