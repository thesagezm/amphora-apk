//
//  RadioView.kt
//  app
//
//  Port of Amphora/UI/RadioView.swift — the Radio tab: internet radio
//  from the radio-browser directory (the Shortwave port, user request
//  2026-11-07). The root shows recently played stations, favorites, and
//  discovery entries (popular / country / tag); typing in the search
//  field swaps the root for live results. All directory fetching lives
//  in `RadioController` (`store.radio`) — this view only renders state
//  and dispatches intents to `AmphoraStore`.
//
//  Port deltas (documented per AGENTS.md):
//  - iOS's NavigationStack pushes (RadioStationListView /
//    RadioCountryListView / RadioTagListView) become a local pushed
//    state + BackHandler — the BrowseStationsSheet drill-down pattern.
//  - The recents row's trailing swipe-to-remove becomes the row's
//    long-press menu (the app's established context-menu language).
//  - SF Symbols map to Material glyphs (chart… → TrendingUp,
//    globe → Public, tag → Sell, speaker.wave.2 → VolumeUp,
//    star → Star/StarOutline, wifi.exclamationmark → WifiOff,
//    antenna.radiowaves → Antennas).
//  - The mini-player space is real layout room in MainTabView, so the
//    lists need no `.miniPlayer()` inset (the iOS modifier's job).
//

package dev.personal.amphora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.player.PlaybackState
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.store.RadioController
import dev.personal.amphora.support.rememberHaptics
import pandoracore.RadioStation

/** What the tab has pushed onto its local stack (the port of iOS's
 *  NavigationStack destinations). */
