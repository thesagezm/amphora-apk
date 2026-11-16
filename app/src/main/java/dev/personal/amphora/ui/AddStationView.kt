//
//  AddStationView.kt
//  app
//
//  Port of Amphora/UI/AddStationView.swift — the add-station sheet:
//  search field → results → tap a row to create a station from its
//  `musicToken` (pithos SearchDialog + add_station flow). The 350 ms
//  debounce lives here (a `LaunchedEffect(query)` — the keystroke
//  restarts it, which is the same cancel-restart contract); policy
//  (min query length, scoring) lives in the store/`pandoracore` —
//  this view only dispatches intents. Dismisses on
//  `stationCreatedTick`.
//

package dev.personal.amphora.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import kotlinx.coroutines.delay
import pandoracore.MusicSearchResult

@Composable
fun AddStationView(store: AmphoraStore, onDismiss: () -> Unit) {
    val theme = Theme.tokens()
    var query by rememberSaveable { mutableStateOf("") }

    // The debounced search (iOS `scheduleSearch`): restart the 350 ms
    // timer on every keystroke; short queries clear results at once.
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            store.send(AmphoraStore.Intent.SearchStations(trimmed))
        } else {
            delay(350)
            store.send(AmphoraStore.Intent.SearchStations(trimmed))
        }
    }
    // The station landed — close the sheet (fire-and-forget intent
    // can't return success, so the tick is the signal).
    LaunchedEffect(store.stationCreatedTick) {
        if (store.stationCreatedTick > 0) onDismiss()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Inline title row with the leading Cancel (iOS nav bar).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "New Station",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor(theme),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.accent)
            }
        }

        // The search field (iOS `.searchable`).
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text("Artists, songs, genres", color = secondaryColor(theme), fontSize = 16.sp)
            },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = secondaryColor(theme))
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = secondaryColor(theme))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(Theme.radiusM),
            textStyle = TextStyle(fontSize = 16.sp, color = labelColor(theme)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = theme.surfaceHighlight,
                unfocusedContainerColor = theme.surfaceHighlight,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = theme.accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        AddStationContent(store = store, query = query)
    }
}

@Composable
private fun AddStationContent(store: AmphoraStore, query: String) {
    val theme = Theme.tokens()
    val trimmed = query.trim()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            store.lastErrorMessage != null -> ErrorBanner(
                store = store,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        when {
            store.isSearching && store.searchResults.isEmpty() -> Centered(
                modifier = Modifier.fillMaxSize(),
            ) {
                CircularProgressIndicator(color = secondaryColor(theme))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Searching…",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                    modifier = Modifier.semantics { contentDescription = "Searching" },
                )
            }
            trimmed.length < 2 -> Prompt("Type at least 2 characters to search.")
            store.searchResults.isEmpty() -> Prompt(
                if (trimmed.isEmpty()) "Search to add a station."
                else "No results for \u201C$trimmed\u201D."
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 4.dp, bottom = 24.dp,
                ),
            ) {
                items(store.searchResults, key = { it.id }) { hit ->
                    SearchResultRow(store = store, hit = hit)
                }
            }
        }
    }
}

/**
 * One search hit: kind icon, title, caption, plus — tapping creates
 * the station.
 */
@Composable
private fun SearchResultRow(store: AmphoraStore, hit: MusicSearchResult) {
    val theme = Theme.tokens()
    RowPressableButton(
        onClick = { store.send(AmphoraStore.Intent.CreateStation(hit)) },
        enabled = !store.isMutatingStations,
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
            Icon(
                imageVector = when (hit.kind) {
                    MusicSearchResult.Kind.artist -> Icons.Rounded.Mic
                    MusicSearchResult.Kind.song -> Icons.Rounded.MusicNote
                    MusicSearchResult.Kind.genre -> Icons.Rounded.Radio
                },
                contentDescription = null,
                tint = theme.accent,
                modifier = Modifier.width(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = labelColor(theme),
                    maxLines = 1,
                )
                Text(
                    text = caption(hit),
                    fontSize = 12.sp,
                    color = secondaryColor(theme),
                    maxLines = 1,
                )
            }
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
                    tint = theme.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** Second line: the artist for a song (pithos shows "title by artist"
 *  on one line; we split across two), a kind label otherwise. */
private fun caption(hit: MusicSearchResult): String = when (hit.kind) {
    MusicSearchResult.Kind.song -> hit.subtitle ?: "Song"
    MusicSearchResult.Kind.artist -> "Artist"
    MusicSearchResult.Kind.genre -> "Genre station"
}

/** iOS `ContentUnavailableView("Find a station", magnifyingglass)`. */
@Composable
private fun Prompt(text: String) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Find a station",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor(theme),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = secondaryColor(theme),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

/** The non-blocking error banner, with the stroke the add sheet adds. */
@Composable
private fun ErrorBanner(store: AmphoraStore, modifier: Modifier = Modifier) {
    val theme = Theme.tokens()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.surface, RoundedCornerShape(Theme.radiusM))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = Color(0xFFFFD60A),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = store.lastErrorMessage.orEmpty(),
            fontSize = 13.sp,
            color = labelColor(theme),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { store.send(AmphoraStore.Intent.DismissError) }) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dismiss error",
                tint = labelColor(theme),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
