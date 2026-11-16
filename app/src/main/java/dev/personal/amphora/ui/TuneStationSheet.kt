//
//  TuneStationSheet.kt
//  app
//
//  Port of Amphora/UI/TuneStationSheet.swift — "Tune your station":
//  Pandora's listening modes (My Mix / Deep Cuts / Crowd Faves /
//  Discovery …). Free accounts can select every mode Pandora marks
//  available; the premium-only mode is shown disabled. Opened from the
//  station list's context menu (tunes that station for its next play);
//  the full-player open (`station: nil` → the playing station) joins
//  with A4. The sheet dismisses itself on the change tick.
//

package dev.personal.amphora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import pandoracore.Station
import pandoracore.StationMode
import pandoracore.StationModes

@Composable
fun TuneStationSheet(store: AmphoraStore, station: Station?, onDismiss: () -> Unit) {
    val theme = Theme.tokens()

    LaunchedEffect(Unit) {
        store.send(AmphoraStore.Intent.LoadStationModes(station))
    }
    LaunchedEffect(store.stationModeChangedTick) {
        if (store.stationModeChangedTick > 0) onDismiss()
    }

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
                text = "Tune Station",
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

        val modes = store.stationModes
        when {
            modes != null -> {
                if (!modes.isInteractiveRadioAvailable || modes.modes.isEmpty()) {
                    NoModesState()
                } else {
                    ModeList(store = store, modes = modes)
                }
            }
            store.isLoadingModes -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = secondaryColor(theme))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Loading modes…",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                )
            }
            store.lastErrorMessage != null -> ErrorState(store = store, station = station)
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = secondaryColor(theme))
            }
        }
    }
}

/** The mode list with its footer + inline error section. */
@Composable
private fun ModeList(store: AmphoraStore, modes: StationModes) {
    val theme = Theme.tokens()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
        ),
    ) {
        items(modes.modes, key = { it.id }) { mode ->
            ModeRow(store = store, mode = mode)
        }
        item(key = "footer") {
            Text(
                text = if (store.isChangingMode) "Changing mode…"
                else "Changing modes starts a new song.",
                fontSize = 12.sp,
                color = secondaryColor(theme),
                modifier = Modifier.padding(top = 10.dp, start = 8.dp),
            )
        }
        store.lastErrorMessage?.let { message ->
            item(key = "error") {
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = Color(0xFFFFD60A),
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp),
                )
            }
        }
    }
}

/** One mode row: name (+ Premium capsule), description, checkmark. */
@Composable
private fun ModeRow(store: AmphoraStore, mode: StationMode) {
    val theme = Theme.tokens()
    val selectable = mode.isSelectable(isSubscriber = store.isSubscriber)
    val isCurrent = store.stationModes?.currentModeID == mode.id

    RowPressableButton(
        onClick = { store.send(AmphoraStore.Intent.SetStationMode(mode, null)) },
        enabled = selectable && !store.isChangingMode,
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = mode.name,
                        fontSize = 16.sp,
                        color = if (selectable) labelColor(theme) else secondaryColor(theme),
                    )
                    if (mode.isPremiumOnly && !store.isSubscriber) {
                        Text(
                            text = "Premium",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = secondaryColor(theme),
                            modifier = Modifier
                                .background(theme.surfaceHighlight, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (mode.modeDescription.isNotEmpty()) {
                    Text(
                        text = mode.modeDescription,
                        fontSize = 13.sp,
                        color = secondaryColor(theme),
                    )
                }
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = theme.accent,
                )
            }
        }
    }
}

/** iOS: `ContentUnavailableView` "No tuning options". */
@Composable
private fun NoModesState() {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "No tuning options",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = labelColor(theme),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Pandora does not offer modes for this station.",
            fontSize = 13.sp,
            color = secondaryColor(theme),
            textAlign = TextAlign.Center,
        )
    }
}

/** iOS: `ContentUnavailableView` "Couldn't load modes" + Retry. */
@Composable
private fun ErrorState(store: AmphoraStore, station: Station?) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(44.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Couldn't load modes",
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
            onClick = { store.send(AmphoraStore.Intent.LoadStationModes(station)) },
            colors = ButtonDefaults.buttonColors(
                containerColor = theme.accent,
                contentColor = theme.accentGlyph,
            ),
        ) {
            Text("Retry")
        }
    }
}
