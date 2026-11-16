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
import dev.personal.amphora.persistence.HistoryStore
import dev.personal.amphora.persistence.Preferences
import dev.personal.amphora.persistence.PreferencesStore
import dev.personal.amphora.pandora.CookieCsrfProvider
import dev.personal.amphora.pandora.KeystoreSessionStore
import dev.personal.amphora.pandora.OkHttpGetTransport
import dev.personal.amphora.pandora.OkHttpLastFmTransport
import dev.personal.amphora.pandora.OkHttpModesTransport
import dev.personal.amphora.pandora.OkHttpPandoraTransport
import dev.personal.amphora.pandora.OkHttpRadioBrowserTransport
import dev.personal.amphora.player.PlaybackClient
import dev.personal.amphora.player.PlaybackState
import dev.personal.amphora.player.RadioMetadataProbe
import dev.personal.amphora.support.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import pandoracore.Bookmark
import pandoracore.FeedbackRating
import pandoracore.GoogleTranslateClient
import pandoracore.ITunesSongArtworkClient
import pandoracore.LastFmClient
import pandoracore.LegacyPandoraClient
import pandoracore.Lyrics
import pandoracore.LyricsError
import pandoracore.LyricsProviderChain
import pandoracore.LyricsTranslation
import pandoracore.PandoraAPIError
import pandoracore.PandoraMalformedResponseError
import pandoracore.PandoraTransportError
import pandoracore.PlaylistError
import pandoracore.PlayHistory
import pandoracore.RadioBrowserClient
import pandoracore.RadioNowPlaying
import pandoracore.SongArtwork
import pandoracore.isRadio
import pandoracore.radioTrack
import pandoracore.SessionManager
import pandoracore.SessionManagerError
import pandoracore.SleepTimer
import pandoracore.StationModesError
import pandoracore.Track

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

/**
 * The state of the English translation for the current lyrics. The
 * lyrics panel turns this into the **Translate** button, the
 * "Translating…" state and the result (the port of AppStore's nested
 * `LyricsTranslationState` enum — the failed message needs its
 * associated value, so a sealed class).
 */
sealed class LyricsTranslationState {
    /** Nothing has been asked for yet. */
    data object None : LyricsTranslationState()

    /** A request is in flight. */
    data object Translating : LyricsTranslationState()

    /** The English translation is on the lyrics. */
    data object Translated : LyricsTranslationState()

    /** The lyrics are already in English — there is nothing to
     *  translate. */
    data object AlreadyEnglish : LyricsTranslationState()

    /** The request failed. The user can try again. */
    data class Failed(val message: String) : LyricsTranslationState()
}

/**
 * How far the radio now-playing identification has come for the screen
 * that is playing (the port of AppStore's nested `RadioIdentification`).
 */
enum class RadioIdentification {
    /** Not a radio stream (or playback stopped). */
    inactive,

    /** The probe is connected and listening; no title yet. */
    identifying,

    /** A song is announced (`radioNowPlaying`). */
    identified,

    /** The station sends no ICY metadata — it announces nothing and
     *  can only be identified by hand if a lookup ever supports it. */
    stationAnnouncesNothing,
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
    /** The internet-radio directory client (radio-browser.info). The
     *  Radio tab's discovery state goes through `radio` — one shared
     *  client, so the mirror rotation and the play-click ping see the
     *  same servers. */
    private val radioClient = RadioBrowserClient(transport = OkHttpRadioBrowserTransport())

    /** The Radio tab's discovery state (search / popular / countries /
     *  tags — the port of the iOS `radio: RadioController`). */
    val radio = RadioController(client = radioClient)

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
    /** Whether the current Pandora station is actively playing (the
     *  port of the views' `isCurrent && store.player.isPlaying`). The
     *  Home rows render their "playing" glyph off it. */
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

    // MARK: Playback engine (A4 — the port of PlayerService ownership)

    /**
     * Playback engine. Owns the player, the audio session and the
     * now-playing track; it never talks to the network. The store drives
     * it and reads its state (the iOS store owns PlayerService directly;
     * here the engine lives in a MediaSessionService and this client
     * wrapper fronts it).
     */
    val player = PlaybackClient(context = AmphoraContextProvider.context)

    /** True while a playlist request for a station is in flight. */
    var isLoadingPlaylist: Boolean by mutableStateOf(false)
        private set

    /** Tracks already handed to the player — the next playlist must not
     *  queue the same track twice. */
    private var queuedTrackTokens: MutableSet<String> = mutableSetOf()

    /** One backed-off prefetch retry after a failed prefetch (the port
     *  of schedulePrefetchRetry's Task). */
    private var prefetchRetryJob: Job? = null
    private var prefetchRetryAttempts = 0

    /** The in-flight advertisement resolve/register (best-effort). */
    private var adJob: Job? = null

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

    // MARK: Observable state (station modes / tuning)

    /** The listening modes of `modesStationId`'s station. */
    var stationModes: pandoracore.StationModes? by mutableStateOf(null)
        private set
    var isLoadingModes: Boolean by mutableStateOf(false)
        private set
    var isChangingMode: Boolean by mutableStateOf(false)
        private set
    /** Bumped on every successful mode change — the tune sheet's
     *  dismiss signal. */
    var stationModeChangedTick: Int by mutableStateOf(0)
        private set
    /** Which station `stationModes` describes (the sheet may tune a
     *  concrete station while another one plays). */
    private var modesStationId: String? = null

    // MARK: Observable state (playback confirmations)

    /** A short confirmation shown in the player ("Tuned to …"), cleared
     *  after a few seconds; a newer message replaces the pending one. */
    var confirmationMessage: String? by mutableStateOf(null)
        private set
    private var confirmationJob: Job? = null

    private var searchJob: kotlinx.coroutines.Job? = null

    // MARK: Observable state (feedback)

    /** The rating on the current track. The FeedbackBar renders it and
     *  toggles it off by sending `RemoveCurrentRating` (pithos'
     *  `Song.rate` love/ban toggle). */
    var currentRating: FeedbackRating by mutableStateOf(FeedbackRating.none)
        private set

    /**
     * The `feedbackId` of the rating on the current track (needed to
     * undo it). Belongs to the *previous* track after a track change —
     * the onTrackChanged observer resets it (pithos keeps it per song).
     */
    private var currentFeedbackId: String? = null

    // MARK: Observable state (sleep timer)

    /** The sleep timer (off by default). */
    var sleepTimer: SleepTimer by mutableStateOf(SleepTimer.off)
        private set

    /** Live status text ("12 min left", "End of track", "Off").
     *  Refreshed when the remaining minutes change, so the UI
     *  re-renders about once a minute instead of every second. */
    var sleepTimerLabel: String by mutableStateOf(SleepTimer.off.label())
        private set

    /** The countdown coroutine for a duration timer. Cancelled and
     *  replaced whenever the timer changes. */
    private var sleepTimerJob: Job? = null

    // MARK: Observable state (bookmarks)

    /** The user's bookmarks (`user.getBookmarks`), songs first. */
    var bookmarks: List<Bookmark> by mutableStateOf(emptyList())
        private set
    var isLoadingBookmarks: Boolean by mutableStateOf(false)
        private set

    // MARK: Observable state (history)

    /** Local playback history ("recently played"), newest first.
     *  `PlayHistory` mutates in place (the Swift struct mutated too),
     *  so every mutation swaps in a copy to make Compose see it — see
     *  the record/rating call sites. */
    var history: PlayHistory by mutableStateOf(PlayHistory())
        private set

    /** Track tokens with a rating request in flight (from the Recents
     *  list) — the UI disables those rows' buttons while it runs. */
    var ratingTrackTokens: Set<String> by mutableStateOf(emptySet())
        private set

