//
//  SessionManager.kt
//  pandoracore
//
//  Port of PandoraCore/SessionManager.swift — auth lifecycle for the
//  legacy JSON API (v5), mirroring `pithos.pandora.Pandora.connect()`
//  plus automatic re-auth:
//
//    partner login (plain body) → decrypt syncTime → time offset
//      → user login (Blowfish body) → tokens + store
//    on fault 1001 (invalid auth token): transparent re-login, replay the
//      failed call exactly once, then surface the typed error.
//
//  The Swift actor becomes a Mutex-serialized class — login/re-auth/replay
//  can never interleave two partner logins. Credential/token persistence
//  is injected (`SessionStore`); pandoracore stays free of platform APIs
//  (Android Keystore lands in the app target) and free of UI/audio.
//

package pandoracore

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

// MARK: Records

/** Established session tokens (all values the client needs to stamp later
 *  requests). Serializable so any store can persist it as-is. */
@Serializable
data class SessionTokens(
    val partnerId: String,
    val partnerAuthToken: String,
    val userId: String,
    val userAuthToken: String,
    /** `pandora_time − local_time`, fixed at partner login; drives syncTime. */
    val timeOffset: Double,
)

@Serializable
data class Credentials(
    val username: String,
    val password: String,
)

/** What a session store persists: tokens plus the credentials needed for
 *  transparent re-auth and next-launch auto-login. */
@Serializable
data class SessionRecord(
    val credentials: Credentials,
    val tokens: SessionTokens,
    val isSubscriber: Boolean,
)

// MARK: Store

/**
 * Persistence seam. Android-side implementation (Keystore) lives in the
 * app target; `InMemorySessionStore` serves tests and fallback.
 */
interface SessionStore {
    fun save(record: SessionRecord)
    fun load(): SessionRecord?
    fun clear()
}

class InMemorySessionStore(
    record: SessionRecord? = null,
) : SessionStore {
    private val lock = Any()
    private var record: SessionRecord? = record

    override fun save(record: SessionRecord) {
        synchronized(lock) { this.record = record }
    }

    override fun load(): SessionRecord? = synchronized(lock) { this.record }

    override fun clear() {
        synchronized(lock) { this.record = null }
    }
}

// MARK: Errors

/** Port of `SessionManagerError`. */
sealed class SessionManagerError : Exception() {
    /** No credentials/session — the app must show login first. */
    data object NotAuthenticated : SessionManagerError()

    /** A session field was missing or undecodable in an otherwise-ok response. */
    data class MalformedSession(val detail: String) : SessionManagerError()
}

// MARK: SessionManager

