//
//  SettingsView.kt
//  app
//
//  Port of Amphora/UI/SettingsView.swift — the Settings tab. Everything
//  the app exposes lives here: Appearance (background theme + accent),
//  Last.fm scrobbling, diagnostics, about & acknowledgements, and
//  signing out. The scrobble engine lives in `Scrobbler`; this view
//  dispatches intents only.
//
//  Port deltas (documented per AGENTS.md):
//  - The NavigationStack pushes (DiagnosticsView / AboutView) become a
//    local pushed state + BackHandler — the RadioView drill-down
//    pattern.
//  - The background `Picker(.menu)` becomes a row with a
//    DropdownMenu; the sign-out `confirmationDialog` becomes an
//    AlertDialog.
//  - SF Symbols map to Material glyphs (e.square → Explicit,
//    person.crop.circle → AccountCircle, clock.arrow.circlepath →
//    History, photo.stack → PhotoLibrary, stethoscope → BugReport,
//    wrench.and.screwdriver → Build, questionmark.circle → Help,
//    info.circle → Info, rectangle…and.arrow.right → Logout,
//    safari → Language, dot.radiowaves → NetworkCheck).
//  - `Link` → LocalUriHandler.openUri.
//

package dev.personal.amphora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Explicit
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.support.rememberHaptics

@Composable
fun SettingsView(store: AmphoraStore) {
    val theme = Theme.tokens()

    var isConfirmingSignOut by remember { mutableStateOf(false) }
    var isConfirmingClearHistory by remember { mutableStateOf(false) }
    var pushed by remember { mutableStateOf<SettingsPush?>(null) }

    BackHandler(enabled = pushed != null) { pushed = null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        when (val target = pushed) {
            null -> {
                // iOS: `.navigationTitle("Settings")` (large title).
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    ) {
                        Text(
                            text = "Settings",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = labelColor(theme),
                        )
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item(key = "appearance-header") {
                            SectionHeader("Appearance", Icons.Rounded.Palette)
                        }
                        item(key = "appearance") {
                            AppearanceSection()
                        }
                        item(key = "appearance-footer") {
                            SectionFooter(
                                "Applies everywhere immediately. " +
                                    "Gradients follow the accent you pick.",
                            )
                        }
                        item(key = "lastfm-header") {
                            SectionHeader("Last.fm", Icons.Rounded.NetworkCheck)
                        }
                        item(key = "lastfm") {
                            LastFmSettings(store)
                        }
                        item(key = "lastfm-footer") {
                            SectionFooter(
                                "Scrobbles what you play to your Last.fm profile. " +
                                    "Tracks shorter than 30 seconds never scrobble; " +
                                    "a track counts once you've played past half of it " +
                                    "or four minutes — whichever comes first.",
                            )
                        }
                        item(key = "account-header") {
                            SectionHeader("Account", Icons.Rounded.AccountCircle)
                        }
                        item(key = "account") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .amphoraCardRow(theme),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Explicit,
                                    contentDescription = null,
                                    tint = labelColor(theme),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.size(12.dp))
                                Text(
                                    text = "Explicit Content",
                                    fontSize = 16.sp,
                                    color = labelColor(theme),
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = if (store.isExplicitContentFilterEnabled) {
                                        "Filtered"
                                    } else {
                                        "Allowed"
                                    },
                                    fontSize = 12.sp,
                                    color = secondaryColor(theme),
                                )
                            }
                        }
                        item(key = "account-footer") {
                            SectionFooter(
                                "Pandora's explicit-content filter is an account setting — " +
                                    "it applies to every station. Change it on pandora.com.",
                            )
                        }
                        item(key = "library-header") {
                            SectionHeader("Library", Icons.Rounded.PhotoLibrary)
                        }
                        item(key = "library-history") {
                            HistoryRow(store) { isConfirmingClearHistory = true }
                        }
                        item(key = "library-cache") {
                            ArtworkCacheRow(store)
                        }
                        item(key = "library-footer") {
                            SectionFooter(
                                "Both live on this device only — clearing them " +
                                    "never changes your Pandora account.",
                            )
                        }
                        item(key = "support-header") {
                            SectionHeader("Support", Icons.Rounded.Build)
                        }
                        item(key = "support") {
                            PushRow(
                                title = "Diagnostics & Logs",
                                icon = Icons.Rounded.BugReport,
                                onClick = { pushed = SettingsPush.Diagnostics },
                            )
                        }
                        item(key = "about-header") {
                            SectionHeader("About", Icons.Rounded.Help)
                        }
                        item(key = "about") {
                            PushRow(
                                title = "About Amphora",
                                icon = Icons.Rounded.Info,
                                onClick = { pushed = SettingsPush.About },
                            )
                        }
                        item(key = "signout") {
                            DestructiveRow(
                                title = "Sign Out of Pandora",
                                icon = Icons.AutoMirrored.Rounded.Logout,
                                centered = true,
                                onClick = { isConfirmingSignOut = true },
                            )
                        }
                    }
                }
            }
            SettingsPush.Diagnostics -> {
                PushedHeader(title = "Diagnostics", onBack = { pushed = null })
                DiagnosticsView(store)
            }
            SettingsPush.About -> {
                PushedHeader(title = "About", onBack = { pushed = null })
                AboutView()
            }
        }
    }

    if (isConfirmingClearHistory) {
        val theme = Theme.tokens()
        AlertDialog(
            onDismissRequest = { isConfirmingClearHistory = false },
            containerColor = theme.surface,
            title = { Text("Clear play history?", color = labelColor(theme)) },
            text = {
                Text(
                    "The recently played list on this device is emptied. " +
                        "Your Pandora account and its listening data are not affected.",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmingClearHistory = false
                        store.send(AmphoraStore.Intent.ClearHistory)
                    },
                ) {
                    Text("Clear History", color = Color(0xFFFF5A5A))
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingClearHistory = false }) {
                    Text("Cancel", color = theme.accent)
                }
            },
        )
    }
    if (isConfirmingSignOut) {
        val theme = Theme.tokens()
        AlertDialog(
            onDismissRequest = { isConfirmingSignOut = false },
            containerColor = theme.surface,
            title = { Text("Sign out of Pandora?", color = labelColor(theme)) },
            text = {
                Text(
                    "Your stations and history stay on Pandora's servers; " +
                        "playback stops and you'll sign in again next launch.",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmingSignOut = false
                        store.send(AmphoraStore.Intent.SignOutPandora)
                    },
                ) {
                    Text("Sign Out", color = Color(0xFFFF5A5A))
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingSignOut = false }) {
                    Text("Cancel", color = theme.accent)
                }
            },
        )
    }
}