private sealed class RadioPush {
    data class Stations(val mode: RadioController.Mode) : RadioPush()
    data object Countries : RadioPush()
    data object Tags : RadioPush()
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun RadioView(store: AmphoraStore) {
    val theme = Theme.tokens()
    val radio = store.radio

    var isAddingStation by remember { mutableStateOf(false) }
    var editingStation by remember { mutableStateOf<RadioStation?>(null) }
    var pushed by remember { mutableStateOf<RadioPush?>(null) }

    BackHandler(enabled = pushed != null) { pushed = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        when (val target = pushed) {
            null -> {
                // iOS: `.navigationTitle("Radio")` + the trailing add
                // button.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Radio",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = labelColor(theme),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { isAddingStation = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Add custom station"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = theme.accent,
                        )
                    }
                }
                // The search field (iOS `.searchable`): non-empty text
                // swaps the root for live results (the controller owns
                // the debounce).
                TextField(
                    value = radio.searchText,
                    onValueChange = { radio.searchText = it },
                    placeholder = {
                        Text("Search radio stations", color = secondaryColor(theme), fontSize = 16.sp)
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = secondaryColor(theme))
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
                if (radio.searchText.trim().isEmpty()) {
                    RadioHomeList(
                        store = store,
                        onPopular = { pushed = RadioPush.Stations(RadioController.Mode.Popular) },
                        onCountries = { pushed = RadioPush.Countries },
                        onTags = { pushed = RadioPush.Tags },
                        onEditStation = { editingStation = it },
                    )
                } else {
                    RadioResultsBody(store)
                }
            }
            is RadioPush.Stations -> {
                PushedHeader(
                    title = target.mode.title,
                    onBack = { pushed = null },
                )
                // The load triggers on appear (iOS `.onAppear`), and
                // again when the destination is pushed after a back —
                // the iOS onAppear behavior.
                LaunchedEffect(target.mode) { radio.show(target.mode) }
                RadioResultsBody(store)
            }
            RadioPush.Countries -> {
                PushedHeader(title = "Countries", onBack = { pushed = null })
                LaunchedEffect(Unit) { radio.loadCountries() }
                RadioCountryBody(
                    store = store,
                    onOpen = { country ->
                        pushed = RadioPush.Stations(
                            RadioController.Mode.Country(country.code, country.name),
                        )
                    },
                )
            }
            RadioPush.Tags -> {
                PushedHeader(title = "Tags", onBack = { pushed = null })
                LaunchedEffect(Unit) { radio.loadTags() }
                RadioTagBody(
                    store = store,
                    onOpen = { tag ->
                        pushed = RadioPush.Stations(RadioController.Mode.Tag(tag.name))
                    },
                )
            }
        }
    }

    // Sheets (iOS `.sheet(isPresented:)` / `.sheet(item:)`).
    if (isAddingStation) {
        ModalBottomSheet(
            onDismissRequest = { isAddingStation = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = theme.background,
        ) {
            RadioStationFormSheet(store = store, existing = null, onDismiss = { isAddingStation = false })
        }
    }
    editingStation?.let { station ->
        ModalBottomSheet(
            onDismissRequest = { editingStation = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = theme.background,
        ) {
            RadioStationFormSheet(store = store, existing = station, onDismiss = { editingStation = null })
        }
    }
}

// MARK: Home (recents, favorites, discovery)

@Composable
private fun RadioHomeList(
    store: AmphoraStore,
    onPopular: () -> Unit,
    onCountries: () -> Unit,
    onTags: () -> Unit,
    onEditStation: (RadioStation) -> Unit,
) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()

    // The landing centerpiece: the last-played station (iOS's radioHero
    // reads `store.radioRecents.first`). Hidden until something played.
    val hero = store.radioRecents.firstOrNull()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        hero?.let { station ->
            item(key = "hero") {
                RadioHero(store, station)
            }
        }
        // Custom stations the user added by URL — editable and
        // deletable via the row's long-press menu (iOS contextMenu).
        if (store.radioCustomStations.isNotEmpty()) {
            item(key = "custom-header") { RadioSectionHeader("My Stations") }
            items(store.radioCustomStations, key = { "custom-${it.uuid}" }) { station ->
                MenuStationButton(
                    store = store,
                    station = station,
                    menu = { close ->
                        DropdownMenuItem(
                            text = { Text("Edit", color = labelColor(theme)) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Edit, contentDescription = null, tint = labelColor(theme))
                            },
                            onClick = {
                                close()
                                onEditStation(station)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = Color(0xFFFF5A5A)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5A5A),
                                )
                            },
                            onClick = {
                                close()
                                store.send(AmphoraStore.Intent.DeleteRadioCustomStation(station))
                            },
                        )
                    },
                )
            }
        }
        if (store.radioRecents.isNotEmpty()) {
            item(key = "recents-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Recently Played",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = secondaryColor(theme),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Clear",
                        fontSize = 12.sp,
                        color = theme.accentText,
                        modifier = Modifier
                            .clickable {
                                haptics.light()
                                store.send(AmphoraStore.Intent.ClearRadioRecents)
                            }
                            .semantics {
                                contentDescription = "Clear recently played radio stations"
                            }
                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
            items(store.radioRecents, key = { "recent-${it.uuid}" }) { station ->
                MenuStationButton(
                    store = store,
                    station = station,
                    menu = { close ->
                        // iOS: the trailing swipe action.
                        DropdownMenuItem(
                            text = { Text("Remove", color = Color(0xFFFF5A5A)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5A5A),
                                )
                            },
                            onClick = {
                                close()
                                store.send(AmphoraStore.Intent.RemoveRadioRecent(station))
                            },
                        )
                    },
                )
            }
        }
        if (store.radioFavorites.isNotEmpty()) {
            item(key = "favorites-header") { RadioSectionHeader("Favorites") }
            items(store.radioFavorites, key = { "favorite-${it.uuid}" }) { station ->
                StationButton(store, station)
            }
        }
        item(key = "discover-header") { RadioSectionHeader("Discover") }
        item(key = "discover-popular") {
            DiscoverRow(
                title = "Popular",
                caption = "The directory's most upvoted stations",
                icon = Icons.Rounded.TrendingUp,
                onClick = onPopular,
            )
        }
        item(key = "discover-countries") {
            DiscoverRow(
                title = "Countries",
                caption = "Browse stations by country",
                icon = Icons.Rounded.Public,
                onClick = onCountries,
            )
        }
        item(key = "discover-tags") {
            DiscoverRow(
                title = "Tags",
                caption = "Browse stations by genre tag",
                icon = Icons.Rounded.Sell,
                onClick = onTags,
            )
        }
    }
}

/**
 * The last-played radio station as the landing page's centerpiece: the
 * station's tile large and centered, the name, and a play/pause button
 * — the Home tab's hero, for radio. The store toggles playback when the
 * tapped station is the one playing, so the button doubles as pause.
 */