    /** Disk persistence for the history (JSON file in filesDir). */
    private val historyStore = HistoryStore()

    // MARK: Observable state (radio — the Shortwave port)

    /**
     * The radio station whose live stream is playing (null unless radio
     * is the current source — starting radio stops Pandora playback and
     * vice versa, the player is singular).
     */
    var currentRadioStation: pandoracore.RadioStation? by mutableStateOf(null)
        private set

    /** Starred radio stations, device-local (Shortwave's favorites). */
    var radioFavorites: List<pandoracore.RadioStation> by mutableStateOf(emptyList())
        private set

    /** Recently played radio stations, most recent first. */
    var radioRecents: List<pandoracore.RadioStation> by mutableStateOf(emptyList())
        private set

    /** User-added custom stations (a name + a raw stream URL). */
    var radioCustomStations: List<pandoracore.RadioStation> by mutableStateOf(emptyList())
        private set

    // MARK: Radio now-playing identification (ICY metadata)

    /** Reads the playing radio station's ICY now-playing metadata over
     *  its own parallel connection (read-only — the audio path is the
     *  player's). */
    private val radioMetadataProbe = RadioMetadataProbe()

    /** Song-artwork lookup for a radio station's identified song. */
    private val songArtworkClient = ITunesSongArtworkClient(transport = OkHttpGetTransport())

    /** The song the playing radio station's ICY metadata is announcing
     *  ("Artist - Title" split by the core parser). */
    var radioNowPlaying: pandoracore.RadioNowPlaying? by mutableStateOf(null)
        private set

    /** Where the radio song identification stands for the UI. */
    var radioIdentification: RadioIdentification by
        mutableStateOf(RadioIdentification.inactive)
        private set

    /** The identified radio song's cover (iTunes Search). */
    var radioSongArtwork: pandoracore.SongArtwork? by mutableStateOf(null)
        private set

    /** True while the identified song's artwork lookup is in flight. */
    var isIdentifyingRadioSong: Boolean by mutableStateOf(false)
        private set

    /**
     * The synthetic track whose lyrics are on screen while a radio
     * station's identified song owns the lyrics panel (the player's
     * current track is still the stream).
     */
    private var radioLyricsToken: String? = null
    private var radioLyricsTrack: Track? = null

    private var radioLookupJob: Job? = null
    private var radioAnnouncementJob: Job? = null

    // MARK: Observable state (lyrics)

    /** The lyrics of the current track (the core provider chain's
     *  answer: synced lines, plain text, instrumental — or nothing). */
    var lyrics: Lyrics? by mutableStateOf(null)
        private set
    var isLoadingLyrics: Boolean by mutableStateOf(false)
        private set

    /** The translation surface of the current lyrics: the **Translate**
     *  button, the "Translating…" state and the result (translated /
     *  already English / failed with a retry). */
    var lyricsTranslationState: LyricsTranslationState by
        mutableStateOf(LyricsTranslationState.None)
        private set

    /** Per-`trackToken` lyrics cache — "not found" is cached too, so
     *  repeat listens never re-hit the service. Main-dispatcher only. */
    private val lyricsCache = mutableMapOf<String, Lyrics>()
    private var lyricsJob: Job? = null
    private var translationJob: Job? = null
    private var romanizationJob: Job? = null