class SessionManager(
    private val client: LegacyPandoraClient,
    /**
     * Kotlin divergence: Swift defaults this to a URLSession-backed
     * client; the pure-Kotlin core has no transport, so the caller
     * (tests or the app's OkHttp adapter) always supplies one.
     */
    private val modes: PandoraModesClient,
    private val store: SessionStore? = null,
    private val now: () -> Instant = { Instant.now() },
) {
    @Volatile private var credentials: Credentials? = null
    @Volatile private var tokens: SessionTokens? = null
    @Volatile private var subscriber = false
    /** Station tokens already transformed into personal ones (pithos's
     *  `isCreator` flag). A shared station may only be transformed once,
     *  so the set keeps a repeat rating from calling the endpoint again. */
    private val transformedStations = mutableSetOf<String>()

    private val mutex = Mutex()

    /** Current session, null when signed out. */
    val session: SessionTokens?
        get() = tokens

    val isSubscriber: Boolean
        get() = subscriber

    val hasSession: Boolean
        get() = tokens != null

    private fun epochNow(): Double = now().toEpochMilli() / 1000.0

    // MARK: Login

    /**
     * Full connect flow (pithos `Pandora.connect`): partner login →
     * syncTime decode → user login → store. Resets any prior session first,
     * exactly like the reference implementation.
     */
    suspend fun login(username: String, password: String): SessionTokens = mutex.withLock {
        loginLocked(username, password)
    }

    /** The login body — the caller either holds the mutex (`login`,
     *  re-auth inside `authenticatedRequest`) or serializes another way
     *  (Kotlin Mutex is not re-entrant, unlike a Swift actor). */
    private suspend fun loginLocked(username: String, password: String): SessionTokens {
        // Reset — a failed re-auth must not leave a half-live session.
        credentials = null
        tokens = null
        subscriber = false
        client.context = URLBuilder.Context()

        // 1. Partner login — plain JSON body (no Blowfish), no auth query.
        val partner = client.request(
            method = PandoraAPI.Method.partnerLogin,
            parameters = mapOf(
                "username" to JSONValue.Str(PandoraAPI.partnerUsername),
                "password" to JSONValue.Str(PandoraAPI.partnerPassword),
                "deviceModel" to JSONValue.Str(PandoraAPI.partnerDeviceModel),
                "version" to JSONValue.Str(PandoraAPI.version),
            ),
            encrypt = false,
            now = epochNow(),
        )
        val partnerId = idField(partner, "partnerId")
        val partnerToken = partner.string("partnerAuthToken")
        val syncTimeHex = partner.string("syncTime")

        val localNow = now()
        val offset = timeOffset(
            encryptedSyncTime = syncTimeHex,
            localTime = localNow,
        )

        client.context = URLBuilder.Context(
            partnerId = partnerId,
            partnerAuthToken = partnerToken,
            timeOffset = offset,
        )

        // 2. User login — Blowfish body; syncTime + partnerAuthToken are
        //    stamped in by URLBuilder from the context.
        val user = client.request(
            method = PandoraAPI.Method.userLogin,
            parameters = mapOf(
                "username" to JSONValue.Str(username),
                "password" to JSONValue.Str(password),
                "loginType" to JSONValue.Str("user"),
                "returnIsSubscriber" to JSONValue.Bool(true),
            ),
            encrypt = true,
            now = epochNow(),
        )
        val userId = idField(user, "userId")
        val userToken = user.string("userAuthToken")
        val isSubscriber = user.bool("isSubscriber")

        val newTokens = SessionTokens(
            partnerId = partnerId,
            partnerAuthToken = partnerToken,
            userId = userId,
            userAuthToken = userToken,
            timeOffset = offset,
        )
        client.context = URLBuilder.Context(
            partnerId = partnerId,
            partnerAuthToken = partnerToken,
            userId = userId,
            userAuthToken = userToken,
            timeOffset = offset,
        )

        val newCredentials = Credentials(username = username, password = password)
        credentials = newCredentials
        tokens = newTokens
        subscriber = isSubscriber
        store?.save(
            SessionRecord(
                credentials = newCredentials,
                tokens = newTokens,
                isSubscriber = isSubscriber,
            )
        )
        return newTokens
    }

    /**
     * Restores a stored session into memory without a network round trip —
     * the fast path for warm launch. If the token is already expired, the
     * first `authenticatedRequest` triggers 1001 → transparent re-login.
     */
    suspend fun restore(): Boolean = mutex.withLock {
        val record = store?.load() ?: return@withLock false
        credentials = record.credentials
        tokens = record.tokens
        subscriber = record.isSubscriber
        client.context = URLBuilder.Context(
            partnerId = record.tokens.partnerId,
            partnerAuthToken = record.tokens.partnerAuthToken,
            userId = record.tokens.userId,
            userAuthToken = record.tokens.userAuthToken,
            timeOffset = record.tokens.timeOffset,
        )
        true
    }

    /**
     * Signs out: drops the in-memory credentials/tokens and clears the
     * stored session record. Idempotent and offline (no network call) —
     * stopping playback and clearing app-level caches is the caller's job.
     */
    suspend fun signOut() = mutex.withLock {
        credentials = null
        tokens = null
        subscriber = false
        client.context = URLBuilder.Context()
        try {
            store?.clear()
        } catch (_: Exception) {
            // Swift used `try?` — a failed clear never blocks sign-out.
        }
        Unit
    }

    // MARK: Authenticated requests

    /**
     * Encrypted request under the current session. On fault 1001 (session
     * expired) re-logins with the in-memory credentials and replays exactly
     * once; a second 1001 (or any other error) is surfaced as-is.
     */
    suspend fun authenticatedRequest(
        method: String,
        parameters: Map<String, JSONValue> = emptyMap(),
    ): Map<String, AnyJSON> = mutex.withLock {
        val creds = credentials
        if (creds == null || tokens == null) {
            throw SessionManagerError.NotAuthenticated
        }
        try {
            client.request(
                method = method,
                parameters = parameters,
                encrypt = true,
                now = epochNow(),
            )
        } catch (error: PandoraAPIError) {
            if (!error.isSessionExpired) throw error
            loginLocked(username = creds.username, password = creds.password)
            // Retry once — a second 1001 lands here and propagates.
            client.request(
                method = method,
                parameters = parameters,
                encrypt = true,
                now = epochNow(),
            )
        }
    }

    // MARK: Stations

    /**
     * The user's station list: `user.getStationList` with
     * `returnAllStations=true` (pithos `get_stations`) and
     * `includeStationArtUrl=true` (pydora `get_all_stations`) under the
     * current session — 1001 → transparent re-login, replay once.
     */
    suspend fun getStationList(): StationList {
        val result = authenticatedRequest(
            PandoraAPI.Method.getStationList,
            mapOf(
                "returnAllStations" to JSONValue.Bool(true),
                "includeStationArtUrl" to JSONValue.Bool(true),
            ),
        )
        return StationList.from(result)
    }

    /** Create from an artist/seed token (pithos `add_station_by_music_id`).
     *  The reply is a full station dict — same shape as list entries. */
    suspend fun createStation(musicToken: String): Station {
        val result = authenticatedRequest(
            PandoraAPI.Method.createStation,
            mapOf("musicToken" to JSONValue.Str(musicToken)),
        )
        return Station.from(result)
    }

    /**
     * Create seeded from a song (pithos `add_station_by_track_token`).
     * `musicType` is the seed's type tag from the search reply
     * (e.g. `"song"` / `"track"` — the value the API returned, not a
     * guessed literal).
     */
    suspend fun createStation(trackToken: String, musicType: String): Station {
        val result = authenticatedRequest(
            PandoraAPI.Method.createStation,
            mapOf(
                "trackToken" to JSONValue.Str(trackToken),
                "musicType" to JSONValue.Str(musicType),
            ),
        )
        return Station.from(result)
    }

    /**
     * Rename (pithos `Station.rename`): a shared station must first be
     * transformed into a personal one (`station.transformSharedStation`,
     * pithos `transformIfShared`) or the rename is rejected.
     */
    suspend fun renameStation(station: Station, newName: String) {
        if (newName == station.name) return
        if (station.isShared) {
            authenticatedRequest(
                PandoraAPI.Method.transformSharedStation,
                mapOf("stationToken" to JSONValue.Str(station.token)),
            )
        }
        authenticatedRequest(
            PandoraAPI.Method.renameStation,
            mapOf(
                "stationToken" to JSONValue.Str(station.token),
                "stationName" to JSONValue.Str(newName),
            ),
        )
    }

    /** Delete (pithos `delete_station` — station list membership only;
     *  the API does not delete the account-level seed). */
    suspend fun deleteStation(station: Station) {
        authenticatedRequest(
            PandoraAPI.Method.deleteStation,
            mapOf("stationToken" to JSONValue.Str(station.token)),
        )
    }

    /**
     * Search artists / songs / genre stations for the add-station flow.
     * Params match pithos `Pandora.search()` exactly; scoring/filtering/
     * ordering happen in `MusicSearchResult.from`.
     */
    suspend fun search(query: String): List<MusicSearchResult> {
        val result = authenticatedRequest(
            PandoraAPI.Method.musicSearch,
            mapOf(
                "includeGenreStations" to JSONValue.Bool(true),
                "includeNearMatches" to JSONValue.Bool(true),
                "searchText" to JSONValue.Str(query),
            ),
        )
        return MusicSearchResult.from(result)
    }

    /**
     * Pandora's featured stations by category (`station.getGenreStations`,
     * sent with no parameters — pydora, pancake, python-pandora and the
     * Sonos Indigo plugin all call it bare). Creating a real station from
     * one reuses `createStation(musicToken:)` with the genre station's
     * token (xivpiano `PandoraClient.cs`: "making one uses its token as
     * the music token").
     */
    suspend fun getGenreStations(): List<GenreCategory> {
        val result = authenticatedRequest(PandoraAPI.Method.getGenreStations)
        return GenreStations.from(result).categories
    }

    // MARK: Playback

    /**
     * Fetch a station's playlist (pithos `Station.get_playlist`).
     * Requests track lengths and both extra stream formats so the client
     * can always pick the highest available quality (see `Track.variants`).
     *
     * Pandora refuses to hand out more tracks once the account's daily
     * skip limit is reached. The server signals that by fault **message**
     * — `PLAYLIST_END` / `DAILY_SKIP_LIMIT_REACHED` (Elpis
     * `Station.cs:232`) — so it is mapped to a typed
     * `PlaylistError.SkipLimitReached` here. The caller can then tell
     * the user what happened and stop asking, instead of retrying a
     * request the server will keep refusing.
     */
    suspend fun getPlaylist(station: Station): Playlist = try {
        val result = authenticatedRequest(
            PandoraAPI.Method.getPlaylist,
            mapOf(
                "stationToken" to JSONValue.Str(station.token),
                "includeTrackLength" to JSONValue.Bool(true),
                "additionalAudioUrl" to JSONValue.Str(PandoraAPI.additionalAudioURLs),
            ),
        )
        Playlist(tracks = Track.from(result), fetchedAt = now())
    } catch (error: PandoraAPIError) {
        if (error.isSkipLimitReached) {
            throw PlaylistError.SkipLimitReached(playlistEnded = error.isPlaylistEnd)
        }
        throw error
    }

    // MARK: User settings

    /**
     * The account-level settings (`user.getSettings`): pithos reads the
     * explicit-content filter from this reply
     * (`Pandora.explicit_content_filter_state`). The call carries no
     * parameters of its own — the session token rides in the query and
     * the encrypted body, exactly like every other authenticated call.
     */
    suspend fun getUserSettings(): UserSettings {
        val result = authenticatedRequest(PandoraAPI.Method.getUserSettings)
        return UserSettings.from(result)
    }

    // MARK: Advertisements

    /**
     * Resolves an advertisement entry (pydora `get_ad_item`):
     * `ad.getAdMetadata` with `returnAdTrackingTokens=true` and
     * `supportAudioAds=true` under the current session.
     */
    suspend fun getAdMetadata(adToken: String): Advertisement {
        val result = authenticatedRequest(
            PandoraAPI.Method.getAdMetadata,
            mapOf(
                "adToken" to JSONValue.Str(adToken),
                "returnAdTrackingTokens" to JSONValue.Bool(true),
                "supportAudioAds" to JSONValue.Bool(true),
            ),
        )
        return Advertisement.from(adToken = adToken, result = result)
    }

    /**
     * Registers an ad impression (pydora `register_ad`): `ad.registerAd`
     * with the station id and the tracking tokens from the metadata
     * reply. Refuses empty token lists before any network (pydora
     * `ParameterMissing`).
     */
    suspend fun registerAd(stationId: String, trackingTokens: List<String>) {
        if (trackingTokens.isEmpty()) {
            throw AdvertisementError.MissingTrackingTokens
        }
        authenticatedRequest(
            PandoraAPI.Method.registerAd,
            mapOf(
                "stationId" to JSONValue.Str(stationId),
                "adTrackingTokens" to JSONValue.Array(trackingTokens.map { JSONValue.Str(it) }),
            ),
        )
    }

    /**
     * Resolve + register in one step (pydora `AdItem.prepare_playback`):
     * metadata is required, registration is best-effort. Deviation from
     * pydora documented: pydora propagates register errors out of
     * `prepare_playback`, Amphora swallows them — a failed impression
     * registration must never block playback (Priority 1). Call
     * `registerAd` directly for the strict contract. With no tracking
     * tokens in the reply, registration is skipped entirely (pydora
     * catches its own `ParameterMissing` in the same situation).
     */
    suspend fun prepareAd(ad: Track, stationId: String? = null): Advertisement {
        if (!ad.isAdvertisement || ad.adToken == null) {
            throw AdvertisementError.NotAnAdvertisement
        }
        val metadata = getAdMetadata(adToken = ad.adToken)
        val targetStation = stationId ?: ad.stationId
        if (metadata.trackingTokens.isNotEmpty() && targetStation.isNotEmpty()) {
            try {
                registerAd(stationId = targetStation, trackingTokens = metadata.trackingTokens)
            } catch (_: Exception) {
                // best-effort — see the doc above
            }
        }
        return metadata
    }

    // MARK: Station tuning (listening modes)

    /**
     * The station's listening modes ("My Mix", "Deep Cuts", …) from the
     * modern `interactiveradio` web API, authenticated with the legacy
     * session's user token (see `PandoraModesClient`). The station's
     * numeric `stationId` is the identifier both API generations share
     * (pandoCast passes it as `stationId`).
     *
     * 401/403 (the modern API's session-expiry signal) → transparent
     * re-login, retry once — the same contract as `authenticatedRequest`.
     */
    suspend fun getStationModes(station: Station): StationModes {
        val authToken = tokens?.userAuthToken ?: throw SessionManagerError.NotAuthenticated
        return try {
            modes.getModes(userAuthToken = authToken, stationId = station.id)
        } catch (error: StationModesError) {
            if (error !is StationModesError.Unauthorized) throw error
            reloginAfterUnauthorized()
            val fresh = tokens?.userAuthToken ?: throw SessionManagerError.NotAuthenticated
            modes.getModes(userAuthToken = fresh, stationId = station.id)
        }
    }

    /**
     * Select a listening mode. Refuses the call without touching the
     * network when the mode is not offered for the station, or when it is
     * premium-only and the account is free (panedora gates both ways).
     * Changing the mode changes what the *next* playlist fetch returns —
     * "changing modes starts a new song".
     */
    suspend fun setStationMode(
        mode: StationMode,
        station: Station,
        previousModeID: kotlin.Int? = null,
    ): StationModes {
        if (!mode.isAvailable) throw StationModesError.ModeNotOffered
        if (mode.isPremiumOnly && !subscriber) throw StationModesError.NotEntitled
        val authToken = tokens?.userAuthToken ?: throw SessionManagerError.NotAuthenticated
        return try {
            modes.setMode(
                userAuthToken = authToken,
                stationId = station.id,
                modeId = mode.id,
                previousModeId = previousModeID,
            )
        } catch (error: StationModesError) {
            if (error !is StationModesError.Unauthorized) throw error
            reloginAfterUnauthorized()
            val fresh = tokens?.userAuthToken ?: throw SessionManagerError.NotAuthenticated
            modes.setMode(
                userAuthToken = fresh,
                stationId = station.id,
                modeId = mode.id,
                previousModeId = previousModeID,
            )
        }
    }

    private suspend fun reloginAfterUnauthorized() {
        val creds = credentials ?: throw SessionManagerError.NotAuthenticated
        login(username = creds.username, password = creds.password)
    }

    // MARK: Feedback

    /**
     * Rate a track (thumbs up / thumbs down).
     *
     * `station.addFeedback` with `trackToken`, `stationToken` and
     * `isPositive` (pithos `Pandora.add_feedback` + libpiano's
     * `PIANO_REQUEST_ADD_FEEDBACK`). The reply carries the
     * `feedbackId` needed to undo the rating.
     */
    suspend fun addFeedback(
        trackToken: String,
        stationToken: String,
        rating: FeedbackRating,
    ): FeedbackReceipt {
        if (rating == FeedbackRating.none) {
            throw PandoraAPIError(code = 0, message = "cannot add a 'none' rating")
        }
        val result = authenticatedRequest(
            PandoraAPI.Method.addFeedback,
            mapOf(
                "trackToken" to JSONValue.Str(trackToken),
                "stationToken" to JSONValue.Str(stationToken),
                "isPositive" to JSONValue.Bool(rating.isPositive),
            ),
        )
        return FeedbackReceipt.from(result)
    }

    /**
     * Remove a rating (thumbs up/down → unrated).
     * `station.deleteFeedback` with `feedbackId` and `stationToken`
     * (pithos `Pandora.delete_feedback`; libpiano sends only the id).
     */
    suspend fun deleteFeedback(feedbackId: String, stationToken: String) {
        authenticatedRequest(
            PandoraAPI.Method.deleteFeedback,
            mapOf(
                "feedbackId" to JSONValue.Str(feedbackId),
                "stationToken" to JSONValue.Str(stationToken),
            ),
        )
    }

    /** Makes a shared station personal, once per station token.
     *
     *  Pithos calls `Station.transformIfShared()` before *any* mutation
     *  that a shared station rejects — including rating a track
     *  (`Song.rate` → `self.station.transformIfShared()`). The
     *  reference implementation keeps an `isCreator` flag on the station
     *  so the transform happens once; this mirrors that with a token set.
     *
     *  A failure here is not fatal: the caller's real request may still
     *  succeed on a station Pandora considers personal already (the
     *  `isShared` flag in a station list can be stale), so the error is
     *  swallowed and the caller proceeds. */
    suspend fun transformStationIfShared(station: Station): Boolean {
        synchronized(transformedStations) {
            if (!station.isShared || transformedStations.contains(station.token)) {
                return false
            }
        }
        return try {
            authenticatedRequest(
                PandoraAPI.Method.transformSharedStation,
                mapOf("stationToken" to JSONValue.Str(station.token)),
            )
            synchronized(transformedStations) { transformedStations.add(station.token) }
            true
        } catch (_: Exception) {
            // The station may already be personal server-side. Let the
            // caller's request decide.
            false
        }
    }

    /**
     * Sets a track's rating, replacing any rating it already has.
     *
     * This is Pithos's `Song.rate` verbatim:
     *
     *     self.station.transformIfShared()
     *     if rating == RATE_NONE:
     *         if not self.feedbackId:
     *             opposite = RATE_BAN if self.rating == RATE_LOVE else RATE_LOVE
     *             self.feedbackId = self.pandora.add_feedback(self.trackToken, opposite)
     *         self.pandora.delete_feedback(self.station.idToken, self.feedbackId)
     *     else:
     *         self.feedbackId = self.pandora.add_feedback(self.trackToken, rating)
     *
     * Two details matter for rating a song that has *already played*:
     *
     * 1. **The station is transformed first.** Pandora rejects
     *    feedback on a station the user has not personalised; the
     *    transform is what makes the station theirs. This is why a
     *    rating from the Recents list (where the station may not even
     *    be the one playing) has to go through here and not through a
     *    bare `addFeedback` call.
     * 2. **Removing a rating needs a `feedbackId`.** When the rating
     *    was set in an earlier session (or by another client), the id
     *    is unknown — Pithos re-rates with the *opposite* rating to
     *    obtain one, then deletes it. The same trick works here.
     *
     * @param trackToken the song to rate.
     * @param station the station the rating belongs to.
     * @param rating the new rating; `none` removes the rating.
     * @param knownFeedbackId the feedback id, when the caller already
     *     has one (the player path keeps the id of the rating it just
     *     set). Null means "look it up".
     * @return the track's rating after the call, and the feedback
     *     id when one is known (null after a removal).
     */
    suspend fun rateTrack(
        trackToken: String,
        station: Station,
        rating: FeedbackRating,
        knownFeedbackId: String? = null,
    ): TrackRatingResult {
        transformStationIfShared(station)

        if (rating == FeedbackRating.none) {
            // Pithos' trick: re-rate with the opposite rating to obtain a
            // feedback id, then delete that feedback.
            val opposite: FeedbackRating = FeedbackRating.loved
            val feedbackId = knownFeedbackId
                ?: addFeedback(
                    trackToken = trackToken,
                    stationToken = station.token,
                    rating = opposite,
                ).feedbackId
            deleteFeedback(
                feedbackId = feedbackId,
                stationToken = station.token,
            )
            return TrackRatingResult(rating = FeedbackRating.none, feedbackId = null)
        }

        // Replace an existing rating first — Pandora rejects a second
        // `addFeedback` for the same track.
        knownFeedbackId?.let {
            deleteFeedback(
                feedbackId = it,
                stationToken = station.token,
            )
        }
        val receipt = addFeedback(
            trackToken = trackToken,
            stationToken = station.token,
            rating = rating,
        )
        return TrackRatingResult(rating = rating, feedbackId = receipt.feedbackId)
    }

    /** Hide a track for the next few hours (`user.sleepSong`, pithos
     *  `Song.set_tired`). */
    suspend fun sleepSong(trackToken: String) {
        authenticatedRequest(
            PandoraAPI.Method.sleepSong,
            mapOf("trackToken" to JSONValue.Str(trackToken)),
        )
    }

    // MARK: Bookmarks

    /** The user's bookmarks (`user.getBookmarks`, pydora
     *  `APIClient.get_bookmarks`). */
    suspend fun getBookmarks(): List<Bookmark> {
        val result = authenticatedRequest(PandoraAPI.Method.getBookmarks)
        return Bookmark.from(result)
    }

    /** Bookmark a song (`bookmark.addSongBookmark`, pithos
     *  `Song.bookmark`). */
    suspend fun addSongBookmark(trackToken: String) {
        authenticatedRequest(
            PandoraAPI.Method.addSongBookmark,
            mapOf("trackToken" to JSONValue.Str(trackToken)),
        )
    }

    /** Bookmark the artist of a track (`bookmark.addArtistBookmark`,
     *  pithos `Song.bookmark_artist`). */
    suspend fun addArtistBookmark(trackToken: String) {
        authenticatedRequest(
            PandoraAPI.Method.addArtistBookmark,
            mapOf("trackToken" to JSONValue.Str(trackToken)),
        )
    }

    /** Remove a song bookmark (`bookmark.deleteSongBookmark`, pydora
     *  `delete_song_bookmark`). */
    suspend fun deleteSongBookmark(bookmarkToken: String) {
        authenticatedRequest(
            PandoraAPI.Method.deleteSongBookmark,
            mapOf("bookmarkToken" to JSONValue.Str(bookmarkToken)),
        )
    }

    /** Remove an artist bookmark (`bookmark.deleteArtistBookmark`, pydora
     *  `delete_artist_bookmark`). */
    suspend fun deleteArtistBookmark(bookmarkToken: String) {
        authenticatedRequest(
            PandoraAPI.Method.deleteArtistBookmark,
            mapOf("bookmarkToken" to JSONValue.Str(bookmarkToken)),
        )
    }

    // MARK: Helpers

    /**
     * partnerId/userId arrive as either JSON string or number depending on
     * the endpoint (pithos formats with `%s`, accepting both).
     */
    private fun idField(result: Map<String, AnyJSON>, key: String): String {
        result[key]?.stringValue?.let { return it }
        result[key]?.intValue?.let { return it.toString() }
        throw SessionManagerError.MalformedSession("missing id field $key")
    }

    companion object {
        /**
         * `offset = pandora_time − local_time`, where pandora_time is the
         * decrypted syncTime's bytes[4..<14] (pithos: `pandora_decrypt(...)[4:14]`).
         */
        fun timeOffset(encryptedSyncTime: String, localTime: Instant): Double {
            val decrypted = PandoraCrypto.decryptHexField(encryptedSyncTime)
            if (decrypted.size < 14) {
                throw SessionManagerError.MalformedSession("syncTime too short after decrypt")
            }
            val stamp = String(decrypted, 4, 10, Charsets.UTF_8).toLongOrNull()
                ?: throw SessionManagerError.MalformedSession("syncTime digits unparsable")
            return stamp - (localTime.toEpochMilli() / 1000.0)
        }
    }
}