@Composable
private fun RadioHero(store: AmphoraStore, station: RadioStation) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    val playerState by store.player.state.collectAsState()
    val isPlaying = store.currentRadioStation?.uuid == station.uuid &&
        (playerState == PlaybackState.playing || playerState == PlaybackState.buffering)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 14.dp, start = 16.dp, end = 16.dp),
    ) {
        RadioStationArtwork(
            stationName = station.name,
            faviconURL = station.faviconURL,
            cornerRadius = Theme.radiusL,
            minimumArtworkPixels = 200,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 240.dp)
                .aspectRatio(1f)
                .shadow(
                    elevation = 22.dp,
                    shape = RoundedCornerShape(Theme.radiusL),
                    ambientColor = Color.Black.copy(alpha = 0.40f),
                    spotColor = Color.Black.copy(alpha = 0.40f),
                ),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = station.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = labelColor(theme),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isPlaying) "Now playing" else "Last played",
                fontSize = 12.sp,
                color = secondaryColor(theme),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        RadioHeroPlayButton(
            isPlaying = isPlaying,
            stationName = station.name,
            onToggle = {
                haptics.light()
                store.send(AmphoraStore.Intent.PlayRadioStation(station))
            },
        )
    }
}

/** The 58dp accent circle with the drawn play/pause glyph (the radio
 *  hero's copy of the Home hero's press-dip button — the iOS files
 *  duplicate it too). */
@Composable
private fun RadioHeroPlayButton(
    isPlaying: Boolean,
    stationName: String,
    onToggle: () -> Unit,
) {
    val theme = Theme.tokens()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) Pressable.pressedScale else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.55f,
            stiffness = Pressable.springStiffness,
        ),
        label = "radio-hero-press-scale",
    )
    val opacity by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) Pressable.pressedOpacity else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.55f,
            stiffness = Pressable.springStiffness,
        ),
        label = "radio-hero-press-opacity",
    )

    Box(
        modifier = Modifier
            .size(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = opacity
            }
            .shadow(
                elevation = 14.dp,
                shape = CircleShape,
                ambientColor = theme.accent.copy(alpha = 0.35f),
                spotColor = theme.accent.copy(alpha = 0.35f),
            )
            .background(theme.accent, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .semantics {
                contentDescription =
                    if (isPlaying) "Pause $stationName" else "Play $stationName"
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.animation.Crossfade(
            targetState = isPlaying,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
            label = "radio-hero-glyph",
        ) { playing ->
            TransportGlyph(
                kind = if (playing) TransportGlyphKind.pause else TransportGlyphKind.play,
                size = 23.dp,
                color = theme.accentGlyph,
            )
        }
    }
}

/** `true` when the station is the one whose live stream is playing. */
private fun isCurrentRadio(store: AmphoraStore, station: RadioStation): Boolean =
    store.currentRadioStation?.uuid == station.uuid

/** A station row as a play button — tapping plays (or toggles) the
 *  live stream via the store. */
@Composable
private fun StationButton(store: AmphoraStore, station: RadioStation) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    RowPressableButton(
        onClick = {
            haptics.light()
            store.send(AmphoraStore.Intent.PlayRadioStation(station))
        },
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme, tinted = isCurrentRadio(store, station)),
    ) {
        RadioStationRow(store = store, station = station)
    }
}

/**
 * A play row with a long-press menu (the port of iOS's contextMenu /
 * swipeActions): custom stations get Edit/Delete, recents get Remove.
 */
@Composable
private fun MenuStationButton(
    store: AmphoraStore,
    station: RadioStation,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    var menuOpen by remember(station.uuid) { mutableStateOf(false) }
    RowPressableButton(
        onClick = {
            haptics.light()
            store.send(AmphoraStore.Intent.PlayRadioStation(station))
        },
        onLongClick = { menuOpen = true },
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme, tinted = isCurrentRadio(store, station)),
    ) {
        RadioStationRow(store = store, station = station)

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = theme.surface,
        ) {
            menu { menuOpen = false }
        }
    }
}

// MARK: Station results (search root + pushed lists)

/**
 * The controller's current `results` with loading / error / empty
 * states. Used directly for search results at the tab root and wrapped
 * by the pushed discovery lists.
 */
