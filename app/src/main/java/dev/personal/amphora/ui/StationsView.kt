//
//  StationsView.kt
//  app
//
//  Port of Amphora/UI/StationsView.swift — the Home tab. The store owns
//  the order (QuickMix first, then the user's stored order, then A–Z);
//  the view renders `store.stations` as given. No networking here —
//  intents only.
//
//  Arriving with their own units (kept out so nothing ships inert):
//  - browse row + Add Station flow: A3.4
//  - the "Tune Station…" context item + TuneStationSheet: A3.5
//  - recents toolbar button: A5
//  - row taps start playback: A4 wiring (`.playStation`) — until then
//    rows carry the long-press context menu only, so no dead tap
//    pretends to play.
//
//  The hero renders only when a last-played station exists — A4
//  populates `preferences.lastStationId`, so until then the hero (and
//  its play control) is unreachable, and its play action is wired in
//  that same unit.
//
//  Drag reorder (edit mode): the toolbar toggle shows a trailing grip
//  on each row; dragging it moves rows one slot at a time — the port
//  of iOS edit-mode `.onMove`, which also reports one move at a time.
//  iOS auto-scrolls while dragging past the edge; the Compose port
//  relies on lift-and-repeat for long moves (station lists are short).
//

package dev.personal.amphora.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.personal.amphora.store.AmphoraStore
import pandoracore.Station

@Composable
fun StationsView(store: AmphoraStore) {
    val theme = Theme.tokens()

    // iOS `@State` per concern: rename target + text, delete target,
    // edit-mode reorder (tune target arrives with A3.5's sheet).
    var renameTarget by remember { mutableStateOf<Station?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Station?>(null) }
    var isEditingOrder by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // iOS: `.navigationTitle("Home")` + trailing toolbar items
            // (recents joins in A5, add in A3.4).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Home",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor(theme),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { isEditingOrder = !isEditingOrder },
                    modifier = Modifier.semantics {
                        contentDescription = "Reorder stations"
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SwapVert,
                        contentDescription = null,
                        tint = theme.accent,
                    )
                }
            }
            when {
                store.stations.isEmpty() && store.isLoadingStations -> LoadingState()
                store.stations.isEmpty() && store.lastErrorMessage != null ->
                    ErrorState(store)
                store.stations.isEmpty() -> EmptyState()
                else -> StationList(
                    store = store,
                    isEditingOrder = isEditingOrder,
                    onRename = { station ->
                        renameText = station.name
                        renameTarget = station
                    },
                    onDelete = { deleteTarget = it },
                )
            }
        }
        // Non-blocking error surface once a list exists (empty-state
        // errors render above); M5 restyles these.
        if (store.stations.isNotEmpty() && store.lastErrorMessage != null) {
            ErrorBanner(
                store = store,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    renameTarget?.let { station ->
        RenameDialog(
            station = station,
            text = renameText,
            onTextChange = { renameText = it },
            onRename = {
                store.send(AmphoraStore.Intent.RenameStation(station, renameText))
                renameTarget = null
            },
            onCancel = { renameTarget = null },
        )
    }
    deleteTarget?.let { station ->
        DeleteDialog(
            station = station,
            onDelete = {
                store.send(AmphoraStore.Intent.DeleteStation(station))
                deleteTarget = null
            },
            onCancel = { deleteTarget = null },
        )
    }
}

// MARK: States

/** iOS: `ProgressView("Loading stations…")` tinted secondary. */
@Composable
private fun LoadingState() {
    val theme = Theme.tokens()
    Column(
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

/** iOS: `ContentUnavailableView` — "No stations yet". The Add Station
 *  action joins when AddStationView lands (A3.4). */
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
            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "No stations yet",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor(theme),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Your stations will appear here.",
            fontSize = 13.sp,
            color = secondaryColor(theme),
            textAlign = TextAlign.Center,
        )
    }
}

/** iOS: `ContentUnavailableView` — "Couldn't load stations" + Retry. */
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
            onClick = { store.send(AmphoraStore.Intent.RefreshStations) },
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.accent,
                contentColor = theme.accentGlyph,
            ),
        ) {
            Text("Retry")
        }
    }
}

