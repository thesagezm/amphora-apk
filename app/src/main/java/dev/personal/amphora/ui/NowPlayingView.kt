//
//  NowPlayingView.kt
//  app
//
//  Port of Amphora/UI/NowPlayingView.swift — the full-screen player,
//  Apple-Music-shaped: large rounded artwork, a left-aligned title
//  block with the stream quality beside it, a thin scrubber, and a
//  plain-glyph transport that answers every touch with a soft spring.
//  The ambient backdrop comes from the artwork palette
//  (ArtworkThemeController); every static surface is a matte palette
//  shade, never glass.
//
//  Arriving with their own units (kept out so nothing ships inert):
//  - Radio surfaces (LIVE badge, identify status, Identify Song menu
//    item, stop, the radio guard on the lyrics toolbar button): A7 —
//    the menu's radio branch guards the song-level items away there;
//    until A7 the menu always shows the song branch.
//  - The AirPlay route picker + route indicator: out of scope
//    (AGENTS.md deprioritizes the output picker).
//
//  Port deltas (documented per AGENTS.md):
//  - iOS wraps the player in a NavigationStack sheet; here MainTabView
//    hosts it in one ModalBottomSheet (the app's established sheet
//    pattern), Done dismisses.
//  - The artwork is a fixed flexible-width square instead of iOS's
//    height-flexible one (weight sizing doesn't exist inside a
//    scrollable Column); on phone portrait the layout is visually the
//    same and the column scrolls when content is too tall, exactly as
//    iOS's ScrollView does.
//  - AmbientGlow's blur ellipse becomes a radial-gradient ellipse —
//    Paint blur (RenderEffect) is API 31+ and this is a minSdk 26 app.
//

