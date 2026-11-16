//
//  AmphoraStore.kt
//  app
//
//  The app-level store — the port of the iOS `AppStore` (@Observable →
//  StateFlow), starting with its auth surface (M1/A2) and growing with
//  the milestones: stations in A3, playback in A4, feedback in A5, and
//  so on. Composes `pandoracore`'s SessionManager with the app-side
//  transports and the Keystore session store.
//
//  Intent discipline mirrors iOS: the UI calls intent methods, no view
//  talks networking directly.
//

package dev.personal.amphora.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.personal.amphora.AmphoraContextProvider
import dev.personal.amphora.pandora.CookieCsrfProvider
import dev.personal.amphora.pandora.KeystoreSessionStore
import dev.personal.amphora.pandora.OkHttpGetTransport
import dev.personal.amphora.pandora.OkHttpLastFmTransport
import dev.personal.amphora.pandora.OkHttpModesTransport
import dev.personal.amphora.pandora.OkHttpPandoraTransport
import dev.personal.amphora.pandora.OkHttpRadioBrowserTransport
import dev.personal.amphora.support.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pandoracore.LastFmClient
import pandoracore.LegacyPandoraClient
import pandoracore.PandoraAPIError
import pandoracore.PandoraMalformedResponseError
import pandoracore.PandoraTransportError
import pandoracore.PlaylistError
import pandoracore.RadioBrowserClient
import pandoracore.SessionManager
import pandoracore.SessionManagerError
import pandoracore.StationModesError

/**
 * Where the app is in the auth lifecycle. Drives `RootView`'s switch.
 */
enum class AuthPhase {
    /** Warm-launch restore attempt in flight. */
    restoring,
    signedOut,
    signingIn,
    signedIn,
}

class AmphoraStore {

    // MARK: Core wiring (one client graph per store, app lifetime)

    private val sessionStore = KeystoreSessionStore(context = AmphoraContextProvider.context)
    val session = SessionManager(
        client = LegacyPandoraClient(transport = OkHttpPandoraTransport()),
        modes = pandoracore.PandoraModesClient(
            transport = OkHttpModesTransport(),
            csrf = CookieCsrfProvider(),
        ),
        store = sessionStore,
    )
    val lastFm = LastFmClient(transport = OkHttpLastFmTransport())
    val radio = RadioBrowserClient(transport = OkHttpRadioBrowserTransport())

    // MARK: Observable state (auth surface)

    var authPhase: AuthPhase by mutableStateOf(AuthPhase.restoring)
        private set
    var lastErrorMessage: String? by mutableStateOf(null)
        private set
    var isSubscriber: Boolean by mutableStateOf(false)
        private set
    /** The session that is signed in — null while signed out. */
    var currentSession by mutableStateOf<pandoracore.SessionTokens?>(null)
        private set

    private var didStartRestore = false

    // MARK: Intents

    /**
     * Warm start: pull the Keystore session into memory with zero
     * network. Expiry surfaces later as a 1001 on the first
     * authenticated call, and `SessionManager` re-logins transparently
     * then.
     */
    fun send(intent: Intent) {
        when (intent) {
            is Intent.RestoreSession -> restoreSession()
            is Intent.Login -> login(intent.username, intent.password)
        }
    }

    sealed class Intent {
        data object RestoreSession : Intent()
        data class Login(val username: String, val password: String) : Intent()
    }

    private fun restoreSession() {
        if (didStartRestore) return
        didStartRestore = true
        AppLog.info("restoring session")
        CoroutineScope(Dispatchers.Main).launch {
            val restored = session.restore()
            AppLog.info("session restore: ${if (restored) "restored" else "no session"}")
            if (restored) {
                isSubscriber = session.isSubscriber
                currentSession = session.session
                authPhase = AuthPhase.signedIn
            } else {
                authPhase = AuthPhase.signedOut
            }
        }
    }

    private fun login(username: String, password: String) {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            lastErrorMessage = "Enter your email and password."
            return
        }
        if (authPhase != AuthPhase.signedOut) return

        lastErrorMessage = null
        authPhase = AuthPhase.signingIn
        CoroutineScope(Dispatchers.Main).launch {
            try {
                currentSession = session.login(username = user, password = password)
                isSubscriber = session.isSubscriber
                authPhase = AuthPhase.signedIn
            } catch (error: Exception) {
                authPhase = AuthPhase.signedOut
                record(error)
            }
        }
    }

    // MARK: Error copy (port of `AppStore.userMessage(for:)` / `record`)

    /** The user-facing sentence for a surfaced error. The core owns the
     *  copy (timeout vs unreachable vs server status) — the raw reason
     *  is log material, never UI copy. */
    private fun userMessage(error: Exception): String = when (error) {
        is PandoraAPIError -> error.localizedDescription
        is PlaylistError -> "Pandora's daily skip limit was reached. Try again later."
        is PandoraTransportError -> error.localizedDescription
        is PandoraMalformedResponseError -> "Pandora sent an unexpected response. Try again."
        is StationModesError.Unauthorized -> "Your Pandora session ended. Sign in again."
        is StationModesError.NotEntitled -> "This mode needs Pandora Premium."
        is StationModesError.ModeNotOffered -> "Pandora did not enable that mode."
        is StationModesError.HttpStatus -> "Couldn't reach Pandora (HTTP ${error.statusCode}). Try again."
        is SessionManagerError.NotAuthenticated -> "Not signed in. Try again."
        is SessionManagerError.MalformedSession -> "Pandora sent an unexpected response. Try again."
        else -> "Something went wrong. Try again."
    }

    /**
     * Records a surfaced error. When the error means the session is
     * unrecoverable — 1001 after `SessionManager`'s transparent
     * re-login retry (a freshly-issued token was already rejected), the
     * modes API rejecting after its own re-login, or no session at all —
     * the app returns to the sign-in screen with a clear message
     * (LoginView shows `lastErrorMessage`).
     */
    private fun record(error: Exception) {
        lastErrorMessage = userMessage(error)
        val sessionDead = when (error) {
            is PandoraAPIError -> error.isSessionExpired
            is SessionManagerError.NotAuthenticated -> true
            is StationModesError.Unauthorized -> true
            else -> false
        }
        if (sessionDead && authPhase == AuthPhase.signedIn) {
            AppLog.error("session unrecoverable — returning to sign-in", AppLog.Category.app)
            lastErrorMessage = "Your Pandora session ended. Sign in again."
            authPhase = AuthPhase.signedOut
        }
    }
}