// MARK: List

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StationList(
    store: AmphoraStore,
    isEditingOrder: Boolean,
    onRename: (Station) -> Unit,
    onDelete: (Station) -> Unit,
) {
    val theme = Theme.tokens()
    val listState = rememberLazyListState()

    // Drag-reorder state (iOS edit mode's grabber + onMove): the id of
    // the row under the finger and its accumulated translation.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingOffset by remember { mutableFloatStateOf(0f) }

    PullToRefreshBox(
        isRefreshing = store.isLoadingStations,
        onRefresh = { store.send(AmphoraStore.Intent.RefreshStations) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            // The hero: the last-played (or playing) station, large and
            // centered — the landing page's centerpiece. Hidden until
            // something has played (A4 populates `lastStationId`).
            item(key = "hero") {
                Hero(store)
            }
            // The account-level explicit-content badge — Pandora's
            // explicit filter is an **account** switch, not a per-track
            // flag, so the app says the one true thing.
            if (store.isExplicitContentFilterEnabled) {
                item(key = "explicit-notice") {
                    ExplicitContentNotice(
                        modifier = Modifier.amphoraCardRow(theme),
                    )
                }
            }
            itemsIndexed(store.stations, key = { _, station -> station.id }) { _, station ->
                // animateItem lives in LazyItemScope — the wrapper Box
                // is the item root the placement animation attaches to.
                Box(modifier = Modifier.animateItem()) {
                    ReorderableStationRow(
                        store = store,
                        station = station,
                        isEditingOrder = isEditingOrder,
                        listState = listState,
                        draggingId = draggingId,
                        draggingOffset = draggingOffset,
                        onDragStart = {
                            draggingId = station.id
                            draggingOffset = 0f
                        },
                        onDrag = { delta ->
                            draggingOffset += delta
                            val correction =
                                moveIfNeeded(store, station.id, listState, draggingOffset)
                            draggingOffset += correction
                        },
                        onDragEnd = {
                            draggingId = null
                            draggingOffset = 0f
                        },
                        onRename = onRename,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

/**
 * Fires `MoveStation(from, to)` when the dragged row's center crosses
 * a neighbor, and returns the offset correction that keeps the row
 * under the finger across the layout change (the passed row's height).
 */
private fun moveIfNeeded(
    store: AmphoraStore,
    draggedId: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    draggingOffset: Float,
): Float {
    val info = listState.layoutInfo
    val dragged = info.visibleItemsInfo.firstOrNull { it.key == draggedId } ?: return 0f
    val middle = dragged.offset + draggingOffset + dragged.size / 2f
    val target = info.visibleItemsInfo.firstOrNull { item ->
        item.key != draggedId && middle >= item.offset && middle < item.offset + item.size
    } ?: return 0f
    val from = store.stations.indexOfFirst { it.id == draggedId }
    val to = store.stations.indexOfFirst { it.id == target.key }
    if (from < 0 || to < 0 || from == to) return 0f
    store.send(AmphoraStore.Intent.MoveStation(from = from, to = to))
    // iOS onMove reports one row at a time; same here.
    return if (to > from) -target.size.toFloat() else target.size.toFloat()
}

@Composable
private fun ReorderableStationRow(
    store: AmphoraStore,
    station: Station,
    isEditingOrder: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    draggingId: String?,
    draggingOffset: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onRename: (Station) -> Unit,
    onDelete: (Station) -> Unit,
) {
    val isDragging = draggingId == station.id
    Box(
        modifier = Modifier
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer { translationY = if (isDragging) draggingOffset else 0f },
    ) {
        StationListRow(
            store = store,
            station = station,
            isEditingOrder = isEditingOrder,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onRename = onRename,
            onDelete = onDelete,
        )
    }
}

/**
 * One row: card surface, artwork, name, badge caption for the
 * non-plain cases, context menu. iOS wraps the row in
 * `RowPressableButtonStyle` with the play intent — that intent is A4
 * wiring, so the press wrapper joins then; the long-press menu works
 * today.
 */
@Composable
private fun StationListRow(
    store: AmphoraStore,
    station: Station,
    isEditingOrder: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onRename: (Station) -> Unit,
    onDelete: (Station) -> Unit,
) {
    val theme = Theme.tokens()
    val isCurrent = store.currentStation?.id == station.id
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.amphoraCardRow(theme, tinted = isCurrent)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = rowAccessibilityLabel(store, station, isCurrent)
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CachedArtwork(
                urlString = station.artUrl,
                cornerRadius = 10.dp,
                modifier = Modifier.size(52.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (station.isQuickMix) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = null,
                            tint = theme.accentText,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = station.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isCurrent) theme.accentText else labelColor(theme),
                        maxLines = 1,
                    )
                }
                badgeCaption(store, station)?.let { caption ->
                    Text(
                        text = caption,
                        fontSize = 12.sp,
                        color = secondaryColor(theme),
                    )
                }
            }
            // Edit mode's grabber: dragging it moves the row — the port
            // of the iOS List edit-mode reorder handle.
            if (isEditingOrder) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .pointerInput(station.id) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDrag = { change, amount ->
                                    change.consume()
                                    onDrag(amount.y)
                                },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragEnd() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = secondaryColor(theme),
                    )
                }
            }
            if (isCurrent && store.isPlayingCurrentStation) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "Playing",
                    tint = theme.accent,
                    modifier = Modifier.size(14.dp),
                )
            } else if (station.isShared) {
                Icon(
                    imageVector = Icons.Rounded.Group,
                    contentDescription = "Shared station",
                    tint = secondaryColor(theme),
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // The context menu (iOS `.contextMenu`): Tune joins in A3.5;
        // pithos' edit dialog filters quickmix + thumbprint out of
        // rename/delete — mirrored here.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = theme.surface,
        ) {
            if (!station.isQuickMix && !station.isThumbprint) {
                DropdownMenuItem(
                    text = { Text("Rename", color = labelColor(theme)) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Edit, contentDescription = null, tint = labelColor(theme))
                    },
                    onClick = {
                        menuOpen = false
                        onRename(station)
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
                        menuOpen = false
                        onDelete(station)
                    },
                )
            }
        }
    }
}

