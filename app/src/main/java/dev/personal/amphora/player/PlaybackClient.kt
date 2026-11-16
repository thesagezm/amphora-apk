//
//  PlaybackClient.kt
//  app
//
//  The app-side half of the PlayerService port. On iOS AppStore owns
//  PlayerService in-process and calls it directly; here the engine lives
//  in PlaybackService (a MediaSessionService, so audio + the media
//  notification survive backgrounding) and the store talks to it through
//  a MediaController. This class wraps that controller behind the exact
//  PlayerService API surface — same method names, same callbacks, same
//  ordering contracts — and republishes its state as StateFlows (the
//  port of @Observable).
//
//  Queueing mirrors pithos' `start_song` exactly as the iOS service does:
//  the queue holds the current track plus the next one, so ExoPlayer
//  buffers the next stream while the current one plays (near-gapless
//  transition). When nothing remains behind the current track,
//  `onNeedMoreTracks` asks the store for the next playlist — pithos
//  preloads when one song remains.
//
//  Callback ordering contract (kept from iOS): `onTrackFinished` fires
//  BEFORE `onTrackChanged` for the replacement track, so "started at"
//  timestamps stay aligned for the scrobbler (A8).
//
//  Port deltas (documented per AGENTS.md):
//  - Commands are fire-and-forget (internally sequenced on the main
//    dispatcher) — MediaController calls are async IPC, unlike iOS's
//    direct in-process calls. Ordering is preserved: one dispatcher.
//  - iOS's pause-then-drop resetQueue dance worked around AVQueuePlayer
//    keeping the current item after removeAllItems; ExoPlayer's
//    clearMediaItems() drops the playing item reliably (and fires the
//    item-null transition, which is where the outgoing track is
//    reported).
//  - The interruption/route observers are the platform's job
//    (audio-focus handling in PlaybackService), so only the observable
//    outcomes flow through here.
//

package dev.personal.amphora.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import dev.personal.amphora.support.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pandoracore.Track

/** Coarse playback state for the UI — the port of PlayerService.PlaybackState. */
enum class PlaybackState {
    idle,
    playing,
    paused,
    buffering,
    failed,
}

class PlaybackClient(private val context: Context) {

    // MARK: Observable state (StateFlow ports of the @Observable properties)

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack

    private val _state = MutableStateFlow(PlaybackState.idle)
    val state: StateFlow<PlaybackState> = _state

    /** Playback position in seconds. */
    private val _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed

    /** Track length in seconds (from Pandora's `trackLength`). */
    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /**
     * Tracks queued behind the current one, most immediate first — the
     * "Up Next" list (the port of PlayerService.upcomingTracks).
     */
    private val _upcomingTracks = MutableStateFlow<List<Track>>(emptyList())
    val upcomingTracks: StateFlow<List<Track>> = _upcomingTracks

    /**
     * The app's playback volume (0...1). `Player.volume` scales what
     * this app outputs — it does **not** change the phone's system
     * volume, so the player can offer its own level without fighting
     * the hardware buttons.
     */
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume

    val isPlaying: Boolean
        get() = _state.value == PlaybackState.playing || _state.value == PlaybackState.buffering

    /**
     * How far the audible audio lags a live stream's edge, in seconds.
     *
     * The radio identification probe reads the station's metadata at
     * the stream's live edge; the player deliberately buffers behind
     * that edge (the 30 s forward-buffer tuning), so an announcement
     * describes a song the listener hears seconds later. The gap is
     * the buffered-ahead window: the buffered range's end (the download
     * edge) minus the playback position. 0 when the measurement is not
     * meaningful (nothing loaded yet, a finite track, a stalled read) —
     * callers treat 0 as "apply now".
     *
     * Port delta: iOS reads `loadedTimeRanges.first` — ExoPlayer's
     * equivalent window is `bufferedPosition`.
     */
    val liveStreamLag: Double
        get() {
            val controller = connected ?: return 0.0
            val bufferedMs = controller.bufferedPosition
            val positionMs = controller.currentPosition
            if (bufferedMs == C.TIME_UNSET || positionMs == C.TIME_UNSET) return 0.0
            val lag = (bufferedMs - positionMs) / 1000.0
            if (!lag.isFinite() || lag <= 0.0) return 0.0
            return minOf(lag, 45.0)
        }

    // MARK: Callbacks (set by the owner — the observer-list ports)

    /**
     * Fired when the current track changed (start, skip, natural end).
     * A list of observers: the app store needs the hook for the
     * per-track state (rating, history, lyrics, ads) and the UI reads
     * the StateFlow — neither may overwrite the other.
     */
    val onTrackChanged = mutableListOf<(Track?) -> Unit>()

