//
//  MiniPlayer.kt
//  app
//
//  Port of Amphora/UI/MiniPlayer.swift — the compact player card
//  floating above the tab bar while something plays: rounded surface, a
//  progress hairline along its top edge, artwork, title/artist, play
//  and skip. The full-player sheet is owned by MainTabView (one
//  instance for the whole shell), so tab switches never tear it down —
//  the card itself only reports taps through `onOpen`.
//
//  Port deltas (documented per AGENTS.md):
//  - The AirPlay route picker + the "playing on <device>" caption are
//    out of scope (AGENTS.md deprioritizes the output picker; system
//    output switching first). The caption is always the artist.
//  - SF Symbols map to Material glyphs (speaker = the radio tile's
//    fallback stays in RadioStationArtwork).
//

package dev.personal.amphora.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.support.rememberHaptics
import pandoracore.isRadio

@Composable
fun MiniPlayer(store: AmphoraStore, onOpen: () -> Unit) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    val track by store.player.currentTrack.collectAsState()
    val isPlaying by store.player.state.collectAsState()
    val elapsed by store.player.elapsed.collectAsState()
    val duration by store.player.duration.collectAsState()

    val current = track
    val playing = isPlaying == dev.personal.amphora.player.PlaybackState.playing ||
        isPlaying == dev.personal.amphora.player.PlaybackState.buffering

    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(Theme.radiusCard),
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                )
                .background(theme.surface, RoundedCornerShape(Theme.radiusCard)),
        ) {
            // The card's content: the open button, then the transport.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpen,
                        )
                        .semantics { contentDescription = "Open now playing" },
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A radio stream has no album art — it has a
                    // favicon, which is usually a 16×16 icon or missing
                    // entirely. The generated station tile covers both
                    // cases.
                    if (current?.isRadio == true) {
                        RadioStationArtwork(
                            stationName = current.title,
                            faviconURL = current.artworkUrl,
                            cornerRadius = Theme.radiusS + 2.dp,
                            minimumArtworkPixels = 64,
                            modifier = Modifier.size(44.dp),
                        )
                    } else {
                        CachedArtwork(
                            urlString = current?.artworkUrl,
                            cornerRadius = Theme.radiusS + 2.dp,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                    Column {
                        Text(
                            text = current?.title.orEmpty(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = labelColor(theme),
                            maxLines = 1,
                        )
                        Text(
                            text = current?.artist.orEmpty(),
                            fontSize = 11.sp,
                            color = secondaryColor(theme),
                            maxLines = 1,
                        )
                    }
                }
                MiniTransportButton(
                    circled = true,
                    label = if (playing) "Pause" else "Play",
                    onClick = {
                        haptics.light()
                        store.send(AmphoraStore.Intent.TogglePlayback)
                    },
                ) {
                    Crossfade(
                        targetState = playing,
                        animationSpec = tween(durationMillis = 200),
                        label = "mini-glyph",
                    ) { state ->
                        TransportGlyph(
                            kind = if (state) TransportGlyphKind.pause else TransportGlyphKind.play,
                            size = 17.dp,
                            color = theme.accentGlyph.copy(alpha = 0.85f),
                        )
                    }
                }
                MiniTransportButton(
                    circled = false,
                    // A radio stream has no next track — the button
                    // stops it instead.
                    label = if (current?.isRadio == true) "Stop radio" else "Next track",
                    onClick = {
                        haptics.medium()
                        if (current?.isRadio == true) {
                            store.send(AmphoraStore.Intent.StopRadio)
                        } else {
                            store.send(AmphoraStore.Intent.SkipToNext)
                        }
                    },
                ) {
                    TransportGlyph(
                        kind = if (current?.isRadio == true) {
                            TransportGlyphKind.stop
                        } else {
                            TransportGlyphKind.next
                        },
                        size = 17.dp,
                        color = labelColor(theme),
                    )
                }
            }
            // The progress hairline along the card's top edge (the port
            // of MiniPlayerProgress; the accent gradient from iOS).
            MiniPlayerProgress(
                elapsed = elapsed,
                duration = duration,
                accent = theme.accent,
                accentSecondary = theme.accentSecondary,
                progressTrack = theme.progressTrack,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * The card's top-edge progress hairline. Deliberately its own view: the
 * per-tick `elapsed` read re-renders only this 2.5 dp strip, never the
 * card's content (the iOS @Observable per-property tracking, kept by
 * isolating the flow reads here).
 */
@Composable
private fun MiniPlayerProgress(
    elapsed: Double,
    duration: Double,
    accent: Color,
    accentSecondary: Color,
    progressTrack: Color,
    modifier: Modifier = Modifier,
) {
    val fraction = if (duration > 0) {
        (elapsed / duration).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp),
    ) {
        // iOS clips the strip to the card's rounded shape; the two
        // rounded ends are drawn here instead (same effect, cheap).
        drawRoundRect(
            color = progressTrack,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(accent, accentSecondary)),
            size = androidx.compose.ui.geometry.Size(size.width * fraction.toFloat(), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
    }
}

/** One 44dp transport control around a glyph (the PressableButtonStyle
 *  port; the play variant carries the 34dp accent circle behind the
 *  glyph, as iOS does for both glyph states). */
@Composable
private fun MiniTransportButton(
    circled: Boolean,
    label: String,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    val theme = Theme.tokens()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) Pressable.pressedScale else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.55f,
            stiffness = Pressable.springStiffness,
        ),
        label = "mini-press-scale",
    )
    val opacity by animateFloatAsState(
        targetValue = if (isPressed) Pressable.pressedOpacity else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.55f,
            stiffness = Pressable.springStiffness,
        ),
        label = "mini-press-opacity",
    )

    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = opacity
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (circled) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(theme.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { glyph() }
        } else {
            glyph()
        }
    }
}