/** What the tab has pushed onto its local stack. */
private sealed class SettingsPush {
    data object Diagnostics : SettingsPush()
    data object About : SettingsPush()
}

// MARK: Appearance

@Composable
private fun AppearanceSection() {
    val theme = Theme.tokens()

    Column {
        // The background picker (iOS `Picker("Background", .menu)`).
        Box {
            var pickerOpen by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .amphoraCardRow(theme)
                    .clickable { pickerOpen = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Background",
                    fontSize = 16.sp,
                    color = labelColor(theme),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = ThemeController.selection.appearance.displayName,
                    fontSize = 14.sp,
                    color = secondaryColor(theme),
                )
            }
            DropdownMenu(
                expanded = pickerOpen,
                onDismissRequest = { pickerOpen = false },
                containerColor = theme.surface,
            ) {
                ThemeAppearance.entries.forEach { appearance ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                appearance.displayName,
                                color = if (ThemeController.selection.appearance == appearance) {
                                    theme.accentText
                                } else {
                                    labelColor(theme)
                                },
                            )
                        },
                        onClick = {
                            pickerOpen = false
                            ThemeController.setAppearance(appearance)
                        },
                    )
                }
            }
        }
        // The accent row: the five swatches.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .amphoraCardRow(theme)
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Accent",
                fontSize = 16.sp,
                color = labelColor(theme),
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeAccent.entries.forEach { accent ->
                    AccentSwatch(
                        accent = accent,
                        isSelected = ThemeController.selection.accent == accent,
                        onSelect = { ThemeController.setAccent(accent) },
                    )
                }
            }
        }
    }
}

/** One accent color chip: a gradient circle; the selected one wears a
 *  soft ring and a checkmark in the accent's legible glyph color. */