    /**
     * Fired when a track stops being the current one — natural end, user
     * skip, station switch or stop — with how far into it playback
     * reached. The scrobbler's policy input (A8). Fires *before*
     * `onTrackChanged` for the replacement track.
     */
    val onTrackFinished = mutableListOf<(Track, Double) -> Unit>()

    /** Fired when the current track played to its end. */
    val onTrackEnded = mutableListOf<() -> Unit>()

    /**
     * Fired when the queue holds nothing behind the current track —
     * fetch another playlist and hand it back via [enqueue].
     */
    var onNeedMoreTracks: (() -> Unit)? = null

    /** Fired when playback of the current track failed. */
    var onPlaybackFailed: ((String) -> Unit)? = null

    // MARK: Private state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controllerDeferred =
        kotlinx.coroutines.CompletableDeferred<MediaController>()

    /** The connected controller once the async connection lands. */
    private var connected: MediaController? = null

    /** Every track handed to the player this session, by trackToken —
     *  the MediaItem only carries the token, so this resolves it back. */
    private val tracksByToken = mutableMapOf<String, Track>()

    private var connectionAttempts = 0

    init {
        connect()
        // The 0.5 s time observer (iOS addPeriodicTimeObserver): updates
        // `elapsed` while audio runs; emits only on change, so a paused
        // player costs nothing.
        scope.launch {
            while (isActive) {
                delay(500)
                val controller = connected ?: continue
                val seconds = controller.currentPosition / 1000.0
                val clamped = if (seconds.isFinite()) seconds.coerceAtLeast(0.0) else 0.0
                if (_elapsed.value != clamped) {
                    _elapsed.value = clamped
                }
            }
        }
    }