    /** The lyrics clients (the core chain: exact match → fuzzy search →
     *  a second database) and the Google endpoint that both translates
     *  and romanizes. One client instance serves both roles (iOS
     *  injects two — both are stateless; the seams are kept). */
    private val lyricsProvider = LyricsProviderChain.defaultChain(
        transport = OkHttpGetTransport(),
    )
    private val lyricsTranslator = GoogleTranslateClient(transport = OkHttpGetTransport())
    private val lyricsRomanizer = lyricsTranslator

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
            is Intent.LoadStationModes ->
                launchIntent { loadStationModes(intent.station) }
            is Intent.SetStationMode ->
                launchIntent { setStationMode(intent.mode, intent.station) }
            is Intent.PlayStation -> launchIntent { play(intent.station) }
            is Intent.TogglePlayback -> player.togglePlayback()
            is Intent.SkipToNext -> skipToNext()
            is Intent.SetPlaybackVolume -> {
                // The slider moves the level live; this intent is the one
                // that remembers it (the view sends it when the drag ends,
                // so the disk is written once per gesture, not per frame).
                player.setVolume(intent.volume)
                updatePreferences { it.copy(playbackVolume = intent.volume) }
            }
            is Intent.RateCurrentTrack ->
                launchIntent { rateCurrentTrack(intent.rating) }
            is Intent.RemoveCurrentRating -> launchIntent { removeCurrentRating() }
            is Intent.SetSleepTimer -> applySleepTimer(intent.timer)
            is Intent.CancelSleepTimer -> applySleepTimer(SleepTimer.off)
            is Intent.SleepCurrentTrack -> launchIntent { sleepCurrentTrack() }
            is Intent.BookmarkCurrentSong -> launchIntent { bookmarkCurrentSong() }
            is Intent.BookmarkCurrentArtist -> launchIntent { bookmarkCurrentArtist() }
            is Intent.RefreshBookmarks -> launchIntent { refreshBookmarks() }
            is Intent.DeleteBookmark -> launchIntent { deleteBookmark(intent.bookmark) }
            is Intent.RateHistoryTrack ->
                launchIntent {
                    rateHistoryTrack(intent.rating, intent.entry, intent.station)
                }
            is Intent.RemoveHistoryTrackRating ->
                launchIntent {
                    rateHistoryTrack(FeedbackRating.none, intent.entry, intent.station)
                }
            is Intent.RefreshLyrics ->
                loadLyrics(currentLyricsTrack(), force = true)
            is Intent.TranslateLyrics ->
                requestTranslation(currentLyricsTrack(), manual = true)
            is Intent.IdentifyRadioSong -> identifyRadioSongIntent()
            is Intent.PlayRadioStation -> playRadio(intent.station)
            is Intent.StopRadio -> stopRadio()
            is Intent.ToggleRadioFavorite ->
                toggleRadioFavorite(intent.station)
            is Intent.RemoveRadioRecent -> {
                radioRecents = radioRecents.filterNot { it.uuid == intent.station.uuid }
                updatePreferences { it.copy(radioRecents = radioRecents) }
            }
            is Intent.ClearRadioRecents -> {
                radioRecents = emptyList()
                updatePreferences { it.copy(radioRecents = radioRecents) }
            }
            is Intent.AddRadioCustomStation ->
                addRadioCustomStation(intent.name, intent.url)
            is Intent.UpdateRadioCustomStation ->
                updateRadioCustomStation(intent.station, intent.name, intent.url)
            is Intent.DeleteRadioCustomStation ->
                deleteRadioCustomStation(intent.station)
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
        data class LoadStationModes(val station: pandoracore.Station?) : Intent()
        data class SetStationMode(
            val mode: pandoracore.StationMode,
            val station: pandoracore.Station?,
        ) : Intent()
        data class PlayStation(val station: pandoracore.Station) : Intent()
        data object TogglePlayback : Intent()
        data object SkipToNext : Intent()
        data class SetPlaybackVolume(val volume: Float) : Intent()
        data class RateCurrentTrack(val rating: FeedbackRating) : Intent()
        data object RemoveCurrentRating : Intent()
        data class SetSleepTimer(val timer: SleepTimer) : Intent()
        data object CancelSleepTimer : Intent()
        data object SleepCurrentTrack : Intent()
        data object BookmarkCurrentSong : Intent()
        data object BookmarkCurrentArtist : Intent()
        data object RefreshBookmarks : Intent()
        data class DeleteBookmark(val bookmark: Bookmark) : Intent()
        data class RateHistoryTrack(
            val rating: FeedbackRating,
            val entry: PlayHistory.Entry,
            val station: pandoracore.Station,
        ) : Intent()
        data class RemoveHistoryTrackRating(
            val entry: PlayHistory.Entry,
            val station: pandoracore.Station,
        ) : Intent()
        data object RefreshLyrics : Intent()
        data object TranslateLyrics : Intent()
        /** The Identify button: retries the announced song's artwork +
         *  lyrics lookup (an automatic attempt may have found nothing). */
        data object IdentifyRadioSong : Intent()
        data class PlayRadioStation(val station: pandoracore.RadioStation) : Intent()
        data object StopRadio : Intent()
        data class ToggleRadioFavorite(val station: pandoracore.RadioStation) : Intent()
        data class RemoveRadioRecent(val station: pandoracore.RadioStation) : Intent()
        data object ClearRadioRecents : Intent()
        /** A custom station from a raw stream URL (validated in the form). */
        data class AddRadioCustomStation(val name: String, val url: String) : Intent()
        data class UpdateRadioCustomStation(
            val station: pandoracore.RadioStation,
            val name: String,
            val url: String,
        ) : Intent()
        data class DeleteRadioCustomStation(val station: pandoracore.RadioStation) : Intent()
        data object DismissError : Intent()
    }

    /** One scope for intent coroutines — main dispatcher, since state
     *  updates land on main; the core's suspend calls dispatch their
     *  own network work off it. */
    private val intentScope = CoroutineScope(Dispatchers.Main)

    private fun launchIntent(block: suspend () -> Unit) {
        intentScope.launch { block() }
    }

    init {
        // Non-secret preferences load eagerly; the Keystore session
        // restores via the restore intent (zero network).
        preferences = preferencesStore.load()
        // The radio favorites / recents / custom stations ride in the
        // same preferences file (iOS reads them out at init too).
        radioFavorites = preferences.radioFavorites
        radioRecents = preferences.radioRecents
        radioCustomStations = preferences.radioCustomStations
        // The play history is device data — it survives sign-out and
        // relaunch (iOS keeps it on sign-out too).
        history = historyStore.load()
        // The playback volume survives a relaunch. `null` (a file written
        // before the key existed) means full volume.
        player.setVolume(preferences.playbackVolume ?: 1f)
        // A sleep timer survives a relaunch: restore it and resume the
        // countdown if its deadline is still ahead.
        val storedTimer = preferences.sleepTimer
        if (storedTimer.mode == SleepTimer.Mode.duration && !storedTimer.hasExpired()) {
            sleepTimer = storedTimer
            sleepTimerLabel = storedTimer.label()
            startSleepTimerCountdown()
        } else if (storedTimer.mode == SleepTimer.Mode.endOfTrack) {
            sleepTimer = storedTimer
            sleepTimerLabel = storedTimer.label()
        }
        // The player asks for more tracks when its queue runs low — pithos
        // preloads the next playlist when one song remains.
        player.onNeedMoreTracks = { fetchMoreTracks() }
        // A new track means a new per-track state surface. The history
        // record, sleep-timer check, lyrics load and Last.fm now-playing
        // observers arrive with A5/A6/A8 — this unit wires the rating
        // reset and the parts playback itself needs (ads).
        player.onTrackChanged.add { track ->
            if (track != null) {
                AppLog.info(
                    "track started: ${track.title} — ${track.artist}",
                    AppLog.Category.playback,
                )
            } else {
                AppLog.info("track queue empty", AppLog.Category.playback)
            }
            // A new track means a new rating state: the feedback id
            // belongs to the previous track (pithos keeps it per song).
            // The rating comes from the playlist entry (`songRating`).
            currentFeedbackId = null
            currentRating = feedbackRatingFor(track?.rating)
            // Record the play in the local history (ads are not songs,
            // and neither is a live radio stream). The station token
            // rides along so the Recents list can rate the track later
            // — the rating belongs to that station.
            if (track != null && !track.isAdvertisement && !track.isRadio) {
                history = history.copy().also {
                    it.record(track, currentStation?.token)
                }
                historyStore.save(history)
            }
            prepareAdIfNeeded(track)
            // An "end of track" sleep timer stops playback here — the
            // moment the next track starts (iOS fires it in the same
            // spot of its onTrackChanged observer).
            if (sleepTimer.mode == SleepTimer.Mode.endOfTrack && track != null) {
                fireSleepTimer()
            }
            // Lyrics follow the current track (never for ads — the radio
            // guard arrives with A7); the per-track cache makes repeat
            // listens free.
            loadLyrics(track)
        }
        // "Currently playing" for the Home rows and the hero: the iOS
        // views compute `isCurrent && store.player.isPlaying`; this
        // collector keeps the same value current on play/pause.
        intentScope.launch {
            player.state.collect { state ->
                isPlayingCurrentStation = currentStation != null &&
                    (state == PlaybackState.playing || state == PlaybackState.buffering)
            }
        }
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

    // MARK: Station modes (tuning)

    private suspend fun loadStationModes(station: pandoracore.Station?) {
        val target = station ?: currentStation ?: return
        if (isLoadingModes || isChangingMode) return
        isLoadingModes = true
        try {
            val modes = session.getStationModes(target)
            stationModes = modes
            modesStationId = target.id
            AppLog.info(
                "station modes: ${modes.modes.size} offered for \"${target.name}\"",
                AppLog.Category.app,
            )
        } catch (error: Exception) {
            AppLog.error("station modes failed: $error")
            record(error)
        } finally {
            isLoadingModes = false
        }
    }

    private suspend fun setStationMode(
        mode: pandoracore.StationMode,
        station: pandoracore.Station?,
    ) {
        val target = station ?: currentStation ?: return
        if (isChangingMode) return
        isChangingMode = true
        try {
            // previousModeId only counts when the cached modes describe
            // this same station.
            val previous = if (modesStationId == target.id) stationModes?.currentModeID else null
            val updated = session.setStationMode(mode, target, previous)
            stationModes = updated
            modesStationId = target.id
            stationModeChangedTick += 1
            if (currentStation?.id == target.id && player.currentTrack.value != null) {
                // "A successful change starts a new song": confirm and
                // restart the playing station's playlist (its queued
                // tracks belong to the previous mode).
                showConfirmation("Tuned to ${mode.name}")
                AppLog.info(
                    "station mode set: ${mode.name} on \"${target.name}\"",
                    AppLog.Category.playback,
                )
                restartWithNewPlaylist(target)
            } else {
                AppLog.info("station mode set for next play: ${mode.name} on \"${target.name}\"")
            }
        } catch (error: Exception) {
            AppLog.error("station mode ${mode.name} failed: $error")
            record(error)
        } finally {
            isChangingMode = false
        }
    }

    // MARK: Playback (the port of AppStore's Playback section)

    /**
     * Plays a station: fetches its playlist, starts the first track and
     * queues the next one (prefetch). Tapping the station that is already
     * playing toggles playback instead of restarting it.
     */
    private suspend fun play(station: pandoracore.Station) {
        if (currentStation?.id == station.id && player.currentTrack.value != null) {
            player.togglePlayback()
            return
        }
        if (isLoadingPlaylist) return
        lastErrorMessage = null
        isLoadingPlaylist = true
        try {
            val playlist = session.getPlaylist(station)
            val playable = playlist.tracks.filter { it.streamURL != null }
            AppLog.info(
                "playlist for \"${station.name}\": ${playlist.tracks.size} entries, " +
                    "${playable.size} playable",
                AppLog.Category.playback,
            )
            val first = playable.firstOrNull()
            if (first == null) {
                currentStation = station
                lastErrorMessage = "No playable tracks in this station right now."
                return
            }
            currentStation = station
            // The player is singular: a Pandora station takes the player
            // over from a radio stream. Leaving `currentRadioStation`
            // set would keep the radio row marked as playing and make
            // "next" stop the (already stopped) radio stream instead of
            // skipping the Pandora track.
            currentRadioStation = null
            stopRadioIdentification()
            // A different station invalidates the cached listening modes.
            stationModes = null
            modesStationId = null
            queuedTrackTokens = playable.map { it.trackToken }.toMutableSet()
            prefetchRetryAttempts = 0
            prefetchRetryJob?.cancel()
            updatePreferences { it.copy(lastStationId = station.id) }
            // The station plays again — the limit (if there was one) is
            // gone.
            if (skipLimitStationId == station.id) {
                skipLimitStationId = null
            }
            player.play(track = first, prefetching = playable.getOrNull(1))
        } catch (error: PlaylistError.SkipLimitReached) {
            // Pandora's daily skip limit: report it and stop asking.
            handleSkipLimit(error, station)
        } catch (error: Exception) {
            AppLog.error(
                "playlist for \"${station.name}\" failed: $error",
                AppLog.Category.playback,
            )
            record(error)
        } finally {
            isLoadingPlaylist = false
        }
    }

    /**
     * Called by the player when its queue runs low — fetch the next
     * playlist for the current station and queue the new tracks.
     */
    private fun fetchMoreTracks() {
        val station = currentStation ?: return
        if (isLoadingPlaylist) return
        isLoadingPlaylist = true
        intentScope.launch {
            try {
                val playlist = session.getPlaylist(station)
                val fresh = playlist.tracks.filter {
                    it.streamURL != null && it.trackToken !in queuedTrackTokens
                }
                if (fresh.isEmpty()) {
                    AppLog.info(
                        "prefetch playlist: nothing new to queue",
                        AppLog.Category.playback,
                    )
                    return@launch
                }
                AppLog.info(
                    "prefetch playlist: queueing ${fresh.size} more tracks",
                    AppLog.Category.playback,
                )
                queuedTrackTokens.addAll(fresh.map { it.trackToken })
                prefetchRetryAttempts = 0
                prefetchRetryJob?.cancel()
                player.enqueue(fresh)
            } catch (error: PlaylistError.SkipLimitReached) {
                // The daily skip limit: the server refuses more tracks.
                // Retrying would only hammer it — report and stop.
                handleSkipLimit(error, station)
            } catch (error: Exception) {
                // A failed prefetch is not fatal — the current track keeps
                // playing. One backed-off retry follows; a network blip
                // near the queue's end must not dead-end playback.
                AppLog.error("prefetch playlist failed: $error", AppLog.Category.playback)
                record(error)
                schedulePrefetchRetry()
            } finally {
                isLoadingPlaylist = false
            }
        }
    }

    /**
     * One delayed prefetch retry after a failure, backing off
     * 5 s / 15 s / 30 s, then giving up — the next user action
     * (playing the station again) fetches fresh.
     */
    private fun schedulePrefetchRetry() {
        prefetchRetryJob?.cancel()
        if (prefetchRetryAttempts >= 3) return
        val delays = longArrayOf(5_000, 15_000, 30_000)
        val backoff = delays[prefetchRetryAttempts]
        prefetchRetryAttempts += 1
        prefetchRetryJob = intentScope.launch {
            delay(backoff)
            AppLog.info(
                "prefetch: retrying in-case the queue ran dry",
                AppLog.Category.playback,
            )
            fetchMoreTracks()
        }
    }

    /** Skip to the next track (user intent). pydora: "Cannot skip
     *  advertisements" — the impression plays out. Radio has no next
     *  track — "next" stops the stream (the store's guard; the player
     *  sees no radio-specific case). */
    private fun skipToNext() {
        if (player.currentTrack.value?.isAdvertisement == true) {
            AppLog.info("skip requested during an ad — refused", AppLog.Category.playback)
            lastErrorMessage = "Advertisements can't be skipped."
            return
        }
        if (currentRadioStation != null) {
            stopRadio()
            return
        }
        player.skipToNext()
    }

    /** Records Pandora's refusal to hand out more tracks: the limit is
     *  reported to the user and the app stops asking for this station —
     *  no prefetch, no retry. The playback that is already queued keeps
     *  playing. */
    private fun handleSkipLimit(error: PlaylistError.SkipLimitReached, station: pandoracore.Station) {
        skipLimitStationId = station.id
        lastErrorMessage =
            "Pandora's daily skip limit was reached for \"${station.name}\". Try again later."
        prefetchRetryJob?.cancel()
        prefetchRetryJob = null
        AppLog.info(
            "skip limit reached for \"${station.name}\" " +
                "(${if (error.playlistEnded) "playlist end" else "skip refused"}) " +
                "— not requesting more tracks",
            AppLog.Category.playback,
        )
    }

    /**
     * Resolves + registers the impression for an advertisement that just
     * started (pydora `AdItem.prepare_playback`). Only ads carrying
     * inline audio reach the queue (`streamURL` filter in `play` /
     * `fetchMoreTracks`), so by the time this runs the ad is audible.
     * Best-effort: `prepareAd` swallows registration failures; a
     * metadata failure here only logs — playback continues.
     */
    private fun prepareAdIfNeeded(track: pandoracore.Track?) {
        adJob?.cancel()
        if (track == null || !track.isAdvertisement || track.adToken == null) return
        AppLog.info(
            "advertisement started: \"${track.title}\" — registering impression",
            AppLog.Category.playback,
        )
        adJob = intentScope.launch {
            try {
                val ad = session.prepareAd(track)
                AppLog.info(
                    "ad impression registered (${ad.trackingTokens.size} tracking tokens)",
                    AppLog.Category.playback,
                )
            } catch (error: Exception) {
                AppLog.error(
                    "ad prepare failed (playback continues): $error",
                    AppLog.Category.playback,
                )
            }
        }
    }

    /**
     * Fetches a fresh playlist for `station` and starts its first
     * track, replacing the queue (whose tracks belong to the previous
     * mode). `PlaybackClient.play` resets the queue.
     */
    private suspend fun restartWithNewPlaylist(station: pandoracore.Station) {
        if (isLoadingPlaylist) return
        isLoadingPlaylist = true
        try {
            val playlist = session.getPlaylist(station)
            val playable = playlist.tracks.filter { it.streamURL != null }
            val first = playable.firstOrNull()
            if (first == null) {
                lastErrorMessage = "No playable tracks in this station right now."
                return
            }
            queuedTrackTokens = playable.map { it.trackToken }.toMutableSet()
            prefetchRetryAttempts = 0
            prefetchRetryJob?.cancel()
            player.play(track = first, prefetching = playable.getOrNull(1))
        } catch (error: PlaylistError.SkipLimitReached) {
            handleSkipLimit(error, station)
        } catch (error: Exception) {
            AppLog.error("restart after tuning failed: $error", AppLog.Category.playback)
            record(error)
        } finally {
            isLoadingPlaylist = false
        }
    }

    /** Shows a short confirmation in the player, clearing it after a
     *  few seconds. A newer message replaces the pending one. */
    private fun showConfirmation(message: String) {
        confirmationJob?.cancel()
        confirmationMessage = message
        confirmationJob = intentScope.launch {
            delay(3_000)
            confirmationMessage = null
        }
    }

    // MARK: Lyrics (the port of AppStore's Lyrics section; the radio
    // branch — an identified radio song owning the panel — arrives
    // with A7)

    /**
     * Loads lyrics for the current track (lrclib.net first, via the
     * core's provider chain). Results — including "not found" — are
     * cached per `trackToken`, so repeat listens never re-hit the
     * service. A track change cancels the in-flight fetch; a
     * superseded fetch never touches state.
     */
    private fun loadLyrics(track: Track?, force: Boolean = false) {
        lyricsJob?.cancel()
        lyricsJob = null
        translationJob?.cancel()
        translationJob = null
        romanizationJob?.cancel()
        romanizationJob = null
        if (track == null || track.isAdvertisement || track.isRadio) {
            // A live radio stream has no song behind it (an identified
            // radio song loads its lyrics through its own synthetic
            // track — A7.4); the guard clears any state from a Pandora
            // track.
            lyrics = null
            isLoadingLyrics = false
            lyricsTranslationState = LyricsTranslationState.None
            return
        }
        if (!force) {
            val cached = lyricsCache[track.trackToken]
            if (cached != null) {
                lyrics = cached
                isLoadingLyrics = false
                lyricsTranslationState = if (cached.hasTranslation) {
                    LyricsTranslationState.Translated
                } else {
                    LyricsTranslationState.None
                }
                return
            }
        }
        lyrics = null
        isLoadingLyrics = true
        lyricsTranslationState = LyricsTranslationState.None
        val provider = lyricsProvider
        AppLog.info(
            "lyrics: lookup for \"${track.title}\" — \"${track.artist}\"",
            AppLog.Category.lyrics,
        )
        lyricsJob = intentScope.launch {
            try {
                val fetched = provider.lyrics(track)
                // A cancelled job dies at the await above; the state
                // below only runs for live fetches.
                lyricsCache[track.trackToken] = fetched
                lyrics = fetched
                isLoadingLyrics = false
                AppLog.info(
                    "lyrics: ${describe(fetched)} for \"${track.title}\"",
                    AppLog.Category.lyrics,
                )
                requestTranslation(track, manual = false)
                requestRomanization(track)
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Superseded by a newer track — that one owns the state.
                throw error
            } catch (error: Exception) {
                isLoadingLyrics = false
                if (error is LyricsError.NotFound) {
                    // "Not found" is cached — the panel offers a retry,
                    // but a repeat visit costs no network.
                    lyricsCache[track.trackToken] = Lyrics.empty
                }
                lyrics = null
                AppLog.info(
                    "lyrics: unavailable for \"${track.title}\" ($error)",
                    AppLog.Category.lyrics,
                )
            }
        }
    }

    /** Short, loggable description of what a lyrics lookup returned. */
    private fun describe(fetched: Lyrics): String = when {
        fetched.instrumental -> "instrumental"
        fetched.isSynced -> "synced, ${fetched.lines.size} lines"
        fetched.plainText != null -> "plain text"
        else -> "empty"
    }

    /** `true` when `token` is the track whose lyrics are on screen —
     *  the radio-aware version lives in the radio section (an
     *  identified radio song's synthetic token owns the panel there). */

    /**
     * Translates the current lyrics into English.
     *
     * The automatic pass (`manual = false`) runs when the lyrics
     * arrive; the manual pass is the lyrics panel's **Translate**
     * button — the answer to "there is no way to translate this song"
     * (the early automatic pass ran only on synced lyrics and gave up
     * silently; plain-text lyrics had nothing to press).
     *
     * The three answers stay apart: a translation to show, lyrics that
     * are already in English (nothing to do) and a failed request
     * (worth a retry).
     */
    private fun requestTranslation(track: Track?, manual: Boolean) {
        val current = lyrics ?: return
        if (current.isEmpty) return
        // Already translated — nothing to ask for. (The manual pass
        // lands here too: the button disappears once it worked.)
        if (current.hasTranslation) {
            lyricsTranslationState = LyricsTranslationState.Translated
            return
        }
        // The lines to translate: the synchronised lines, or the plain
        // text split into lines. The service keeps the line breaks, so
        // the answer lines up with the input one-to-one.
        val texts: List<String> = if (current.isSynced) {
            current.lines.map { it.text }
        } else {
            val plain = current.plainText ?: return
            plain.split("\n")
        }
        if (texts.isEmpty()) return

        translationJob?.cancel()
        lyricsTranslationState = LyricsTranslationState.Translating
        val translator = lyricsTranslator
        AppLog.info(
            "lyrics: translating ${texts.size} lines" +
                (if (manual) " (manual)" else "") +
                " for \"${track?.title}\"",
            AppLog.Category.lyrics,
        )
        val token = track?.trackToken
        translationJob = intentScope.launch {
            val outcome = translator.englishTranslation(texts)
            // A superseded fetch (a new track, a newer request) never
            // touches state — the token must still be the live one.
            if (!isActive) return@launch
            if (token == null || !isCurrentLyricsToken(token)) return@launch
            when (outcome) {
                is LyricsTranslation.Translated -> {
                    if (applyTranslation(outcome.lines, token)) {
                        lyricsTranslationState = LyricsTranslationState.Translated
                        AppLog.info(
                            "lyrics: translation applied for \"${track?.title}\"",
                            AppLog.Category.lyrics,
                        )
                    } else {
                        // The answer did not line up with the lines — it
                        // is refused rather than shown against the wrong
                        // words.
                        lyricsTranslationState = LyricsTranslationState.Failed(
                            "The translation didn't line up. Try again.",
                        )
                        AppLog.info(
                            "lyrics: misaligned translation for \"${track?.title}\" — refused",
                            AppLog.Category.lyrics,
                        )
                    }
                }
                LyricsTranslation.AlreadyEnglish -> {
                    lyricsTranslationState = LyricsTranslationState.AlreadyEnglish
                    AppLog.info(
                        "lyrics: already English — \"${track?.title}\"",
                        AppLog.Category.lyrics,
                    )
                }
                is LyricsTranslation.Failed -> {
                    lyricsTranslationState = LyricsTranslationState.Failed(
                        "Couldn't translate the lyrics. Try again.",
                    )
                    AppLog.info(
                        "lyrics: translation failed for \"${track?.title}\" (${outcome.reason})",
                        AppLog.Category.lyrics,
                    )
                }
            }
        }
    }

    /**
     * Kicks off an alphabet romanization for the current lyrics — the
     * reading line for lyrics written in a non-Latin script ("사랑해" →
     * "salanghae"). The endpoint skips Latin-script text before any
     * request, so an English or French song costs nothing here. It is
     * independent of the translation: neither suppresses the other.
     */
    private fun requestRomanization(track: Track) {
        val current = lyrics ?: return
        if (!current.isSynced || current.lines.isEmpty()) return
        val texts = current.lines.map { it.text }
        val romanizer = lyricsRomanizer
        val token = track.trackToken
        romanizationJob = intentScope.launch {
            val romanized = romanizer.romanization(texts)
            if (!isActive) return@launch
            if (romanized != null) {
                applyRomanization(romanized, token)
                AppLog.info(
                    "lyrics: romanization applied for \"${track.title}\"",
                    AppLog.Category.lyrics,
                )
            } else {
                AppLog.info(
                    "lyrics: no romanization for \"${track.title}\"",
                    AppLog.Category.lyrics,
                )
            }
        }
    }

    /**
     * Fills the English translation on the cached lyrics for `token`
     * and, if that track is still current, on the live state.
     *
     * The alignment guard lives in the core (`Lyrics.applyingTranslation`):
     * a misaligned answer is refused rather than shown against the wrong
     * lines. Returns `false` when the answer could not be applied.
     */
    private fun applyTranslation(translated: List<String>, token: String): Boolean {
        val cached = lyricsCache[token] ?: return false
        val updated = if (cached.isSynced) {
            cached.applyingTranslation(translated)
        } else {
            cached.applyingPlainTranslation(translated.joinToString("\n"))
        }
        if (!updated.hasTranslation) return false
        lyricsCache[token] = updated
        if (isCurrentLyricsToken(token)) {
            lyrics = updated
        }
        return true
    }

    /**
     * Fills `LyricLine.romanization` on the cached lyrics for `token`
     * and, if that track is still current, on the live state. Guarded
     * by line count so a mismatched response can never shift the
     * alignment. Mirrors `applyTranslation`; each helper rebuilds from
     * the current cache entry, so whichever lands second keeps the
     * first one's field.
     */
    private fun applyRomanization(romanized: List<String>, token: String) {
        val cached = lyricsCache[token] ?: return
        val updated = cached.applyingRomanization(romanized)
        lyricsCache[token] = updated
        if (isCurrentLyricsToken(token)) {
            lyrics = updated
        }
    }

    // MARK: Radio playback (the port of AppStore's Radio section)

    /**
     * Plays one directory station's live stream. Tapping the station
     * that is already playing toggles playback instead of restarting.
     * Starting radio stops Pandora playback and vice versa — the
     * player is singular.
     *
     * Port delta: iOS's onTrackChanged observer carries a "radio station
     * lost its player slot" guard because a remote-command skip can
     * empty a single-item AVQueuePlayer silently; on Android every
     * queue-empty path already reports the nil transition through
     * PlaybackClient, and stopRadio clears the station itself, so the
     * guard has nothing left to catch here.
     */
    private fun playRadio(station: pandoracore.RadioStation) {
        if (currentRadioStation?.uuid == station.uuid &&
            player.currentTrack.value != null
        ) {
            player.togglePlayback()
            return
        }
        AppLog.info("radio: playing \"${station.name}\"", AppLog.Category.playback)
        currentRadioStation = station
        currentStation = null
        queuedTrackTokens = mutableSetOf()
        prefetchRetryAttempts = 0
        prefetchRetryJob?.cancel()
        stationModes = null
        modesStationId = null
        recordRadioRecent(station)
        player.playLiveStream(track = radioTrack(station))
        // Listen along for the station's now-playing announcements (the
        // probe holds its own read-only connection).
        startRadioIdentification(station)
        // Directory click counter (Shortwave parity) — best-effort,
        // never blocks playback. Custom stations aren't in the
        // directory, so there is nothing to count.
        if (!isRadioCustom(station)) {
            val uuid = station.uuid
            intentScope.launch { radioClient.registerClick(uuid) }
        }
    }

    /** Stops radio playback and forgets the station. The moment
     *  playback stops the probe's second connection goes too
     *  (bandwidth). */
    private fun stopRadio() {
        if (currentRadioStation == null) return
        AppLog.info("radio: stopped", AppLog.Category.playback)
        currentRadioStation = null
        lyrics = null
        stopRadioIdentification()
        player.stop()
    }

    /** Moves a station to the top of the recents list (deduped), keeping
     *  the list bounded like Shortwave's. */
    private fun recordRadioRecent(station: pandoracore.RadioStation) {
        radioRecents = listOf(station) + radioRecents.filterNot { it.uuid == station.uuid }
        if (radioRecents.size > 20) {
            radioRecents = radioRecents.dropLast(radioRecents.size - 20)
        }
        updatePreferences { it.copy(radioRecents = radioRecents) }
    }

    // MARK: Radio now-playing identification (ICY metadata)

    /**
     * Starts listening to the station's stream metadata. A late event
     * from a previous station's probe is dropped by the identity check
     * in the delivery hop.
     */
    private fun startRadioIdentification(station: pandoracore.RadioStation) {
        radioNowPlaying = null
        radioIdentification = RadioIdentification.identifying
        if (station.streamURL.toHttpUrlOrNull() == null) {
            AppLog.info(
                "radio metadata: station has no usable stream URL",
                AppLog.Category.playback,
            )
            return
        }
        val stationUUID = station.uuid
        val probe = radioMetadataProbe
        probe.start(url = station.streamURL) { event ->
            // The probe delivers from a background thread — hop the
            // state handling onto main (iOS: Task { @MainActor … }).
            intentScope.launch {
                if (currentRadioStation?.uuid != stationUUID) return@launch
                handleRadioMetadata(event)
            }
        }
    }

    /**
     * Stops the probe and clears the identification — the moment
     * playback stops the second connection goes too (bandwidth).
     */
    private fun stopRadioIdentification() {
        radioNowPlaying = null
        radioIdentification = RadioIdentification.inactive
        radioSongArtwork = null
        isIdentifyingRadioSong = false
        radioLyricsToken = null
        radioLyricsTrack = null
        radioLookupJob?.cancel()
        radioLookupJob = null
        radioAnnouncementJob?.cancel()
        radioAnnouncementJob = null
        radioMetadataProbe.stop()
    }

    private fun handleRadioMetadata(event: RadioMetadataProbe.Event) {
        when (event) {
            RadioMetadataProbe.Event.Listening -> {
                // Connected; a title is expected but has not arrived.
                if (radioNowPlaying == null) {
                    radioIdentification = RadioIdentification.identifying
                }
            }
            is RadioMetadataProbe.Event.Announced ->
                scheduleRadioAnnouncement(event.nowPlaying)
            RadioMetadataProbe.Event.AnnouncesNothing ->
                radioIdentification = RadioIdentification.stationAnnouncesNothing
        }
    }

    /**
     * Applies an announcement when the *audio* reaches it.
     *
     * The probe reads the station's metadata at the stream's live
     * edge, but the player buffers behind that edge (up to 30 s of the
     * forward-buffer tuning) — applying the announcement the moment it
     * arrives swapped the header, the cover and the lyrics up to half
     * a song before the listener heard the song (user report
     * 2026-11-12). The player measures its own lag
     * (`PlaybackClient.liveStreamLag`); a short gap applies as before,
     * a real one waits it out. A newer announcement cancels the
     * waiting one, and a station switch or stop cancels everything —
     * the identity check at application time is the second line of
     * defence.
     */
    private fun scheduleRadioAnnouncement(nowPlaying: pandoracore.RadioNowPlaying) {
        radioAnnouncementJob?.cancel()
        radioAnnouncementJob = null
        val station = currentRadioStation ?: return
        val uuid = station.uuid
        val lag = player.liveStreamLag
        if (lag > 1.5) {
            radioAnnouncementJob = intentScope.launch {
                delay((lag * 1000).toLong())
                applyRadioAnnouncement(nowPlaying, uuid)
            }
        } else {
            applyRadioAnnouncement(nowPlaying, uuid)
        }
    }

    private fun applyRadioAnnouncement(
        nowPlaying: pandoracore.RadioNowPlaying,
        stationUUID: String,
    ) {
        if (currentRadioStation?.uuid != stationUUID) return
        radioNowPlaying = nowPlaying
        radioIdentification = RadioIdentification.identified
        // The station announced a (new) song — look its cover and
        // lyrics up on its own, without a tap.
        identifyRadioSong(nowPlaying, stationUUID)
    }

    // MARK: The identified radio song (artwork + lyrics)

    /**
     * Looks the announced song's artwork and lyrics up. Runs
     * automatically on every announcement, and again from the
     * Identify button — the retry for a lookup that found nothing.
     */
    private fun identifyRadioSong(
        nowPlaying: pandoracore.RadioNowPlaying,
        stationUUID: String,
    ) {
        radioLookupJob?.cancel()
        radioSongArtwork = null
        isIdentifyingRadioSong = true

        // The lyrics provider wants a Track — this synthetic one is
        // built from the announcement and never reaches the player, so
        // it can never be rated, scrobbled, bookmarked or recorded; it
        // only feeds the lyrics path and its cache.
        val synthetic = radioSongTrack(nowPlaying, stationUUID)
        loadLyrics(synthetic)
        // The player's current track is still the stream — the lyrics
        // panel must answer to the synthetic token while it is up.
        radioLyricsToken = synthetic.trackToken
        radioLyricsTrack = synthetic

        val client = songArtworkClient
        radioLookupJob = intentScope.launch {
            val artwork = try {
                client.artwork(
                    artist = nowPlaying.artist ?: "",
                    title = nowPlaying.title,
                )
            } catch (_: Exception) {
                null
            }
            // A cancelled lookup never touches state.
            if (!isActive) return@launch
            isIdentifyingRadioSong = false
            radioSongArtwork = artwork
            if (artwork == null) {
                AppLog.info(
                    "radio metadata: no trustworthy artwork for \"${nowPlaying.rawTitle}\"",
                    AppLog.Category.playback,
                )
            }
        }
    }

    /**
     * The synthetic track the lyrics provider sees for an announced
     * song. The token deliberately does not carry the radio prefix —
     * `Track.isRadio` would make the lyrics loader refuse it — but the
     * track never reaches the player regardless.
     */
    private fun radioSongTrack(
        nowPlaying: pandoracore.RadioNowPlaying,
        stationUUID: String,
    ): Track = Track(
        trackToken = "radio-song.$stationUUID.${nowPlaying.rawTitle}",
        title = nowPlaying.title,
        artist = nowPlaying.artist ?: "",
        album = "",
        artworkUrl = "",
        duration = 0,
        stationId = stationUUID,
        variants = emptyList(),
    )

    /**
     * `true` when `token` is the track whose lyrics are on screen: the
     * player's current track — or, while a radio station's identified
     * song owns the lyrics panel, that song's synthetic token.
     */
    private fun isCurrentLyricsToken(token: String): Boolean {
        val radio = radioLyricsToken ?: return player.currentTrack.value?.trackToken == token
        return token == radio
    }

    /**
     * The track whose lyrics are on screen — `player.currentTrack`,
     * except while a radio station's identified song owns the panel.
     */
    private fun currentLyricsTrack(): Track? = radioLyricsTrack ?: player.currentTrack.value

    /**
     * The Identify button. With an announcement: retries the song's
     * lookup (an earlier attempt may have found nothing). Without one:
     * reconnects the probe — a fresh connection often carries the
     * current title within the first metadata interval. A station that
     * truly announces nothing says so beside the LIVE badge.
     */
    private fun identifyRadioSongIntent() {
        val nowPlaying = radioNowPlaying
        val station = currentRadioStation
        if (nowPlaying != null && station != null) {
            identifyRadioSong(nowPlaying, station.uuid)
        } else if (station != null) {
            AppLog.info(
                "radio metadata: identify requested — restarting the probe",
                AppLog.Category.playback,
            )
            startRadioIdentification(station)
        }
    }

    // MARK: Radio favorites (device-local, the Shortwave port)

    /** Stars or unstars a station (device-local favorites). */
    private fun toggleRadioFavorite(station: pandoracore.RadioStation) {
        radioFavorites = if (radioFavorites.any { it.uuid == station.uuid }) {
            radioFavorites.filterNot { it.uuid == station.uuid }
        } else {
            listOf(station) + radioFavorites
        }
        // Port delta: Swift's localizedCaseInsensitiveCompare sort
        // becomes a lowercase sort — the same stable, never-shuffle
        // choice the station list's displayOrder made.
        radioFavorites = radioFavorites.sortedBy { it.name.lowercase() }
        updatePreferences { it.copy(radioFavorites = radioFavorites) }
    }

    /** True when the station is starred — row state for the Radio tab. */
    fun isRadioFavorite(station: pandoracore.RadioStation): Boolean =
        radioFavorites.any { it.uuid == station.uuid }

    // MARK: Custom radio stations (radio R5)

    /** True when the station is one the user added by URL (not a
     *  directory entry) — drives the Radio tab's edit/delete menus and
     *  the click-ping skip. */
    fun isRadioCustom(station: pandoracore.RadioStation): Boolean =
        radioCustomStations.any { it.uuid == station.uuid }

    /**
     * Saves a custom station (name + raw stream URL). The form sheet
     * validates both; the store just persists. Saving is synchronous —
     * no network — so the sheet dismisses immediately.
     */
    private fun addRadioCustomStation(name: String, url: String) {
        val station = pandoracore.RadioStation(
            uuid = java.util.UUID.randomUUID().toString().lowercase(),
            name = name,
            streamURL = url,
        )
        radioCustomStations = radioCustomStations + station
        updatePreferences { it.copy(radioCustomStations = radioCustomStations) }
        AppLog.info("radio: added custom station \"$name\"", AppLog.Category.network)
    }

    /**
     * Applies a form edit: same uuid, new name/URL. The user's other
     * lists and the playing snapshot follow the new identity (the
     * stream keeps playing until restarted — a restart picks the new
     * URL).
     */
    private fun updateRadioCustomStation(
        station: pandoracore.RadioStation,
        name: String,
        url: String,
    ) {
        val index = radioCustomStations.indexOfFirst { it.uuid == station.uuid }
        if (index < 0) return
        val updated = pandoracore.RadioStation(uuid = station.uuid, name = name, streamURL = url)
        radioCustomStations = radioCustomStations.toMutableList().also { it[index] = updated }
        if (radioFavorites.any { it.uuid == updated.uuid }) {
            radioFavorites = radioFavorites.map { if (it.uuid == updated.uuid) updated else it }
            updatePreferences { it.copy(radioFavorites = radioFavorites) }
        }
        if (radioRecents.any { it.uuid == updated.uuid }) {
            radioRecents = radioRecents.map { if (it.uuid == updated.uuid) updated else it }
            updatePreferences { it.copy(radioRecents = radioRecents) }
        }
        if (currentRadioStation?.uuid == updated.uuid) {
            currentRadioStation = updated
        }
        updatePreferences { it.copy(radioCustomStations = radioCustomStations) }
    }

    /**
     * Deletes a custom station everywhere: the list, favorites and
     * recents (it is gone, so stale entries would 404), and playback
     * if it was the one playing.
     */
    private fun deleteRadioCustomStation(station: pandoracore.RadioStation) {
        radioCustomStations = radioCustomStations.filterNot { it.uuid == station.uuid }
        updatePreferences { it.copy(radioCustomStations = radioCustomStations) }
        if (radioFavorites.any { it.uuid == station.uuid }) {
            radioFavorites = radioFavorites.filterNot { it.uuid == station.uuid }
            updatePreferences { it.copy(radioFavorites = radioFavorites) }
        }
        if (radioRecents.any { it.uuid == station.uuid }) {
            radioRecents = radioRecents.filterNot { it.uuid == station.uuid }
            updatePreferences { it.copy(radioRecents = radioRecents) }
        }
        if (currentRadioStation?.uuid == station.uuid) {
            stopRadio()
        }
    }

    // MARK: Feedback (the port of AppStore's rating section)

    /**
     * Rates the current track (thumbs up / down). Pithos `Song.rate`
     * semantics: the previous feedback is deleted first, then the new
     * one is set. Tapping the active rating removes it again — the UI
     * sends `RemoveCurrentRating` for that.
     */
    private suspend fun rateCurrentTrack(rating: FeedbackRating) {
        val track = player.currentTrack.value ?: return
        if (rating == FeedbackRating.none) return
        // Ads are not songs, and neither is a live radio stream (the
        // synthetic `radio.<uuid>` token) — nothing to rate.
        if (track.isAdvertisement || track.isRadio) return
        val station = currentStation ?: return
        lastErrorMessage = null
        try {
            currentFeedbackId?.let {
                session.deleteFeedback(feedbackId = it, stationToken = station.token)
                currentFeedbackId = null
            }
            val receipt = session.addFeedback(
                trackToken = track.trackToken,
                stationToken = station.token,
                rating = rating,
            )
            currentFeedbackId = receipt.feedbackId
            currentRating = rating
        } catch (error: Exception) {
            record(error)
        }
    }

    /** Removes the rating on the current track (thumbs up/down →
     *  unrated). Needs the feedback id of the active rating; without it
     *  there is nothing to undo. */
    private suspend fun removeCurrentRating() {
        val feedbackId = currentFeedbackId ?: return
        val station = currentStation ?: return
        lastErrorMessage = null
        try {
            session.deleteFeedback(feedbackId = feedbackId, stationToken = station.token)
            currentFeedbackId = null
            currentRating = FeedbackRating.none
        } catch (error: Exception) {
            record(error)
        }
    }

    // MARK: Sleep timer (the port of AppStore's sleep-timer section)

    /**
     * Sets (or clears) the sleep timer. A duration timer that has
     * expired stops playback; an end-of-track timer fires when the
     * current track finishes. Not an async intent — iOS dispatches it
     * synchronously (the sheet dismisses itself right after sending).
     *
     * Port delta: iOS names this `setSleepTimer(_:)`; Kotlin generates
     * a `setSleepTimer` setter for the `sleepTimer` property, so the
     * function takes iOS's own wording for the *apply* step instead.
     */
    private fun applySleepTimer(timer: SleepTimer) {
        sleepTimer = timer
        sleepTimerLabel = timer.label()
        updatePreferences { it.copy(sleepTimer = timer) }
        // A new timer replaces the old countdown.
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (timer.hasExpired()) {
            player.pause()
            sleepTimer = SleepTimer.off
            sleepTimerLabel = SleepTimer.off.label()
            updatePreferences { it.copy(sleepTimer = SleepTimer.off) }
            return
        }
        if (timer.mode != SleepTimer.Mode.duration) return
        startSleepTimerCountdown()
    }

    /**
     * Counts a duration timer down and stops playback when it expires.
     * The label is refreshed only when the visible minute count
     * changes, so the UI re-renders about once a minute — not every
     * second.
     */
    private fun startSleepTimerCountdown() {
        if (sleepTimer.mode != SleepTimer.Mode.duration) return
        sleepTimerJob = intentScope.launch {
            while (isActive) {
                val remaining = sleepTimer.remaining()
                val label = sleepTimer.label()
                if (label != sleepTimerLabel) {
                    sleepTimerLabel = label
                }
                if (remaining <= 0.0) {
                    fireSleepTimer()
                    return@launch
                }
                // Wake up when the next label changes (or when the
                // timer ends) instead of polling every second.
                delay((minOf(remaining, 30.0) * 1000).toLong())
            }
        }
    }

    /** The timer expired (or the track it was waiting for ended): pause
     *  playback and clear the timer. */
    private fun fireSleepTimer() {
        player.pause()
        sleepTimer = SleepTimer.off
        sleepTimerLabel = SleepTimer.off.label()
        updatePreferences { it.copy(sleepTimer = SleepTimer.off) }
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    // MARK: Bookmarks & sleep-song (the port of AppStore's bookmark
    // section)

    /** Hides the current track for a few hours (`user.sleepSong`,
     *  pithos `Song.set_tired`) and moves on to the next one — the
     *  player menu's "Hide This Song". */
    private suspend fun sleepCurrentTrack() {
        val track = player.currentTrack.value ?: return
        // Ads are not songs. (The radio guard arrives with A7.)
        if (track.isAdvertisement) return
        lastErrorMessage = null
        try {
            session.sleepSong(trackToken = track.trackToken)
            // iOS drives the player directly here (the ad guard above
            // already cleared the only case the store's skip refuser
            // covers).
            player.skipToNext()
        } catch (error: Exception) {
            record(error)
        }
    }

    /** Bookmark the current song (`bookmark.addSongBookmark`). */
    private suspend fun bookmarkCurrentSong() {
        val track = player.currentTrack.value ?: return
        if (track.isAdvertisement) return
        lastErrorMessage = null
        try {
            session.addSongBookmark(trackToken = track.trackToken)
            refreshBookmarks()
            showConfirmation("Song bookmarked")
        } catch (error: Exception) {
            record(error)
        }
    }

    /** Bookmark the current artist (`bookmark.addArtistBookmark`). */
    private suspend fun bookmarkCurrentArtist() {
        val track = player.currentTrack.value ?: return
        if (track.isAdvertisement) return
        lastErrorMessage = null
        try {
            session.addArtistBookmark(trackToken = track.trackToken)
            refreshBookmarks()
            showConfirmation("Artist bookmarked")
        } catch (error: Exception) {
            record(error)
        }
    }

    /** Loads the user's bookmarks. Public: the Recents sheet refreshes
     *  on open (iOS's `BookmarksList` `.task`). */
    suspend fun refreshBookmarks() {
        if (isLoadingBookmarks) return
        isLoadingBookmarks = true
        try {
            bookmarks = session.getBookmarks()
        } catch (error: Exception) {
            record(error)
        } finally {
            isLoadingBookmarks = false
        }
    }

    /** Removes a bookmark (song or artist). */
    private suspend fun deleteBookmark(bookmark: Bookmark) {
        lastErrorMessage = null
        try {
            when (bookmark.kind) {
                Bookmark.Kind.song -> session.deleteSongBookmark(bookmark.bookmarkToken)
                Bookmark.Kind.artist -> session.deleteArtistBookmark(bookmark.bookmarkToken)
            }
            bookmarks = bookmarks.filterNot { it.bookmarkToken == bookmark.bookmarkToken }
        } catch (error: Exception) {
            record(error)
        }
    }

    // MARK: History rating (rate a song that has already played)

    /**
     * Rates a song from the Recents list ("pithos am able to like a
     * song even after it has already played").
     *
     * The rating belongs to the *song*, not to the play — it goes
     * through `SessionManager.rateTrack` (pithos `Song.rate` verbatim:
     * transform a shared station first, replace an existing rating by
     * its feedback id, and obtain an id when removing a rating whose
     * id is unknown by re-rating with the opposite rating first).
     *
     * The rating is written back into the history so the row shows it.
     */
    private suspend fun rateHistoryTrack(
        rating: FeedbackRating,
        entry: PlayHistory.Entry,
        station: pandoracore.Station,
    ) {
        lastErrorMessage = null
        ratingTrackTokens = ratingTrackTokens + entry.trackToken
        try {
            val result = session.rateTrack(
                trackToken = entry.trackToken,
                station = station,
                rating = rating,
            )
            history = history.copy().also {
                it.setRating(result.rating, entry.trackToken)
            }
            historyStore.save(history)
            AppLog.info(
                "history track rated: \"${entry.title}\" → ${result.rating.name}",
                AppLog.Category.playback,
            )
        } catch (error: Exception) {
            record(error)
        } finally {
            ratingTrackTokens = ratingTrackTokens - entry.trackToken
        }
    }

    // MARK: Skip limit

    /** `true` when Pandora's daily skip limit stopped the current
     *  station. The player shows it; the station list marks the row. */
    val isSkipLimitReached: Boolean
        get() {
            val id = skipLimitStationId ?: return false
            return currentStation?.id == id
        }

    /** The user-facing explanation of the skip limit. */
    val skipLimitMessage: String
        get() = "Pandora's daily skip limit was reached for this station. Try again later."

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
            // The player must not keep a dead session's audio running
            // (the iOS signOut path stops the player, clears the
            // skip-limit mark and forgets the radio station).
            player.stop()
            skipLimitStationId = null
            currentRadioStation = null
            stopRadioIdentification()
        }
    }
}

/** Maps Pandora's `songRating` onto the app's rating enum (the port of
 *  AppStore's static `feedbackRating(for:)`). */
private fun feedbackRatingFor(rating: pandoracore.Track.Rating?): FeedbackRating = when (rating) {
    pandoracore.Track.Rating.loved -> FeedbackRating.loved
    pandoracore.Track.Rating.banned -> FeedbackRating.banned
    null, pandoracore.Track.Rating.none -> FeedbackRating.none
}