@Composable
private fun AccentSwatch(
    accent: ThemeAccent,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable {
                haptics.light()
                onSelect()
            }
            .semantics {
                contentDescription = accent.displayName
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(accent.palette.gradient, CircleShape),
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .border(
                        width = 1.5.dp,
                        color = labelColor(theme).copy(alpha = 0.85f),
                        shape = CircleShape,
                    ),
            )
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = accent.palette.glyph,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

// MARK: Last.fm

/**
 * The Last.fm connect/sign-out block (previously its own sheet).
 * Dispatches the same three intents.
 */
@Composable
private fun LastFmSettings(store: AmphoraStore) {
    val scrobbler = store.scrobbler
    val theme = Theme.tokens()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column {
        when (val auth = scrobbler.authState) {
            is dev.personal.amphora.player.Scrobbler.AuthState.SignedIn -> {
                SignedInRows(store, auth.username)
            }
            is dev.personal.amphora.player.Scrobbler.AuthState.NeedsAuth -> {
                Text(
                    text = auth.message,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFFD60A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .amphoraCardRow(theme),
                )
                CredentialsRows(
                    store = store,
                    username = username,
                    onUsernameChange = {
                        username = it
                        store.scrobbler.clearAuthError()
                    },
                    password = password,
                    onPasswordChange = {
                        password = it
                        store.scrobbler.clearAuthError()
                    },
                )
            }
            dev.personal.amphora.player.Scrobbler.AuthState.SignedOut -> {
                CredentialsRows(
                    store = store,
                    username = username,
                    onUsernameChange = {
                        username = it
                        store.scrobbler.clearAuthError()
                    },
                    password = password,
                    onPasswordChange = {
                        password = it
                        store.scrobbler.clearAuthError()
                    },
                )
            }
        }
    }
}

@Composable
private fun SignedInRows(store: AmphoraStore, name: String) {
    val theme = Theme.tokens()
    val uriHandler = LocalUriHandler.current

    Column {
        // The master switch (iOS `Toggle("Scrobble to Last.fm")`).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .amphoraCardRow(theme),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Scrobble to Last.fm",
                fontSize = 16.sp,
                color = labelColor(theme),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = store.scrobbler.isEnabled,
                onCheckedChange = { enabled ->
                    store.send(AmphoraStore.Intent.SetScrobblingEnabled(enabled))
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = theme.accent,
                    checkedThumbColor = theme.accentGlyph,
                ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .amphoraCardRow(theme),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Signed in as $name",
                fontSize = 12.sp,
                color = secondaryColor(theme),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = theme.accentText,
                modifier = Modifier.size(16.dp),
            )
        }
        store.scrobbler.lastActivity?.let { activity ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .amphoraCardRow(theme),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (store.scrobbler.lastActivityIsError) {
                        Icons.Rounded.Warning
                    } else {
                        Icons.Rounded.CheckCircle
                    },
                    contentDescription = null,
                    tint = if (store.scrobbler.lastActivityIsError) {
                        Color(0xFFFFD60A)
                    } else {
                        theme.accentText
                    },
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = activity,
                    fontSize = 12.sp,
                    color = if (store.scrobbler.lastActivityIsError) {
                        Color(0xFFFFD60A)
                    } else {
                        secondaryColor(theme)
                    },
                )
            }
        }
        // "Open Last.fm Profile" (iOS `Link`).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .amphoraCardRow(theme)
                .clickable { uriHandler.openUri("https://www.last.fm/user/$name") },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = labelColor(theme),
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Open Last.fm Profile",
                fontSize = 16.sp,
                color = labelColor(theme),
            )
        }
        DestructiveRow(
            title = "Sign Out of Last.fm",
            icon = null,
            centered = false,
            onClick = { store.send(AmphoraStore.Intent.SignOutLastFm) },
        )
    }
}