/**
 * The account-level explicit-content badge: "Explicit content is
 * filtered for this account."
 */
@Composable
private fun ExplicitContentNotice(modifier: Modifier = Modifier) {
    val theme = Theme.tokens()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS: `e.square.fill` — a filled square with the letter E.
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(theme.accentText, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "E",
                color = theme.surface,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Text(
            text = "Explicit content is filtered for this account.",
            fontSize = 13.sp,
            color = secondaryColor(theme),
        )
    }
}

/**
 * Non-blocking error surface: warning glyph, message, dismiss.
 */
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

// MARK: Hero

/**
 * The last-played (or playing) station, large and centered — the
 * landing page's centerpiece. Hidden until something has played.
 * (iOS wraps it in `PressableButtonStyle` + a snappy glyph swap; the
 * play action itself is A4 wiring.)
 */
@Composable
private fun Hero(store: AmphoraStore) {
    val theme = Theme.tokens()
    val station = store.featuredStation ?: return
    val isPlaying = store.currentStation?.id == station.id && store.isPlayingCurrentStation

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 14.dp, start = 16.dp, end = 16.dp),
    ) {
        CachedArtwork(
            urlString = station.artUrl,
            cornerRadius = Theme.radiusL,
            fallbackSystemImage = if (station.isQuickMix) {
                Icons.Rounded.Shuffle
            } else {
                Icons.AutoMirrored.Rounded.QueueMusic
            },
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 240.dp)
                .aspectRatio(1f)
                .shadow(
                    elevation = 22.dp,
                    shape = RoundedCornerShape(Theme.radiusL),
                    ambientColor = Color.Black.copy(alpha = 0.40f),
                    spotColor = Color.Black.copy(alpha = 0.40f),
                )
                .shadow(
                    elevation = 30.dp,
                    shape = RoundedCornerShape(Theme.radiusL),
                    ambientColor = theme.accent.copy(alpha = 0.16f),
                    spotColor = theme.accent.copy(alpha = 0.16f),
                ),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = station.name,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor(theme),
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isPlaying) "Now playing" else "Last played",
            fontSize = 12.sp,
            color = secondaryColor(theme),
        )
        Spacer(modifier = Modifier.height(16.dp))
        HeroPlayButton(isPlaying = isPlaying)
    }
}