    /**
     * Connects the MediaController (which starts PlaybackService on
     * demand). A transient failure retries a few times; after that the
     * error surfaces through [fail] — never a crash (AGENTS.md).
     */
    private fun connect() {
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                try {
                    val controller = future.get()
                    connected = controller
                    controller.addListener(playerListener)
                    publishState()
                    requestMoreTracksIfNeeded()
                } catch (error: Exception) {
                    AppLog.error(
                        "media controller connect failed: $error",
                        AppLog.Category.playback,
                    )
                    if (++connectionAttempts <= 3) {
                        connect()
                    } else {
                        controllerDeferred.completeExceptionally(error)
                        fail("Couldn't start audio output.")
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** Runs a command once the controller is connected, in issue order. */
    private fun withController(block: (MediaController) -> Unit) {
        scope.launch {
            val controller = try {
                controllerDeferred.await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.error("playback unavailable: $error", AppLog.Category.playback)
                fail("Couldn't start audio output.")
                return@launch
            }
            block(controller)
        }
    }

    // MARK: - Public API (the PlayerService surface)

    /** Starts a track, queueing the next one for a gapless hand-over. */
    fun play(track: Track, prefetching: Track?) = withController { controller ->
        // Dropping the playlist fires the item-null transition, which is
        // where the outgoing track is reported (finishOutgoingTrack).
        controller.clearMediaItems()
        controller.stop()
        val item = mediaItem(track)
        if (item == null) {
            // The queue is empty now, so the old track is no longer
            // playing — say so, or the player would keep showing the
            // track that was replaced.
            _errorMessage.value = null
            _currentTrack.value = null
            onTrackChanged.forEach { it(null) }
            fail("That track isn't playable right now.")
            return@withController
        }
        tracksByToken[track.trackToken] = track
        val items = mutableListOf(item)
        if (prefetching != null) {
            mediaItem(prefetching)?.let { next ->
                tracksByToken[next.mediaId] = prefetching
                items.add(next)
            }
        }
        _errorMessage.value = null
        controller.addMediaItems(items)
        controller.prepare()
        controller.play()
        _elapsed.value = 0.0
        _duration.value = track.duration.toDouble()
        // currentTrack / state / needMore arrive via the session's
        // transition and timeline events.
    }

    /**
     * Starts a live radio stream: one slot, nothing behind it — there
     * is no "next" to prefetch, and the track's duration is 0 (the
     * scrubber reads it as live and disables itself).
     *
     * Port delta: iOS's resetQueue never reports the outgoing track as
     * nil here; the port keeps the A4 contract instead — the
     * clearMediaItems fires the item-null transition (the outgoing
     * track is reported), and the single radio item's own transition
     * adopts the stream as the current track.
     */
    fun playLiveStream(track: Track) = withController { controller ->
        // Dropping the playlist fires the item-null transition, which is
        // where the outgoing track is reported (finishOutgoingTrack).
        controller.clearMediaItems()
        controller.stop()
        val item = mediaItem(track)
        if (item == null) {
            // The queue was emptied for the switch, so nothing is
            // playing any more — the current track must not keep
            // pointing at the track that was replaced.
            _errorMessage.value = null
            _currentTrack.value = null
            onTrackChanged.forEach { it(null) }
            fail("That stream isn't playable right now.")
            return@withController
        }
        tracksByToken[track.trackToken] = track
        _errorMessage.value = null
        controller.addMediaItems(listOf(item))
        controller.prepare()
        controller.play()
        _elapsed.value = 0.0
        _duration.value = 0.0
        // currentTrack / state arrive via the session's transition
        // events (the `play` contract).
    }

    /**
     * Appends tracks to the queue (a freshly fetched playlist). Items
     * are buffered by ExoPlayer while the current track plays.
     */
    fun enqueue(tracks: List<Track>) = withController { controller ->
        if (tracks.isEmpty()) return@withController
        val items = mutableListOf<MediaItem>()
        for (track in tracks) {
            // No playable URL — skipped, exactly like the iOS
            // `guard let item else { continue }`.
            val item = mediaItem(track) ?: continue
            tracksByToken[track.trackToken] = track
            items.add(item)
        }
        if (items.isEmpty()) return@withController
        if (controller.mediaItemCount == 0) {
            // The queue was empty — this track starts right away (the
            // port of enqueue's slots.count == 1 branch).
            _errorMessage.value = null
            controller.addMediaItems(items)
            controller.prepare()
            controller.play()
        } else {
            controller.addMediaItems(items)
        }
    }

    /**
     * Skip to the next track (user action). Skipping the last track
     * empties the queue, like the iOS advanceToNextItem does — ExoPlayer
     * would no-op, so the empty case clears explicitly (the resulting
     * need-more callback refills it via the store).
     */
    fun skipToNext() = withController { controller ->
        if (controller.mediaItemCount == 0) return@withController
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        } else {
            controller.clearMediaItems()
            controller.stop()
        }
    }

    fun pause() = withController { controller ->
        controller.pause()
    }

    fun resume() = withController { controller ->
        if (controller.mediaItemCount == 0) return@withController
        controller.play()
    }

    /** iOS togglePlayback: switch on the app's own state, not the
     *  player's internals. */
    fun togglePlayback() {
        when (_state.value) {
            PlaybackState.playing, PlaybackState.buffering -> pause()
            PlaybackState.paused, PlaybackState.failed -> resume()
            PlaybackState.idle -> Unit
        }
    }

    /** Sets the app's playback volume, clamped to 0...1. */
    fun setVolume(value: Float) = withController { controller ->
        val clamped = value.coerceIn(0f, 1f)
        controller.volume = clamped
        _volume.value = clamped
    }

    /** Jumps forward in the current track (remote command). */
    fun skipForward(by: Double) = withController { controller ->
        if (controller.mediaItemCount == 0) return@withController
        seekInternal(controller, _elapsed.value + by)
    }

    /** Jumps backward in the current track (remote command). */
    fun skipBackward(by: Double) = withController { controller ->
        if (controller.mediaItemCount == 0) return@withController
        seekInternal(controller, _elapsed.value - by)
    }

    /** Seeks within the current track. */
    fun seek(to: Double) = withController { controller ->
        if (controller.mediaItemCount == 0) return@withController
        seekInternal(controller, to)
    }

    /** Stops playback and clears the queue (sign-out, station switch). */
    fun stop() = withController { controller ->
        controller.clearMediaItems()
        controller.stop()
        // currentTrack / elapsed / duration / state flow through the
        // item-null transition and the IDLE event.
    }

    fun clearError() {
        _errorMessage.value = null
        publishState()
    }

    // MARK: - Internals

    private fun seekInternal(controller: MediaController, targetSeconds: Double) {
        val target = targetSeconds.coerceIn(0.0, maxDurationSeconds(controller))
        val targetMs = (target * 1000).toLong()
        controller.seekTo(targetMs)
        _elapsed.value = targetMs / 1000.0
    }

    /** Track length in seconds; falls back to the player item's duration
     *  when Pandora did not send one (the port of maxDuration). */
    private fun maxDurationSeconds(controller: MediaController): Double {
        val trackDuration = _duration.value
        if (trackDuration > 0) return trackDuration
        val itemDurationMs = controller.duration
        return if (itemDurationMs != C.TIME_UNSET && itemDurationMs > 0) {
            itemDurationMs / 1000.0
        } else {
            0.0
        }
    }

    /** Builds a MediaItem for a track: the token rides in `mediaId`, the
     *  lock-screen metadata in MediaMetadata (title, artist, album, and
     *  the artwork URI the media notification loads on its own). */
    private fun mediaItem(track: Track): MediaItem? {
        val url = track.streamURL ?: return null
        return MediaItem.Builder()
            .setMediaId(track.trackToken)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.artworkUrl?.let(Uri::parse))
                    .build(),
            )
            .build()
    }