@Composable
private fun CredentialsRows(
    store: AmphoraStore,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
) {
    val theme = Theme.tokens()
    val scrobbler = store.scrobbler

    Column {
        TextField(
            value = username,
            onValueChange = onUsernameChange,
            placeholder = {
                Text("Last.fm username or email", color = secondaryColor(theme), fontSize = 16.sp)
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
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                keyboardType = KeyboardType.Email,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextField(
            value = password,
            onValueChange = onPasswordChange,
            placeholder = {
                Text("Last.fm password", color = secondaryColor(theme), fontSize = 16.sp)
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(Theme.radiusM),
            textStyle = TextStyle(fontSize = 16.sp, color = labelColor(theme)),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = theme.surfaceHighlight,
                unfocusedContainerColor = theme.surfaceHighlight,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = theme.accent,
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // The Connect button (spinner while the round trip runs).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .amphoraCardRow(theme)
                .clickable(enabled = !scrobbler.isAuthenticating) {
                    store.send(
                        AmphoraStore.Intent.AuthenticateLastFm(
                            username = username,
                            password = password,
                        ),
                    )
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (scrobbler.isAuthenticating) {
                CircularProgressIndicator(
                    color = secondaryColor(theme),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = "Connect",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = labelColor(theme),
                    textAlign = TextAlign.Center,
                )
            }
        }
        scrobbler.authError?.let { error ->
            Text(
                text = error,
                fontSize = 12.sp,
                color = Color(0xFFFFD60A),
                modifier = Modifier
                    .fillMaxWidth()
                    .amphoraCardRow(theme),
            )
        }
    }
}

// MARK: Library

@Composable
private fun HistoryRow(store: AmphoraStore, onClear: () -> Unit) {
    val theme = Theme.tokens()
    val count = store.history.count
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme)
            .clickable(enabled = count > 0, onClick = onClear),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.History,
            contentDescription = null,
            tint = if (count > 0) Color(0xFFFF5A5A) else secondaryColor(theme),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Clear Play History",
            fontSize = 16.sp,
            color = if (count > 0) labelColor(theme) else secondaryColor(theme),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (count == 0) {
                "Empty"
            } else {
                "$count ${if (count == 1) "play" else "plays"}"
            },
            fontSize = 12.sp,
            color = secondaryColor(theme),
        )
    }
}

@Composable
private fun ArtworkCacheRow(store: AmphoraStore) {
    val theme = Theme.tokens()
    val bytes = store.artworkCacheBytes
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme)
            .clickable(enabled = bytes > 0) {
                store.send(AmphoraStore.Intent.ClearArtworkCache)
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            tint = if (bytes > 0) labelColor(theme) else secondaryColor(theme),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Clear Artwork Cache",
            fontSize = 16.sp,
            color = if (bytes > 0) labelColor(theme) else secondaryColor(theme),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (bytes == 0L) {
                "Empty"
            } else {
                byteSummary(bytes)
            },
            fontSize = 12.sp,
            color = secondaryColor(theme),
        )
    }
}

/** The ByteCountFormatter port — "0 KB", "1.2 MB", "340 MB" etc. */
private fun byteSummary(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> {
            val rounded = (mb * 10).toInt() / 10.0
            "${if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()} MB"
        }
        kb >= 1.0 -> "${kb.toInt()} KB"
        else -> "$bytes bytes"
    }
}

// MARK: Rows + headers

/** A row that pushes a sub-screen (iOS `NavigationLink`). */
@Composable
private fun PushRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val theme = Theme.tokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = labelColor(theme),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = title,
            fontSize = 16.sp,
            color = labelColor(theme),
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(16.dp),
        )
    }
}

/** A destructive button row (iOS `Button(role: .destructive)`). */
@Composable
private fun DestructiveRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    centered: Boolean,
    onClick: () -> Unit,
) {
    val theme = Theme.tokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) {
            Arrangement.Center
        } else {
            Arrangement.spacedBy(12.dp)
        },
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = Color(0xFFFF5A5A),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            fontSize = 16.sp,
            color = Color(0xFFFF5A5A),
        )
    }
}

/** The section header with its glyph (iOS `sectionHeader(_:icon:)`). */
@Composable
private fun SectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val theme = Theme.tokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = secondaryColor(theme),
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = secondaryColor(theme),
        )
    }
}

/** The section footer copy (iOS Section footer). */
@Composable
private fun SectionFooter(text: String) {
    val theme = Theme.tokens()
    Text(
        text = text,
        fontSize = 12.sp,
        color = secondaryColor(theme),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
    )
}