/** The 58dp accent circle with the drawn play/pause glyph. */
@Composable
private fun HeroPlayButton(isPlaying: Boolean) {
    val theme = Theme.tokens()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) Pressable.pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Pressable.springStiffness),
        label = "hero-press-scale",
    )
    val opacity by animateFloatAsState(
        targetValue = if (isPressed) Pressable.pressedOpacity else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Pressable.springStiffness),
        label = "hero-press-opacity",
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
                // A4 wires `.playStation` — the hero only renders once a
                // station has played, which only A4's play path records.
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = isPlaying,
            animationSpec = tween(durationMillis = 200),
            label = "hero-glyph",
        ) { playing ->
            TransportGlyph(
                kind = if (playing) TransportGlyphKind.pause else TransportGlyphKind.play,
                size = 23.dp,
                color = theme.accentGlyph,
            )
        }
    }
}

// MARK: Row helpers

/** "Skip limit reached" → "Quick mix" → "Thumbprint station" → none. */
private fun badgeCaption(store: AmphoraStore, station: Station): String? = when {
    store.isSkipLimited(station) -> "Skip limit reached"
    station.isQuickMix -> "Quick mix"
    station.isThumbprint -> "Thumbprint station"
    else -> null
}

/** What VoiceOver reads for the row: the name, whether it is the one
 *  playing, and the skip-limit state when there is one. */
private fun rowAccessibilityLabel(store: AmphoraStore, station: Station, isCurrent: Boolean): String {
    var label = if (isCurrent) "${station.name}, currently playing" else station.name
    if (store.isSkipLimited(station)) {
        label += ", Pandora's daily skip limit reached"
    }
    return label
}

// MARK: Label colors (the `.primary` / `.secondary` port — the literals
// the LoginView port established for this codebase).

internal fun labelColor(theme: Theme.Tokens): Color =
    if (theme.isLight) Color(0xFF151021) else Color(0xFFF2EFFB)

internal fun secondaryColor(theme: Theme.Tokens): Color =
    if (theme.isLight) Color(0xFF5B5570) else Color(0xFF9B94B4)

// MARK: Dialogs (iOS `.alert`)

@Composable
private fun RenameDialog(
    station: Station,
    text: String,
    onTextChange: (String) -> Unit,
    onRename: () -> Unit,
    onCancel: () -> Unit,
) {
    val theme = Theme.tokens()
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = theme.surface,
        title = { Text("Rename Station", color = labelColor(theme)) },
        text = {
            Column {
                Text(
                    "Choose a new name for this station.",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 17.sp,
                        color = labelColor(theme),
                    ),
                    keyboardOptions = KeyboardOptions(
                        // iOS: .textInputAutocapitalization(.words)
                        capitalization = KeyboardCapitalization.Words,
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = theme.surfaceHighlight,
                        unfocusedContainerColor = theme.surfaceHighlight,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = theme.accent,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRename) {
                Text("Rename", color = theme.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = labelColor(theme))
            }
        },
    )
}

@Composable
private fun DeleteDialog(station: Station, onDelete: () -> Unit, onCancel: () -> Unit) {
    val theme = Theme.tokens()
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = theme.surface,
        // iOS: "Delete "\(name)""?" — curly quotes in the copy.
        title = { Text("Delete \u201C${station.name}\u201D?", color = labelColor(theme)) },
        text = {
            Text(
                "This removes the station from your list. It can't be undone here.",
                color = secondaryColor(theme),
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = Color(0xFFFF5A5A))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = labelColor(theme))
            }
        },
    )
}
