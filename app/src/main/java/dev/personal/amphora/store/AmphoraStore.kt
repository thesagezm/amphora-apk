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
import dev.personal.amphora.persistence.Preferences
import dev.personal.amphora.persistence.PreferencesStore
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

    // MARK: Preferences (non-secret; one JSON file in filesDir)

    private val preferencesStore = PreferencesStore()

    var preferences: Preferences by mutableStateOf(Preferences())
        private set

    /** Persists a preferences change — mutate-and-save in one step,
     *  the port of the Swift `preferences.x = y; preferencesStore.save(preferences)`. */
    private fun updatePreferences(transform: (Preferences) -> Preferences) {
        preferences = transform(preferences)
        preferencesStore.save(preferences)
    }

    init {
        // Non-secret preferences load eagerly; the Keystore session
        // restores via the restore intent (zero network).
        preferences = preferencesStore.load()
    }

    // MARK: Observable state (stations surface)

    var stations: List<pandoracore.Station> by mutableStateOf(emptyList())
        private set
    var isLoadingStations: Boolean by mutableStateOf(false)
        private set
    var isMutatingStations: Boolean by mutableStateOf(false)
        private set
    var currentStation: pandoracore.Station? by mutableStateOf(null)
        private set
    /** The station that hit Pandora's daily skip limit (A4 sets this;
     *  the Home rows render it). */
    var skipLimitStationId: String? by mutableStateOf(null)
        private set
    /** Whether the current Pandora station is actively playing. A4
     *  wires this to the player; until then nothing plays, so it stays
     *  false (the Home rows render their "playing" glyph off it). */
    var isPlayingCurrentStation: Boolean by mutableStateOf(false)
        private set
    /** Whether Pandora hides explicit tracks for this account
     *  (`user.getSettings`). Pandora's explicit filter is an **account**
     *  switch, not a per-track flag, so Home shows one account-level
     *  badge. */
    var isExplicitContentFilterEnabled: Boolean by mutableStateOf(false)
        private set

    /** The hero of the Home tab: the last-played station, if it still
     *  exists in the list. Hidden until something has played. */
    val featuredStation: pandoracore.Station?
        get() {
            val id = preferences.lastStationId ?: return null
            return stations.firstOrNull { it.id == id }
        }

    // MARK: Observable state (search & create)

    var searchResults: List<pandoracore.MusicSearchResult> by mutableStateOf(emptyList())
        private set
    var isSearching: Boolean by mutableStateOf(false)
        private set
    /** Pandora's featured stations by browse category — null until
     *  first fetched this session; the browse sheet opens instantly on
     *  repeat visits; a relaunch refetches. */
    var genreCategories: List<pandoracore.GenreCategory>? by mutableStateOf(null)
        private set
    var isLoadingGenres: Boolean by mutableStateOf(false)
        private set
    /** Bumped on every successful station creation — the sheets' signal
     *  to dismiss (a fire-and-forget intent can't return success). */
    var stationCreatedTick: Int by mutableStateOf(0)
        private set

    private var searchJob: kotlinx.coroutines.Job? = null

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
            is Intent.RefreshStations -> launchIntent { refreshStations() }
            is Intent.RenameStation ->
                launchIntent { renameStation(intent.station, intent.newName) }
            is Intent.DeleteStation -> launchIntent { deleteStation(intent.station) }
            is Intent.MoveStation -> moveStation(from = intent.from, to = intent.to)
            is Intent.SearchStations -> search(intent.query)
            is Intent.CreateStation ->
                launchIntent { createStation(musicToken = intent.result.musicToken) }
            is Intent.CreateGenreStation ->
                launchIntent { createStation(musicToken = intent.station.token) }
            is Intent.LoadGenreStations -> launchIntent { loadGenreStations() }
            is Intent.DismissError -> lastErrorMessage = null
        }
    }

    sealed class Intent {
        data object RestoreSession : Intent()
        data class Login(val username: String, val password: String) : Intent()
        data object RefreshStations : Intent()
        data class RenameStation(val station: pandoracore.Station, val newName: String) : Intent()
        data class DeleteStation(val station: pandoracore.Station) : Intent()
        data class MoveStation(val from: Int, val to: Int) : Intent()
        data class SearchStations(val query: String) : Intent()
        data class CreateStation(val result: pandoracore.MusicSearchResult) : Intent()
        data class CreateGenreStation(val station: pandoracore.GenreStation) : Intent()
        data object LoadGenreStations : Intent()
        data object DismissError : Intent()
    }

    /** One scope for intent coroutines — main dispatcher, since state
     *  updates land on main; the core's suspend calls dispatch their
     *  own network work off it. */
    private val intentScope = CoroutineScope(Dispatchers.Main)

    private fun launchIntent(block: suspend () -> Unit) {
        intentScope.launch { block() }
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
                refreshUserSettings()
                refreshStations()
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
                refreshUserSettings()
                refreshStations()
            } catch (error: Exception) {
                authPhase = AuthPhase.signedOut
                record(error)
            }
        }
    }

    // MARK: User settings

    /**
     * Reads the account-level settings (`user.getSettings`). Best-effort:
     * a failure here never blocks the app, it only means the
     * explicit-content badge stays off.
     */
    private suspend fun refreshUserSettings() {
        try {
            val settings = session.getUserSettings()
            isExplicitContentFilterEnabled = settings.isExplicitContentFilterEnabled
            AppLog.info(
                "explicit content filter: " +
                    (if (settings.isExplicitContentFilterEnabled) "on" else "off"),
                AppLog.Category.network,
            )
        } catch (error: Exception) {
            AppLog.info("user settings unavailable: $error", AppLog.Category.network)
        }
    }

    // MARK: Stations

    /**
     * Fetches `user.getStationList`. Fire-and-forget via the intent;
     * also awaited directly for pull-to-refresh. A guard keeps
     * concurrent calls from stacking.
     */
    suspend fun refreshStations() {
        if (isLoadingStations) return
        lastErrorMessage = null
        isLoadingStations = true
        try {
            val list = session.getStationList()
            stations = displayOrder(list.stations)
            AppLog.info("station list loaded: ${stations.size} stations")
        } catch (error: Exception) {
            // Keep any already-loaded list; surface the error when empty
            // (error/empty-state polish lands in M5).
            if (stations.isEmpty()) {
                record(error)
            }
            AppLog.error("station list failed: $error")
        } finally {
            isLoadingStations = false
        }
    }

    /** Rename via `SessionManager` (shared stations transform first on
     *  the core side), then reload so sort order reflects the new name. */
    private suspend fun renameStation(station: pandoracore.Station, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || isMutatingStations) {
            if (trimmed.isEmpty()) {
                lastErrorMessage = "Enter a station name."
            }
            return
        }
        lastErrorMessage = null
        isMutatingStations = true
        try {
            session.renameStation(station, trimmed)
            refreshStations()
        } catch (error: Exception) {
            record(error)
        } finally {
            isMutatingStations = false
        }
    }

    private suspend fun deleteStation(station: pandoracore.Station) {
        if (isMutatingStations) return
        lastErrorMessage = null
        isMutatingStations = true
        try {
            session.deleteStation(station)
            stations = stations.filterNot { it.id == station.id }
        } catch (error: Exception) {
            record(error)
        } finally {
            isMutatingStations = false
        }
    }

    /** `true` when the station hit Pandora's daily skip limit. */
    fun isSkipLimited(station: pandoracore.Station): Boolean =
        skipLimitStationId == station.id

    /**
     * The list as the user sees it: QuickMix first, then the stored
     * order, then the remaining stations by name (case-insensitive, id
     * as the tiebreak so the order never shuffles between reloads).
     *
     * Port delta: Swift sorts with `localizedCaseInsensitiveCompare`;
     * the JVM port lowercases for a stable, locale-independent order —
     * the id tiebreak preserves the never-shuffle guarantee either way.
     */
    private fun displayOrder(stations: List<pandoracore.Station>): List<pandoracore.Station> {
        val ordered = preferences.stationOrder.apply(stations)
        val custom = preferences.stationOrder.orderedIds.toSet()
        return ordered.sortedWith { lhs, rhs ->
            when {
                lhs.isQuickMix != rhs.isQuickMix -> if (lhs.isQuickMix) -1 else 1
                custom.contains(lhs.id) != custom.contains(rhs.id) ->
                    if (custom.contains(lhs.id)) -1 else 1
                // Both are user-ordered — keep the stored sequence.
                custom.contains(lhs.id) && custom.contains(rhs.id) -> {
                    val lhsIndex = preferences.stationOrder.orderedIds.indexOf(lhs.id)
                    val rhsIndex = preferences.stationOrder.orderedIds.indexOf(rhs.id)
                    lhsIndex.compareTo(rhsIndex)
                }
                else -> {
                    val byName = lhs.name.lowercase().compareTo(rhs.name.lowercase())
                    if (byName != 0) byName else lhs.id.compareTo(rhs.id)
                }
            }
        }
    }

    /** Moves a station to a new position in the list and persists the
     *  new order. */
    private fun moveStation(from: Int, to: Int) {
        if (from !in stations.indices) return
        val ids = stations.map { it.id }.toMutableList()
        val moved = ids.removeAt(from)
        val target = to.coerceIn(0, ids.size)
        ids.add(target, moved)
        updatePreferences { it.copy(stationOrder = it.stationOrder.setOrder(ids)) }
        stations = preferences.stationOrder.apply(stations)
    }

    // MARK: Search & create

    /**
     * Debounced callers land here (the view waits out the typing pause).
     * Queries under 2 chars clear results instead of hitting the API —
     * never hammer the endpoint. A newer query cancels the in-flight one.
     */
    private fun search(rawQuery: String) {
        searchJob?.cancel()
        val query = rawQuery.trim()
        if (query.length < 2) {
            searchResults = emptyList()
            isSearching = false
            return
        }
        isSearching = true
        searchJob = intentScope.launch {
            try {
                val hits = session.search(query)
                searchResults = hits
                isSearching = false
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Superseded by a newer query — that one owns the state now.
                throw error
            } catch (error: Exception) {
                searchResults = emptyList()
                isSearching = false
                record(error)
            }
        }
    }

    /**
     * Creates from a search hit or the featured catalog (`musicToken`
     * covers artist/song/genre; a `GenreStation`'s own token is passed
     * as the music token — xivpiano: "making one uses its token as the
     * music token"). Appends locally like pithos — no refetch round
     * trip; deduped by id. Bumps `stationCreatedTick` so the sheet
     * dismisses on success.
     */
    private suspend fun createStation(musicToken: String) {
        if (isMutatingStations) return
        lastErrorMessage = null
        isMutatingStations = true
        try {
            val station = session.createStation(musicToken = musicToken)
            if (stations.none { it.id == station.id }) {
                stations = stations + station
            }
            stationCreatedTick += 1
        } catch (error: Exception) {
            record(error)
        } finally {
            isMutatingStations = false
        }
    }

    /**
     * Fetches Pandora's featured stations once per session
     * (`station.getGenreStations`, no parameters). The browse sheet
     * opens instantly on repeat visits; a relaunch refetches.
     */
    private suspend fun loadGenreStations() {
        if (genreCategories != null || isLoadingGenres) return
        isLoadingGenres = true
        try {
            val categories = session.getGenreStations()
            genreCategories = categories
            AppLog.info("genre stations: ${categories.size} categories")
        } catch (error: Exception) {
            AppLog.error("genre stations failed: $error")
            record(error)
        } finally {
            isLoadingGenres = false
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
