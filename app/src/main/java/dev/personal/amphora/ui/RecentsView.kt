//
//  RecentsView.kt
//  app
//
//  Port of Amphora/UI/RecentsView.swift — recents + bookmarks, opened
//  from the Home tab's toolbar button (user request 2026-11-07 — the
//  tab itself is gone). The two share one screen behind a soft
//  segmented pill.
//
//  Port deltas (per the app's established sheet pattern):
//  - iOS wraps the sheet in a NavigationStack; the Android sheet
//    carries the inline title row + trailing Done itself.
//  - `Text(entry.playedAt, style: .relative)` becomes
//    `DateUtils.getRelativeTimeSpanString` (same "2 hours ago" shape).
//  - `.contentTransition(.symbolEffect(.replace))` becomes a
//    Crossfade; SF hand.thumbsup(.fill) becomes Material
//    ThumbUpOffAlt/ThumbUp — the FeedbackBar pair, smaller.
//  - SF music.mic → Material Mic.
//

package dev.personal.amphora.ui

import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.support.rememberHaptics
import java.time.Instant
import pandoracore.Bookmark
import pandoracore.FeedbackRating
import pandoracore.PlayHistory
import pandoracore.Station

private enum class RecentsSegment(val label: String) {
    history("Recents"),
    bookmarks("Bookmarks"),
}

@Composable
fun RecentsView(store: AmphoraStore, onDismiss: () -> Unit) {
    val theme = Theme.tokens()
    var segment by remember { mutableStateOf(RecentsSegment.history) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Inline title row (iOS nav bar): centered title, trailing Done.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.size(64.dp))
            Text(
                text = "Recents",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor(theme),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onDismiss) {
                Text("Done", color = theme.accent)
            }
        }

        SegmentPicker(
            segment = segment,
            onSelect = { segment = it },
        )
        when (segment) {
            RecentsSegment.history -> HistoryList(store)
            RecentsSegment.bookmarks -> BookmarksList(store)
        }
    }
}

/**
 * The theme's own segmented control: a soft surface track, the
 * selected pill lifting via a lighter fill + the accent — no hard
 * borders. (iOS animates the pill move with `.snappy(0.25)`; the two
 * equal-width segments animate their fills here — visually the same
 * lift, no layout math.)
 */
@Composable
private fun SegmentPicker(segment: RecentsSegment, onSelect: (RecentsSegment) -> Unit) {
    val theme = Theme.tokens()
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(theme.surface)
            .padding(4.dp)
            .semantics { contentDescription = "Show recents or bookmarks" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RecentsSegment.entries.forEach { item ->
            val isSelected = segment == item
            val fill by animateColorAsState(
                targetValue = if (isSelected) theme.surfaceHighlight else Color.Transparent,
                animationSpec = tween(durationMillis = 250),
                label = "segment-pill",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(fill)
                    .clickable { onSelect(item) }
                    .padding(vertical = 8.dp)
                    .semantics { selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) theme.accentText else secondaryColor(theme),
                )
            }
        }
    }
}

// MARK: Recents

@Composable
private fun HistoryList(store: AmphoraStore) {
    val history = store.history
    if (history.isEmpty) {
        ContentUnavailable(
            title = "Nothing played yet",
            icon = Icons.Rounded.Schedule,
            description = "Tracks you listen to will show up here.",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
        ),
    ) {
        items(history.items, key = { it.trackToken }) { entry ->
            // The station a remembered track was played from — the
            // rating of a past track belongs to that station. Null when
            // the station is no longer in the user's list (deleted or
            // transformed), in which case the row's rating buttons are
            // disabled.
            val station = entry.stationToken?.let { token ->
                store.stations.firstOrNull { it.token == token }
            }
            HistoryRow(
                entry = entry,
                station = station,
                isRating = entry.trackToken in store.ratingTrackTokens,
                onRate = { rating ->
                    station?.let {
                        store.send(AmphoraStore.Intent.RateHistoryTrack(rating, entry, it))
                    }
                },
                onRemoveRating = {
                    station?.let {
                        store.send(AmphoraStore.Intent.RemoveHistoryTrackRating(entry, it))
                    }
                },
            )
        }
    }
}

/** One remembered play: artwork, title/artist, when it played, and the
 *  rating controls. (Rating a song that has already played — the
 *  rating belongs to the song, so it can be given long after the track
 *  ended. The buttons mirror the player's FeedbackBar: the active
 *  rating fills, tapping it again removes the rating.) */
