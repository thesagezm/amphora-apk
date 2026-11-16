//
//  PlaybackService.kt
//  app
//
//  Port of Amphora/Player/PlayerService.swift + NowPlayingService.swift.
//
//  On iOS the two roles are separate classes: PlayerService owns the
//  AVQueuePlayer and the audio session; NowPlayingService publishes to
//  MPNowPlayingInfoCenter and wires MPRemoteCommandCenter. Media3 unifies
//  both roles in one MediaSessionService: the ExoPlayer is the engine,
//  the MediaSession publishes the lock-screen / system-UI media card and
//  the transport commands, and the default notification provider posts
//  the media notification when playback runs in the background.
//
//  Like the iOS service it performs **no networking**: every stream URL
//  arrives through AmphoraStore's PlaybackClient, which hands over
//  MediaItems (one per Track). The service lives as long as the system
//  needs it — audio keeps playing while the activity is gone.
//
//  Interruption handling (the iOS AVAudioSession interruption/route
//  observers) is the platform's job here: `setAudioAttributes(...,
//  handleAudioFocus = true)` pauses on focus loss (a call, another app)
//  and resumes after a transient loss; `setHandleAudioBecomingNoisy(true)`
//  pauses on headset unplug. Route changes need no player work at all —
//  ExoPlayer follows the output like AVPlayer does (documented in
//  PlayerService.handleRouteChange, whose "never replace the item here"
//  lesson applies verbatim).
//
//  Port delta (documented per AGENTS.md): iOS's resetQueue pause-then-
//  drop dance worked around AVQueuePlayer keeping the current item after
//  removeAllItems. ExoPlayer's clearMediaItems() drops the playing item
//  reliably, so a plain clear is the explicit hand-over.
//

package dev.personal.amphora.player

import android.content.Intent
import android.app.PendingIntent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.personal.amphora.MainActivity

/**
 * The playback engine — the single source of truth for playback state,
 * wrapped in a MediaSession for the lock screen, the system-UI media
 * card and the media notification.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            // Audio focus + interruption handling: gain focus when
            // playback starts, pause on transient loss and resume after,
            // pause on permanent loss (the port of the iOS interruption
            // observers).
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pause when the headphones are unplugged (the noisy-unplug
            // route change).
            .setHandleAudioBecomingNoisy(true)
            // Hold the CPU while streaming over the network even when
            // the app is backgrounded.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Buffer tuning for a fast, smooth start (the port of the
            // iOS item's preferredForwardBufferDuration = 30 s): keep up
            // to 60 s buffered, ~30 s target, start playback after ~1 s
            // is buffered (5 s after a rebuffer).
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 60_000,
                        /* bufferForPlaybackMs = */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                    )
                    .build(),
            )
            // The lock-screen / notification skip ±15 s buttons (iOS
            // wires MPRemoteCommandCenter.skip(Forward|Backward)Command
            // with preferredIntervals [15]).
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            // Tapping the media notification brings the app up.
            .setSessionActivity(getActivityPendingIntent())
            .build()

        // A named channel of our own — the default provider's generic
        // channel reads as system noise in Android's settings.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("playback")
                .setChannelName(dev.personal.amphora.R.string.notification_channel_playback)
                .build(),
        )
    }

    private fun getActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Task swiped away: keep the service alive while audio runs, stop it
     * when nothing plays (a dead service with a stale notification is
     * exactly what the iOS app never shows).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == androidx.media3.common.Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