    /** Reports the outgoing track and the reached position to the
     *  onTrackFinished observers, before any queue or current-track
     *  change (the scrobble moment's policy input, A8). */
    private fun finishOutgoingTrack() {
        val outgoing = _currentTrack.value ?: return
        for (observer in onTrackFinished) {
            observer(outgoing, _elapsed.value)
        }
    }

    /** pithos preloads the next playlist when one song remains — ask the
     *  owner for more tracks at that point. "One song remains" = nothing
     *  behind the current track (the port of slots.count <= 1). */
    private fun requestMoreTracksIfNeeded() {
        val controller = connected ?: return
        val count = controller.mediaItemCount
        val remaining = if (count == 0) 0 else count - controller.currentMediaItemIndex - 1
        if (remaining <= 0) {
            onNeedMoreTracks?.invoke()
        }
    }

    /** Republishes the Up Next list from the queue behind the current
     *  item (the port of slots.dropFirst().map(\.track)). */
    private fun publishUpcoming() {
        val controller = connected
        val tracks = if (controller == null) {
            emptyList()
        } else {
            val current = controller.currentMediaItemIndex
            ((current + 1) until controller.mediaItemCount).mapNotNull { index ->
                tracksByToken[controller.getMediaItemAt(index).mediaId]
            }
        }
        if (_upcomingTracks.value != tracks) {
            _upcomingTracks.value = tracks
        }
    }

    /** Adopts a new current track (or none): resets position and length,
     *  notifies the onTrackChanged observers, republishes the state. */
    private fun adoptCurrent(track: Track?) {
        _currentTrack.value = track
        _elapsed.value = 0.0
        _duration.value = track?.duration?.toDouble() ?: 0.0
        for (observer in onTrackChanged) {
            observer(track)
        }
        publishState()
    }

    private fun fail(message: String) {
        AppLog.error("playback failed: $message", AppLog.Category.playback)
        _errorMessage.value = message
        publishState()
        onPlaybackFailed?.invoke(message)
    }

    /** The state machine — the port of syncStateWithPlayer. A `failed`
     *  state rides on the error message (sticky until cleared or a new
     *  track starts), not on the player's internal state. */
    private fun publishState() {
        val message = _errorMessage.value
        val track = _currentTrack.value
        val controller = connected
        val newState = when {
            track == null && message != null -> PlaybackState.failed
            controller == null -> PlaybackState.idle
            controller.playbackState == Player.STATE_IDLE && message != null -> PlaybackState.failed
            track == null -> PlaybackState.idle
            controller.isPlaying -> PlaybackState.playing
            controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady ->
                PlaybackState.buffering
            else -> PlaybackState.paused
        }
        val old = _state.value
        if (old != newState) {
            AppLog.info(
                "playback state: $old → $newState",
                AppLog.Category.playback,
            )
            _state.value = newState
        }
    }

    /** The queue ran to its end with nothing behind the current track —
     *  the port of handleItemEnded for the last item (AVPlayerItemDidPlay
     *  ToEndTime with no successor). */
    private fun handleQueueEnded() {
        finishOutgoingTrack()
        for (observer in onTrackEnded) {
            observer()
        }
        connected?.clearMediaItems()
        adoptCurrent(null)
        requestMoreTracksIfNeeded()
    }

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newToken = mediaItem?.mediaId
            val newTrack = newToken?.let { tracksByToken[newToken] }
            val current = _currentTrack.value
            if (newTrack == current) {
                // Nothing actually changed (batched playlist events).
                publishState()
                requestMoreTracksIfNeeded()
                return
            }
            // Contract: the outgoing track is reported first.
            finishOutgoingTrack()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // The previous item played to its end and the queue
                // advanced on its own — the natural-end signal (the port
                // of handleItemEnded), before the track-changed notify.
                for (observer in onTrackEnded) {
                    observer()
                }
            }
            adoptCurrent(newTrack)
            publishUpcoming()
            requestMoreTracksIfNeeded()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // Enqueues, clears and removals land here; the transition
            // handler covers current-track changes. This is where the
            // need-more contract stays current after an append.
            publishUpcoming()
            requestMoreTracksIfNeeded()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // The last item finished with no successor: the port of
                // the end-of-queue branch of handleItemEnded.
                if (_currentTrack.value != null) {
                    handleQueueEnded()
                }
                return
            }
            if (playbackState == Player.STATE_IDLE &&
                connected?.mediaItemCount == 0 &&
                _currentTrack.value != null
            ) {
                // Safety net for a clear that produced no transition
                // event: the outgoing track must still be reported and
                // the current track must not stay stale.
                finishOutgoingTrack()
                adoptCurrent(null)
                publishUpcoming()
            }
            publishState()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishState()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppLog.error(
                "player item failed: ${error.errorCodeName}",
                AppLog.Category.playback,
            )
            fail("Playback failed. Tap next to skip this track.")
        }
    }
}