@Composable
private fun RadioResultsBody(store: AmphoraStore) {
    val radio = store.radio
    when {
        radio.results.isNotEmpty() -> RadioResultsList(store)
        radio.isLoading || !radio.didLoadResults -> CenteredState {
            CircularProgressIndicator(color = secondaryColor(Theme.tokens()))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Loading stations…",
                fontSize = 13.sp,
                color = secondaryColor(Theme.tokens()),
                modifier = Modifier.semantics { contentDescription = "Loading stations" },
            )
        }
        radio.errorMessage != null -> UnavailableState(
            title = "Couldn't load stations",
            message = radio.errorMessage.orEmpty(),
            icon = Icons.Rounded.WifiOff,
            retry = { radio.activeMode?.let { radio.show(it) } },
        )
        else -> UnavailableState(
            title = "No stations",
            message = "Nothing matched here. Try another search or browse.",
            icon = Icons.Rounded.Radio,
        )
    }
}

@Composable
private fun RadioResultsList(store: AmphoraStore) {
    val radio = store.radio
    val haptics = rememberHaptics()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(radio.results, key = { it.uuid }) { station ->
            StationButton(store, station)
        }
        // The paging footer: "Show More" while the ranking has more
        // pages, a spinner while the next page loads, and the failure +
        // retry when one didn't come back. The stations already on
        // screen are never disturbed by it.
        if (radio.isLoadingMore) {
            item(key = "more-loading") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .amphoraCardRow(Theme.tokens()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = secondaryColor(Theme.tokens()),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Loading more stations…",
                        fontSize = 12.sp,
                        color = secondaryColor(Theme.tokens()),
                    )
                }
            }
        } else if (radio.moreErrorMessage != null) {
            item(key = "more-error") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .amphoraCardRow(Theme.tokens()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = radio.moreErrorMessage.orEmpty(),
                        fontSize = 12.sp,
                        color = Color(0xFFFFD60A),
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { radio.loadMore() }) {
                        Text("Retry", color = Theme.tokens().accentText)
                    }
                }
            }
        } else if (radio.canLoadMore) {
            item(key = "more-button") {
                RowPressableButton(
                    onClick = {
                        haptics.light()
                        radio.loadMore()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .amphoraCardRow(Theme.tokens()),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // iOS: chevron.down.
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = secondaryColor(Theme.tokens()),
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(90f),
                        )
                        Text(
                            text = "Show More Stations",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = labelColor(Theme.tokens()),
                        )
                    }
                }
            }
        }
    }
}

// MARK: Catalog lists (countries, tags)

@Composable
private fun RadioCountryBody(
    store: AmphoraStore,
    onOpen: (pandoracore.RadioCountry) -> Unit,
) {
    val radio = store.radio
    val countries = radio.countries
    when {
        countries != null && countries.isEmpty() -> UnavailableState(
            title = "No countries",
            message = "The directory returned no countries.",
            icon = Icons.Rounded.Public,
        )
        countries != null -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(countries, key = { it.id }) { country ->
                CatalogRow(
                    title = country.name.ifEmpty { country.code },
                    caption = "${country.stationCount} stations",
                    icon = Icons.Rounded.Public,
                    onClick = { onOpen(country) },
                )
            }
        }
        radio.isLoading || countries == null -> CenteredState {
            CircularProgressIndicator(color = secondaryColor(Theme.tokens()))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Loading countries…",
                fontSize = 13.sp,
                color = secondaryColor(Theme.tokens()),
                modifier = Modifier.semantics { contentDescription = "Loading countries" },
            )
        }
        else -> UnavailableState(
            title = "Couldn't load countries",
            message = radio.errorMessage.orEmpty(),
            icon = Icons.Rounded.WifiOff,
            retry = { radio.retryCountries() },
        )
    }
}

@Composable
private fun RadioTagBody(
    store: AmphoraStore,
    onOpen: (pandoracore.RadioTag) -> Unit,
) {
    val radio = store.radio
    val tags = radio.tags
    when {
        tags != null && tags.isEmpty() -> UnavailableState(
            title = "No tags",
            message = "The directory returned no tags.",
            icon = Icons.Rounded.Sell,
        )
        tags != null -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tags, key = { it.id }) { tag ->
                CatalogRow(
                    title = tag.name,
                    caption = "${tag.stationCount} stations",
                    icon = Icons.Rounded.Sell,
                    onClick = { onOpen(tag) },
                )
            }
        }
        radio.isLoading || tags == null -> CenteredState {
            CircularProgressIndicator(color = secondaryColor(Theme.tokens()))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Loading tags…",
                fontSize = 13.sp,
                color = secondaryColor(Theme.tokens()),
                modifier = Modifier.semantics { contentDescription = "Loading tags" },
            )
        }
        else -> UnavailableState(
            title = "Couldn't load tags",
            message = radio.errorMessage.orEmpty(),
            icon = Icons.Rounded.WifiOff,
            retry = { radio.retryTags() },
        )
    }
}