package dev.personal.amphora.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.HearingDisabled
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.store.ArtworkThemeController
import dev.personal.amphora.support.TimeFormat
import dev.personal.amphora.support.rememberHaptics
import pandoracore.Track

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun NowPlayingView(store: AmphoraStore, onDismiss: () -> Unit) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    val palette = ArtworkThemeController.colors
    val track by store.player.currentTrack.collectAsState()
    val state by store.player.state.collectAsState()
    val errorMessage by store.player.errorMessage.collectAsState()
    var isShowingActions by remember { mutableStateOf(false) }
    var isShowingTuning by remember { mutableStateOf(false) }
    var isShowingSleepTimer by remember { mutableStateOf(false) }
    var isShowingLyrics by remember { mutableStateOf(false) }
    var isShowingUpNext by remember { mutableStateOf(false) }
    // The volume while the slider is being dragged. The level is
    // applied live, but it is only written to disk when the drag ends.
    var draftVolume by remember { mutableStateOf<Float?>(null) }

    val playing = state == dev.personal.amphora.player.PlaybackState.playing ||
        state == dev.personal.amphora.player.PlaybackState.buffering

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Theme.playerBackground(palette)),
    ) {
        AmbientGlow(color = palette.accent.asColor())
        Column(modifier = Modifier.fillMaxSize()) {
            // The toolbar (iOS: leading ellipsis menu, inline
            // navigationTitle, trailing Done; lyrics + AirPlay arrive
            // later / are out of scope).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { isShowingActions = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = "More actions",
                            tint = labelColor(theme),
                        )
                    }
                    DropdownMenu(
                        expanded = isShowingActions,
                        onDismissRequest = { isShowingActions = false },
                        containerColor = theme.surface,
                    ) {
                        // Every secondary action in one place (iOS's
                        // actionsMenu). This is the app's only
                        // sleep-timer surface entry — one sleep control,
                        // no duplicate moons. The radio-specific items
                        // (Identify Song) arrive with A7; the song-level
                        // bookmark/hide items land with A5.3.
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (store.sleepTimer.isOff) {
                                        "Sleep Timer…"
                                    } else {
                                        "Sleep Timer (${store.sleepTimerLabel})…"
                                    },
                                    color = labelColor(theme),
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Bedtime,
                                    contentDescription = null,
                                    tint = labelColor(theme),
                                )
                            },
                            onClick = {
                                isShowingActions = false
                                isShowingSleepTimer = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Tune Station…", color = labelColor(theme)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Tune, contentDescription = null, tint = labelColor(theme))
                            },
                            onClick = {
                                isShowingActions = false
                                isShowingTuning = true
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Bookmark Song", color = labelColor(theme)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = labelColor(theme))
                            },
                            onClick = {
                                isShowingActions = false
                                haptics.light()
                                store.send(AmphoraStore.Intent.BookmarkCurrentSong)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Bookmark Artist", color = labelColor(theme)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = labelColor(theme))
                            },
                            onClick = {
                                isShowingActions = false
                                haptics.light()
                                store.send(AmphoraStore.Intent.BookmarkCurrentArtist)
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Hide This Song", color = labelColor(theme)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.HearingDisabled, contentDescription = null, tint = labelColor(theme))
                            },
                            onClick = {
                                isShowingActions = false
                                haptics.light()
                                store.send(AmphoraStore.Intent.SleepCurrentTrack)
                            },
                        )
                    }
                }
                Text(
                    text = store.currentStation?.name ?: "Now Playing",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = secondaryColor(theme),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                // The lyrics toggle (iOS: quote.bubble/quote.bubble.fill
                // — one Material glyph, tinted while shown; the radio
                // guard on the button arrives with A7).
                IconButton(
                    onClick = { isShowingLyrics = !isShowingLyrics },
                    modifier = Modifier.semantics {
                        contentDescription =
                            if (isShowingLyrics) "Hide lyrics" else "Show lyrics"
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FormatQuote,
                        contentDescription = null,
                        tint = if (isShowingLyrics) theme.accent else labelColor(theme),
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text("Done", color = theme.accentText)
                }
            }

            val current = track
            if (current == null) {
                NothingPlaying()
            } else if (isShowingLyrics) {
                // The lyrics panel scrolls itself; the transport stays
                // fixed (iOS: VStack { LyricsPanel; playerControls }).
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LyricsPanel(store = store)
                    }
                    PlayerControls(
                        store = store,
                        track = current,
                        playing = playing,
                        haptics = haptics,
                        isShowingUpNext = isShowingUpNext,
                        onToggleUpNext = { isShowingUpNext = !isShowingUpNext },
                        draftVolume = draftVolume,
                        onVolumeLive = { draftVolume = it },
                        onVolumeSettled = { live ->
                            store.send(AmphoraStore.Intent.SetPlaybackVolume(live))
                            draftVolume = null
                        },
                    )
                    Confirmation(store)
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage.orEmpty(),
                            fontSize = 13.sp,
                            color = Color(0xFFFFD60A),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
            } else {
                // One fixed stack, Apple-Music-shaped: the artwork and
                // the controls are children of the same layout; when
                // everything fits nothing scrolls (the iOS ScrollView
                // scrolls only when content overflows).
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Artwork(current)
                    TrackHeader(store, current)
                    Scrubber(store)
                    PlayerControls(
                        store = store,
                        track = current,
                        playing = playing,
                        haptics = haptics,
                        isShowingUpNext = isShowingUpNext,
                        onToggleUpNext = { isShowingUpNext = !isShowingUpNext },
                        draftVolume = draftVolume,
                        onVolumeLive = { draftVolume = it },
                        onVolumeSettled = { live ->
                            store.send(AmphoraStore.Intent.SetPlaybackVolume(live))
                            draftVolume = null
                        },
                    )
                    Confirmation(store)
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage.orEmpty(),
                            fontSize = 13.sp,
                            color = Color(0xFFFFD60A),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (isShowingTuning) {
        val theme = Theme.tokens()
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { isShowingTuning = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            ),
            containerColor = theme.background,
        ) {
            TuneStationSheet(store = store, station = null, onDismiss = { isShowingTuning = false })
        }
    }
    if (isShowingSleepTimer) {
        val theme = Theme.tokens()
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { isShowingSleepTimer = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            ),
            containerColor = theme.background,
        ) {
            SleepTimerSheet(store = store, onDismiss = { isShowingSleepTimer = false })
        }
    }
}

// MARK: Artwork + header

/** The flexible square: grows with the space left over and yields it
 *  again when the controls need room — the Apple-Music artwork. The
 *  port sizes it to the width (see the header's port deltas). */
@Composable
private fun Artwork(track: Track) {
    val palette = ArtworkThemeController.colors
    CachedArtwork(
        urlString = track.artworkUrl,
        cornerRadius = Theme.radiusArtwork,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .aspectRatio(1f)
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(Theme.radiusArtwork),
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Color.Black.copy(alpha = 0.45f),
            )
            .shadow(
                elevation = 40.dp,
                shape = RoundedCornerShape(Theme.radiusArtwork),
                ambientColor = palette.accent.asColor().copy(alpha = 0.18f),
                spotColor = palette.accent.asColor().copy(alpha = 0.18f),
            ),
    )
}

/**
 * Apple-Music-style block: left-aligned title, artist in the artwork
 * accent, album beneath, and the stream quality as a matte chip on the
 * trailing edge.
 */
