//
//  SleepTimerSheet.kt
//  app
//
//  Port of Amphora/UI/SleepTimerSheet.swift — sleep-timer control:
//  pick a duration or "end of track".
//
//  Port deltas (per the app's established sheet pattern, documented in
//  TuneStationSheet.kt):
//  - iOS wraps the sheet in a NavigationStack with an inline title;
//    here the sheet carries the same inline title row + trailing Done.
//  - SF Symbols moon.zzz → Material `Bedtime`; forward.end → Material
//    `SkipNext` (the ▶| glyph).
//  - The two List sections become card-row groups with a section gap.
//

package dev.personal.amphora.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.support.rememberHaptics
import pandoracore.SleepTimer

/** Offered durations in minutes. */
private val sleepTimerDurations = listOf(15, 30, 45, 60, 90)

@Composable
fun SleepTimerSheet(store: AmphoraStore, onDismiss: () -> Unit) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()

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
                text = "Sleep Timer",
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
            ),
        ) {
            // Section 1: the durations.
            items(sleepTimerDurations.size) { index ->
                val minutes = sleepTimerDurations[index]
                SheetActionRow(
                    label = "$minutes minutes",
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Bedtime,
                            contentDescription = null,
                            tint = secondaryColor(theme),
                        )
                    },
                    onClick = {
                        haptics.light()
                        store.send(AmphoraStore.Intent.SetSleepTimer(SleepTimer(minutes)))
                        onDismiss()
                    },
                )
            }
            // Section 2: end-of-track + (when set) cancel. The section
            // gap stands in for iOS's second List section.
            item(key = "end-of-track") {
                SheetActionRow(
                    label = "End of current track",
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = null,
                            tint = secondaryColor(theme),
                        )
                    },
                    onClick = {
                        haptics.light()
                        store.send(
                            AmphoraStore.Intent.SetSleepTimer(SleepTimer(endOfTrack = true)),
                        )
                        onDismiss()
                    },
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (!store.sleepTimer.isOff) {
                item(key = "cancel") {
                    SheetActionRow(
                        label = "Cancel timer",
                        icon = null,
                        onClick = {
                            haptics.light()
                            store.send(AmphoraStore.Intent.CancelSleepTimer)
                            onDismiss()
                        },
                        labelColor = Color(0xFFFF5A5A),
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

/** One tappable list row: label, trailing secondary glyph — the iOS
 *  sheet's Button row in the card-row list language. */
@Composable
private fun SheetActionRow(
    label: String,
    icon: (@Composable () -> Unit)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelColor: Color = Theme.tokens().let { labelColor(it) },
) {
    RowPressableButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .amphoraCardRow(Theme.tokens()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 16.sp,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            icon?.invoke()
        }
    }
}