// MARK: Shared rows

/**
 * One directory station: favicon, name, caption (country · codec ·
 * bitrate), a playing indicator for the live stream, and the star
 * toggle for favorites. The row itself is wrapped in a play button by
 * the callers; the star is a nested button and takes its own taps.
 */
@Composable
private fun RadioStationRow(store: AmphoraStore, station: RadioStation) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    val playerState by store.player.state.collectAsState()
    val isCurrent = isCurrentRadio(store, station)
    val isPlaying = playerState == PlaybackState.playing || playerState == PlaybackState.buffering
    val isFavorite = store.isRadioFavorite(station)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The station's real favicon when it exists and is big enough
        // for this slot; otherwise the generated monogram tile — a bare
        // antenna glyph read as "no artwork".
        RadioStationArtwork(
            stationName = station.name,
            faviconURL = station.faviconURL,
            cornerRadius = Theme.radiusS,
            minimumArtworkPixels = 64,
            modifier = Modifier.size(44.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = station.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) theme.accentText else labelColor(theme),
                maxLines = 1,
            )
            if (station.caption.isNotEmpty()) {
                Text(
                    text = station.caption,
                    fontSize = 12.sp,
                    color = secondaryColor(theme),
                    maxLines = 1,
                )
            }
        }
        if (isCurrent && isPlaying) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = "Playing",
                tint = theme.accent,
                modifier = Modifier.size(14.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable {
                    haptics.light()
                    store.send(AmphoraStore.Intent.ToggleRadioFavorite(station))
                }
                .semantics {
                    contentDescription =
                        if (isFavorite) {
                            "Remove ${station.name} from favorites"
                        } else {
                            "Add ${station.name} to favorites"
                        }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                contentDescription = null,
                tint = if (isFavorite) theme.accentText else secondaryColor(theme),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A discover entry: accent tile + caption + chevron (the port of
 *  `discoverRow`). */
@Composable
private fun DiscoverRow(
    title: String,
    caption: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val theme = Theme.tokens()
    RowPressableButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentTile(icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = labelColor(theme),
                    maxLines = 1,
                )
                Text(
                    text = caption,
                    fontSize = 12.sp,
                    color = secondaryColor(theme),
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = secondaryColor(theme),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A country/tag catalog row (the port of `catalogRow`). */
@Composable
private fun CatalogRow(
    title: String,
    caption: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val theme = Theme.tokens()
    RowPressableButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentTile(icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = labelColor(theme),
                    maxLines = 1,
                )
                Text(
                    text = caption,
                    fontSize = 12.sp,
                    color = secondaryColor(theme),
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = secondaryColor(theme),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** The 44dp accent-washed tile behind a discover/catalog row's glyph. */
@Composable
private fun AccentTile(icon: ImageVector) {
    val theme = Theme.tokens()
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(theme.accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = theme.accentText,
            modifier = Modifier.size(20.dp),
        )
    }
}

// MARK: States + headers

/** A pushed screen's header: back arrow + inline title (the port of
 *  the NavigationStack navigation bar). */
@Composable
internal fun PushedHeader(title: String, onBack: () -> Unit) {
    val theme = Theme.tokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = theme.accent,
            )
        }
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = labelColor(theme),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        // Balances the back button so the title sits centered.
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
internal fun RadioSectionHeader(title: String) {
    val theme = Theme.tokens()
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = secondaryColor(theme),
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** iOS `ProgressView` tinted secondary, centered. */
@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

/** iOS `ContentUnavailableView` — no action. */
@Composable
private fun UnavailableState(
    title: String,
    message: String,
    icon: ImageVector,
) {
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
            text = message,
            fontSize = 13.sp,
            color = secondaryColor(theme),
            textAlign = TextAlign.Center,
        )
    }
}

/** iOS `ContentUnavailableView` with a Retry action (the error
 *  states). */
@Composable
private fun UnavailableState(
    title: String,
    message: String,
    icon: ImageVector,
    retry: () -> Unit,
) {
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
            text = message,
            fontSize = 13.sp,
            color = secondaryColor(theme),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = retry,
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.accent,
                contentColor = theme.accentGlyph,
            ),
        ) {
            Text("Retry")
        }
    }
}