@Composable
private fun TrackHeader(store: AmphoraStore, track: Track) {
    val palette = ArtworkThemeController.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = track.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
            )
            Text(
                text = track.artist,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = palette.accent.asColor().copy(alpha = 0.9f),
                maxLines = 1,
            )
            if (track.album.isNotEmpty()) {
                Text(
                    text = track.album,
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            // The station the track came from (AGENTS.md's Now Playing
            // list: artwork, title, artist, station, progress, skips).
            store.currentStation?.name?.let { stationName ->
                Text(
                    text = stationName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        contentDescription = "Playing on $stationName"
                    },
                )
            }
        }
        QualityChip(track)
    }
}

/** e.g. "128 kbps · MP3" — the variant the player actually streams
 *  (`Track.preferredVariant`, always the best the account offers). */
@Composable
private fun QualityChip(track: Track) {
    val palette = ArtworkThemeController.colors
    val variant = track.preferredVariant ?: return
    val encoding = when (variant.encoding.lowercase()) {
        "mp3" -> "MP3"
        "aacplus" -> "AAC+"
        "aac" -> "AAC"
        else -> variant.encoding.uppercase()
    }
    val quality = "${variant.bitrate} kbps · $encoding"
    Text(
        text = quality,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.accent.asColor(),
        modifier = Modifier
            .background(
                Theme.tokens().playerSurface,
                RoundedCornerShape(50),
            )
            .padding(horizontal = 9.dp, vertical = 5.dp)
            .semantics { contentDescription = "Audio quality $quality" },
    )
}

// MARK: Scrubber

