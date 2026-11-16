//
//  FeedbackBar.kt
//  app
//
//  Port of Amphora/UI/FeedbackBar.swift — the rating row on the Now
//  Playing screen — Apple-Music-plain: two large glyphs, no filled
//  circles. The active rating turns the accent; tapping it removes it
//  again (pithos' `Song.rate` toggles love/ban off). The iOS app has no
//  "why" reason UI (the legacy v5 API carries no reason parameter —
//  see pandoracore Feedback.kt), so the port has none either.
//
//  iOS's former third button (hide-song, the "two sleep timers"
//  confusion) lives in the player's ellipsis menu — those menu items
//  land with the A5 menu unit, not here.
//
//  Port delta: SF Symbols' hand.thumbsup / hand.thumbsup.fill pair is
//  Material's ThumbUpOffAlt / ThumbUp pair (outlined when idle, filled
//  when active); `.contentTransition(.symbolEffect(.replace))` becomes
//  a Crossfade.
//

package dev.personal.amphora.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.support.rememberHaptics
import pandoracore.FeedbackRating

/// The rating row: two 52dp glyph buttons, 64 apart — the iOS spacing.
@Composable
fun FeedbackBar(store: AmphoraStore, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatingButton(store, FeedbackRating.loved, "Thumbs up", accent)
        RatingButton(store, FeedbackRating.banned, "Thumbs down", accent)
    }
}

@Composable
private fun RatingButton(
    store: AmphoraStore,
    rating: FeedbackRating,
    label: String,
    accent: Color,
) {
    val haptics = rememberHaptics()
    val isActive = store.currentRating == rating
    // The player is dark-first like iOS: inactive glyphs are the label
    // color (white on this surface), the active one is the accent.
    PressableIconButton(
        label = label,
        onClick = {
            haptics.light()
            if (isActive) {
                store.send(AmphoraStore.Intent.RemoveCurrentRating)
            } else {
                store.send(AmphoraStore.Intent.RateCurrentTrack(rating))
            }
        },
        modifier = Modifier.size(52.dp),
        stateDescription = if (isActive) "On" else "Off",
    ) {
        Crossfade(
            targetState = isActive,
            animationSpec = tween(durationMillis = 250),
            label = "rating-glyph",
        ) { active ->
            Icon(
                imageVector = when (rating) {
                    FeedbackRating.loved ->
                        if (active) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt
                    else ->
                        if (active) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt
                },
                contentDescription = null,
                tint = if (active) accent else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
