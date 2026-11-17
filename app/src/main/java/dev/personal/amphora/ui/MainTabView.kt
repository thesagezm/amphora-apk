//
//  MainTabView.kt
//  app
//
//  Port of Amphora/UI/MainTabView.swift — the signed-in shell: Home
//  (stations), Radio, Settings. Recents lost its tab on iOS (user
//  request 2026-11-07); history and bookmarks open from a Home toolbar
//  button (that button arrives with A5's RecentsView). The persistent
//  mini-player rides along, and MainTabView owns the full-player sheet
//  (one instance for the whole shell, so tab switches never tear it
//  down).
//
//  Port deltas:
//  - The mini-player sits between the tab content and the bar as real
//    layout space, instead of iOS's `.miniPlayer()` safe-area inset on
//    the scroll surfaces. That is the *better* behavior iOS was chasing
//    (2026-11-11 report): the card never overlaps the lists here, so
//    the last row is always reachable.
//  - The NowPlayingView sheet is one ModalBottomSheet at this level
//    (iOS: one `.sheet` on the TabView).
//  - All three tab contents are live: Home (A3), Radio (A7.3),
//    Settings (A8.2). QuietTabPlaceholder is gone with the last
//    milestone that needed it.
//  - SF Symbols map to the closest Material glyphs (music.note.house →
//    Home, antenna.radiowaves… → Radio, gearshape → Settings).
//

package dev.personal.amphora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore

private const val TAB_HOME = 0
private const val TAB_RADIO = 1
private const val TAB_SETTINGS = 2

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MainTabView(store: AmphoraStore) {
    val theme = Theme.tokens()
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var isShowingNowPlaying by rememberSaveable { mutableStateOf(false) }
    // The mini-player slot: empty until something plays (the port of
    // MiniPlayerInset's `if store.player.currentTrack != nil`).
    val currentTrack by store.player.currentTrack.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        Box(modifier = Modifier.weight(1f).statusBarsPadding()) {
            when (selectedTab) {
                TAB_HOME -> StationsView(store)
                TAB_RADIO -> RadioView(store)
                TAB_SETTINGS -> SettingsView(store)
            }
        }
        if (currentTrack != null) {
            MiniPlayer(store = store, onOpen = { isShowingNowPlaying = true })
        }
        NavigationBar(containerColor = theme.surface) {
            val secondary = if (theme.isLight) Color(0xFF5B5570) else Color(0xFF9B94B4)
            TabItem(
                label = "Home",
                icon = Icons.Rounded.Home,
                selected = selectedTab == TAB_HOME,
                accent = theme.accent,
                accentText = theme.accentText,
                secondary = secondary,
                onClick = { selectedTab = TAB_HOME },
            )
            TabItem(
                label = "Radio",
                icon = Icons.Rounded.Radio,
                selected = selectedTab == TAB_RADIO,
                accent = theme.accent,
                accentText = theme.accentText,
                secondary = secondary,
                onClick = { selectedTab = TAB_RADIO },
            )
            TabItem(
                label = "Settings",
                icon = Icons.Rounded.Settings,
                selected = selectedTab == TAB_SETTINGS,
                accent = theme.accent,
                accentText = theme.accentText,
                secondary = secondary,
                onClick = { selectedTab = TAB_SETTINGS },
            )
        }
    }

    // The full player: one sheet instance for the whole shell — the
    // port of `.sheet(isPresented:) { NowPlayingView() }`.
    if (isShowingNowPlaying) {
        ModalBottomSheet(
            onDismissRequest = { isShowingNowPlaying = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.Transparent,
        ) {
            NowPlayingView(store = store, onDismiss = { isShowingNowPlaying = false })
        }
    }
}

/** One tab entry — iOS tints the selected item `Theme.accent`.
 *  (m3 1.3.2 declares `NavigationBarItem` on `RowScope`, so this is
 *  one too and is called inside the `NavigationBar { }` row.) */
@Composable
private fun RowScope.TabItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    accentText: Color,
    secondary: Color,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = accent,
            selectedTextColor = accentText,
            unselectedIconColor = secondary,
            unselectedTextColor = secondary,
            indicatorColor = accent.copy(alpha = 0.14f),
        ),
    )
}

/**
 * The Radio (A7) and Settings (A8) tab contents are their own
 * milestones — a quiet empty shell until then, named so nobody
 * mistakes it for finished work.
 */
@Composable
private fun QuietTabPlaceholder(title: String, icon: ImageVector) {
    val theme = Theme.tokens()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.backgroundGradient),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (theme.isLight) Color(0xFF5B5570) else Color(0xFF9B94B4),
            )
            Text(
                text = title,
                fontSize = 15.sp,
                color = if (theme.isLight) Color(0xFF5B5570) else Color(0xFF9B94B4),
            )
        }
    }
}