/** The scrubber + the elapsed/duration labels (monospaced digits). */
@Composable
private fun Scrubber(store: AmphoraStore) {
    val palette = ArtworkThemeController.colors
    val elapsed by store.player.elapsed.collectAsState()
    val duration by store.player.duration.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlayerScrubber(
            elapsed = elapsed,
            duration = duration,
            accent = palette.accent.asColor(),
            onSeek = { store.player.seek(to = it) },
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = TimeFormat.string(elapsed),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = TimeFormat.string(duration),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

// MARK: Controls

/**
 * Everything below the artwork: ad badge, skip-limit badge, transport,
 * volume, feedback, up next. (The radio surfaces are A7.)
 */
@Composable
private fun PlayerControls(
    store: AmphoraStore,
    track: Track,
    playing: Boolean,
    haptics: dev.personal.amphora.support.Haptics,
    isShowingUpNext: Boolean,
    onToggleUpNext: () -> Unit,
    draftVolume: Float?,
    onVolumeLive: (Float) -> Unit,
    onVolumeSettled: (Float) -> Unit,
) {
    val skipLimitReached = store.isSkipLimitReached

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (track.isAdvertisement) {
            // pydora's player prints "Advertisement" — the slot is
            // labeled, unratable and unskippable by protocol.
            AdBadge()
        }
        if (skipLimitReached) {
            SkipLimitBadge(store)
        }
        Transport(
            store = store,
            track = track,
            playing = playing,
            haptics = haptics,
        )
        VolumeControl(
            store = store,
            draftVolume = draftVolume,
            onLive = onVolumeLive,
            onSettled = onVolumeSettled,
        )
        if (!track.isAdvertisement) {
            // A live radio stream is not a song — there is nothing to
            // rate (the synthetic `radio.<uuid>` token); that guard
            // arrives with A7's radio tracks.
            FeedbackBar(store = store, accent = ArtworkThemeController.colors.accent.asColor())
        }
        UpNext(
            store = store,
            isExpanded = isShowingUpNext,
            onToggle = onToggleUpNext,
        )
    }
}

@Composable
private fun AdBadge() {
    Text(
        text = "ADVERTISEMENT",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 3.sp,
        color = Color(0xFFFFD60A),
        modifier = Modifier
            .background(Color(0xFFFFD60A).copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Pandora's daily skip limit stopped the playlist for this station.
 *  The badge says why no more tracks are coming — the server refuses
 *  to hand them out until the limit resets. */
@Composable
private fun SkipLimitBadge(store: AmphoraStore) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "SKIP LIMIT",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
            color = Color(0xFFFFD60A),
            modifier = Modifier
                .background(Color(0xFFFFD60A).copy(alpha = 0.12f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            text = store.skipLimitMessage,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The transport: matched side glyphs around the main play/pause, which
 * sits in the accent-filled circle the mini player and the Home hero
 * already use.
 */
@Composable
private fun Transport(
    store: AmphoraStore,
    track: Track,
    playing: Boolean,
    haptics: dev.personal.amphora.support.Haptics,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressableIconButton(
            label = "Back 15 seconds",
            onClick = {
                haptics.light()
                store.player.skipBackward(by = 15.0)
            },
            modifier = Modifier.size(56.dp),
        ) {
            TransportGlyph(
                kind = TransportGlyphKind.back15,
                size = 27.dp,
                color = Color.White,
            )
        }
        Box(
            modifier = Modifier.size(width = 92.dp, height = 76.dp),
            contentAlignment = Alignment.Center,
        ) {
            PressableIconButton(
                label = if (playing) "Pause" else "Play",
                onClick = {
                    haptics.light()
                    store.send(AmphoraStore.Intent.TogglePlayback)
                },
            ) {
                Crossfade(
                    targetState = playing,
                    animationSpec = tween(durationMillis = 250),
                    label = "transport-glyph",
                ) { state ->
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .shadow(
                                elevation = 12.dp,
                                shape = CircleShape,
                                ambientColor = ArtworkThemeController.colors.accent
                                    .asColor().copy(alpha = 0.30f),
                                spotColor = ArtworkThemeController.colors.accent
                                    .asColor().copy(alpha = 0.30f),
                            )
                            .background(Theme.tokens().accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        TransportGlyph(
                            kind = if (state) TransportGlyphKind.pause else TransportGlyphKind.play,
                            size = 32.dp,
                            color = Theme.tokens().accentGlyph,
                        )
                    }
                }
            }
        }
        PressableIconButton(
            label = "Next track",
            onClick = {
                haptics.medium()
                store.send(AmphoraStore.Intent.SkipToNext)
            },
            modifier = Modifier.size(56.dp),
        ) {
            TransportGlyph(
                kind = TransportGlyphKind.next,
                size = 27.dp,
                color = Color.White,
            )
        }
    }
}

/**
 * The app's playback volume. `Player.volume` scales what Amphora
 * outputs — the phone's own system volume is untouched, so this slider
 * never fights the hardware buttons. The level moves live while the
 * finger is down and is written to disk once, when the drag ends —
 * saving on every frame would hammer the file system for nothing.
 */
@Composable
private fun VolumeControl(
    store: AmphoraStore,
    draftVolume: Float?,
    onLive: (Float) -> Unit,
    onSettled: (Float) -> Unit,
) {
    val palette = ArtworkThemeController.colors
    val volume by store.player.volume.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
        Slider(
            value = draftVolume ?: volume,
            onValueChange = { onLive(it) },
            onValueChangeFinished = {
                onSettled(draftVolume ?: volume)
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                activeTrackColor = palette.accent.asColor(),
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                thumbColor = Color.White,
            ),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Volume" },
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Pithos-style "Up Next": the next few tracks the queue will play.
 *  Collapsed to a one-line header (with the immediate next title);
 *  expanding reveals up to four rows. Hidden while nothing is queued. */
@Composable
private fun UpNext(
    store: AmphoraStore,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val upcoming by store.player.upcomingTracks.collectAsState()
    val shown = upcoming.take(4)
    if (shown.isEmpty()) return
    val palette = ArtworkThemeController.colors

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Up Next",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.accent.asColor(),
            )
            Text(
                text = shown.firstOrNull()?.title.orEmpty(),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.45f),
                maxLines = 1,
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                shown.forEach { upcomingTrack ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CachedArtwork(
                            urlString = upcomingTrack.artworkUrl,
                            cornerRadius = 6.dp,
                            modifier = Modifier.size(38.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = upcomingTrack.title,
                                fontSize = 15.sp,
                                color = Color.White,
                                maxLines = 1,
                            )
                            Text(
                                text = upcomingTrack.artist,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                maxLines = 1,
                            )
                        }
                        if (upcomingTrack.isAdvertisement) {
                            Text(
                                text = "AD",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD60A),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A short, self-dismissing confirmation ("Tuned to …"). */
@Composable
private fun Confirmation(store: AmphoraStore) {
    val message = store.confirmationMessage ?: return
    Text(
        text = message,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White,
        modifier = Modifier
            .background(Theme.tokens().playerSurface, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** iOS ContentUnavailableView — "Nothing playing". */
@Composable
private fun NothingPlaying() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Nothing playing",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Pick a station to start listening.",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A soft ellipse of the artwork accent above the player's gradient —
 * the "light in the room" effect. Static per track (no per-tick
 * state). Port delta: a radial-gradient falloff instead of a blur
 * (RenderEffect needs API 31+; this is a minSdk 26 app) — the same
 * soft ellipse (1.1 × width, 0.9 × width, raised 0.18 × height), drawn
 * on every supported API.
 */
@Composable
private fun AmbientGlow(color: Color) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val width = maxWidth
        val height = maxHeight
        Box(
            modifier = Modifier
                .width(width * 1.1f)
                .height(width * 0.9f)
                .offset(y = height * -0.18f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            color.copy(alpha = 0.20f),
                            Color.Transparent,
                        ),
                    ),
                )
                .align(Alignment.TopCenter),
        )
    }
}