@Composable
private fun HistoryRow(
    entry: PlayHistory.Entry,
    station: Station?,
    isRating: Boolean,
    onRate: (FeedbackRating) -> Unit,
    onRemoveRating: () -> Unit,
) {
    val theme = Theme.tokens()
    val rating = entry.rating ?: FeedbackRating.none

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme)
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CachedArtwork(
            urlString = entry.artworkUrl,
            cornerRadius = Theme.radiusS,
            modifier = Modifier.size(44.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = labelColor(theme),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.artist,
                fontSize = 12.sp,
                color = secondaryColor(theme),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The two rating glyphs, plus when the track played.
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                HistoryRatingButton(
                    value = FeedbackRating.loved,
                    rating = rating,
                    isRating = isRating,
                    hasStation = station != null,
                    onToggle = { if (rating == FeedbackRating.loved) onRemoveRating() else onRate(FeedbackRating.loved) },
                )
                HistoryRatingButton(
                    value = FeedbackRating.banned,
                    rating = rating,
                    isRating = isRating,
                    hasStation = station != null,
                    onToggle = { if (rating == FeedbackRating.banned) onRemoveRating() else onRate(FeedbackRating.banned) },
                )
            }
            Text(
                text = relativeTime(entry.playedAt),
                fontSize = 11.sp,
                color = secondaryColor(theme).copy(alpha = 0.7f),
                modifier = Modifier.width(48.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun HistoryRatingButton(
    value: FeedbackRating,
    rating: FeedbackRating,
    isRating: Boolean,
    hasStation: Boolean,
    onToggle: () -> Unit,
) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    val isActive = rating == value
    PressableIconButton(
        label = if (value == FeedbackRating.loved) "Thumbs up" else "Thumbs down",
        onClick = {
            haptics.light()
            onToggle()
        },
        modifier = Modifier
            .size(width = 30.dp, height = 24.dp)
            .alpha(if (hasStation) 1f else 0.4f),
        enabled = !isRating && hasStation,
        stateDescription = if (isActive) "On" else "Off",
    ) {
        Crossfade(
            targetState = isActive,
            animationSpec = tween(durationMillis = 250),
            label = "history-rating-glyph",
        ) { active ->
            Icon(
                imageVector = when (value) {
                    FeedbackRating.loved ->
                        if (active) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt
                    else ->
                        if (active) Icons.Rounded.ThumbDown else Icons.Rounded.ThumbDownOffAlt
                },
                contentDescription = null,
                tint = if (active) theme.accentText else secondaryColor(theme),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** iOS `Text(entry.playedAt, style: .relative)` — "2 hours ago". */
private fun relativeTime(playedAt: Instant): String =
    DateUtils.getRelativeTimeSpanString(
        playedAt.toEpochMilli(),
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

// MARK: Bookmarks

/** The bookmarks half of the Recents screen — the user's saved songs
 *  and artists (`user.getBookmarks`), with delete. */
@Composable
private fun BookmarksList(store: AmphoraStore) {
    val theme = Theme.tokens()

    // iOS `.task { await store.refreshBookmarks() }` — refresh on open.
    LaunchedEffect(Unit) {
        store.refreshBookmarks()
    }

    when {
        store.isLoadingBookmarks && store.bookmarks.isEmpty() -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = secondaryColor(theme))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Loading bookmarks…",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                )
            }
        }
        store.bookmarks.isEmpty() -> {
            ContentUnavailable(
                title = "No bookmarks yet",
                icon = Icons.Rounded.Bookmark,
                description = "Bookmark a song or artist from the player.",
            )
        }
        else -> {
            val songs = store.bookmarks.filter { it.kind == Bookmark.Kind.song }
            val artists = store.bookmarks.filter { it.kind == Bookmark.Kind.artist }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
                ),
            ) {
                if (songs.isNotEmpty()) {
                    item(key = "songs-header") { SectionHeader("Songs") }
                    items(songs, key = { it.bookmarkToken }) { bookmark ->
                        BookmarkRow(store, bookmark)
                    }
                }
                if (artists.isNotEmpty()) {
                    item(key = "artists-header") { SectionHeader("Artists") }
                    items(artists, key = { it.bookmarkToken }) { bookmark ->
                        BookmarkRow(store, bookmark)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    val theme = Theme.tokens()
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = secondaryColor(theme),
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun BookmarkRow(store: AmphoraStore, bookmark: Bookmark) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme)
            .heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CachedArtwork(
            urlString = bookmark.artworkUrl,
            cornerRadius = Theme.radiusS,
            fallbackSystemImage = if (bookmark.kind == Bookmark.Kind.song) {
                Icons.Rounded.MusicNote
            } else {
                Icons.Rounded.Mic
            },
            modifier = Modifier.size(44.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = bookmark.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = labelColor(theme),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (bookmark.kind == Bookmark.Kind.song) {
                Text(
                    text = bookmark.artistName,
                    fontSize = 12.sp,
                    color = secondaryColor(theme),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PressableIconButton(
            label = "Remove bookmark ${bookmark.title}",
            onClick = {
                haptics.light()
                store.send(AmphoraStore.Intent.DeleteBookmark(bookmark))
            },
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                tint = Color(0xFFFF5A5A),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// MARK: Shared

/** iOS `ContentUnavailableView` — icon, title, one-line description. */
@Composable
private fun ContentUnavailable(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor(theme),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = description,
            fontSize = 13.sp,
            color = secondaryColor(theme),
            textAlign = TextAlign.Center,
        )
    }
}
