//
//  BrowseStationsSheet.kt
//  app
//
//  Port of Amphora/UI/BrowseStationsSheet.swift — browse Pandora's
//  featured stations ("Today's Hits", "Classic Rock", …) by category.
//  A genre station has no playlist, so tapping one creates a personal
//  station from its token and the sheet dismisses on success — the
//  same tick-dismiss contract as AddStationView. Catalog loading and
//  creation live in the store — this view only dispatches intents.
//
//  Port delta: the iOS NavigationStack drill-down (list → category) is
//  an internal `selectedCategory` state + system back handling — the
//  same two-level visual flow without a NavigationStack.
//

package dev.personal.amphora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import pandoracore.GenreCategory

@Composable
fun BrowseStationsSheet(store: AmphoraStore, onDismiss: () -> Unit) {
    val theme = Theme.tokens()

    // The drill-down (iOS NavigationLink → CategoryStationsView).
    var selectedCategory by remember { mutableStateOf<GenreCategory?>(null) }

    LaunchedEffect(Unit) {
        store.send(AmphoraStore.Intent.LoadGenreStations)
    }
    // The station landed — close the sheet (same signal the add-station
    // sheet uses).
    LaunchedEffect(store.stationCreatedTick) {
        if (store.stationCreatedTick > 0) onDismiss()
    }

    // System back pops the drill-down first.
    BackHandler(enabled = selectedCategory != null) { selectedCategory = null }

    Column(modifier = Modifier.fillMaxSize()) {
        // Inline title row (iOS nav bar): leading back-or-cancel,
        // centered title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val category = selectedCategory
            if (category != null) {
                IconButton(onClick = { selectedCategory = null }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = "Back",
                        tint = theme.accent,
                    )
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = theme.accent)
                }
            }
            Text(
                text = category?.name ?: "Browse Stations",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor(theme),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            // Balance the leading control so the title centers.
            Spacer(modifier = Modifier.size(64.dp))
        }

        val categories = store.genreCategories
        when {
            store.lastErrorMessage != null && categories == null -> ErrorState(store)
            categories != null -> {
                if (categories.isEmpty()) {
                    EmptyState()
                } else {
                    selectedCategory?.let { category ->
                        CategoryStationsList(store = store, category = category)
                    } ?: CategoryList(
                        categories = categories,
                        onOpen = { selectedCategory = it },
                    )
                }
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = secondaryColor(theme))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Loading stations…",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                    modifier = Modifier.semantics { contentDescription = "Loading stations" },
                )
            }
        }
    }
}

// MARK: Lists

/** The category list (iOS: NavigationLink rows); taps report up — the
 *  parent owns the drill-down. */
@Composable
private fun CategoryList(categories: List<GenreCategory>, onOpen: (GenreCategory) -> Unit) {
    val theme = Theme.tokens()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 4.dp, bottom = 24.dp,
        ),
    ) {
        items(categories, key = { it.name }) { category ->
            CategoryRow(category = category, onOpen = { onOpen(category) })
        }
    }
}

/**
 * One category: the 44dp accent tile, name, station count, chevron.
 * Tapping drills into the category (iOS NavigationLink).
 */
@Composable
private fun CategoryRow(category: GenreCategory, onOpen: () -> Unit) {
    val theme = Theme.tokens()
    RowPressableButton(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(theme.accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Radio,
                    contentDescription = null,
                    tint = theme.accentText,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = labelColor(theme),
                    maxLines = 1,
                )
                Text(
                    text = "${category.stations.size} stations",
                    fontSize = 12.sp,
                    color = secondaryColor(theme),
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = secondaryColor(theme).copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** One category's featured stations; a row tap creates a personal
 *  station from the genre station's token. */
@Composable
private fun CategoryStationsList(store: AmphoraStore, category: GenreCategory) {
    val theme = Theme.tokens()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 4.dp, bottom = 24.dp,
        ),
    ) {
        items(category.stations, key = { it.id }) { genre ->
            RowPressableButton(
                onClick = { store.send(AmphoraStore.Intent.CreateGenreStation(genre)) },
                enabled = !store.isMutatingStations,
                strong = true, // iOS: PressableButtonStyle on these rows
                modifier = Modifier
                    .fillMaxWidth()
                    .amphoraCardRow(theme),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CachedArtwork(
                        urlString = genre.artUrl,
                        cornerRadius = 12.dp,
                        fallbackSystemImage = Icons.Rounded.Radio,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = genre.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = labelColor(theme),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (store.isMutatingStations) {
                        CircularProgressIndicator(
                            color = secondaryColor(theme),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.AddCircle,
                            contentDescription = null,
                            tint = theme.accentText,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

// MARK: States

/** iOS: `ContentUnavailableView` "Couldn't load stations" + Retry. */
@Composable
private fun ErrorState(store: AmphoraStore) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.WifiOff,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Couldn't load stations",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor(theme),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = store.lastErrorMessage.orEmpty(),
            fontSize = 13.sp,
            color = secondaryColor(theme),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { store.send(AmphoraStore.Intent.LoadGenreStations) },
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.accent,
                contentColor = theme.accentGlyph,
            ),
        ) {
            Text("Retry")
        }
    }
}

/** iOS: `ContentUnavailableView` "No featured stations". */
@Composable
private fun EmptyState() {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Radio,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "No featured stations",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor(theme),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Pandora returned no station catalog.",
            fontSize = 13.sp,
            color = secondaryColor(theme),
            textAlign = TextAlign.Center,
        )
    }
}
