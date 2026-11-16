//
//  LyricsPanel.kt
//  app
//
//  Port of Amphora/UI/LyricsPanel.swift — lyrics in the full player.
//  Synchronised lines scroll with playback and seek on tap; plain
//  lyrics scroll freely; the unavailable case offers a retry. All
//  state comes from `AmphoraStore` — the view holds no fetching or
//  business logic.
//
//  Port deltas (documented per AGENTS.md):
//  - SF Symbols mapping: checkmark.circle.fill → CheckCircle,
//    character.bubble → Translate, exclamationmark.triangle.fill →
//    Warning, text.quote → FormatQuote.
//  - `.bordered` + `.tint(accent)` becomes a rounded Button with a
//    matte accent wash (the app's button language).
//  - `ScrollViewReader.scrollTo(_, anchor: .center)` becomes
//    `animateScrollToItem` on the active line (the LazyColumn centers
//    approximately; the highlight itself is exact).
//

package dev.personal.amphora.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.store.ArtworkThemeController
import dev.personal.amphora.store.LyricsTranslationState
import dev.personal.amphora.support.rememberHaptics
import kotlinx.coroutines.delay
import pandoracore.Lyrics

@Composable
fun LyricsPanel(store: AmphoraStore) {
    val lyrics = store.lyrics
    val palette = ArtworkThemeController.colors
    when {
        lyrics != null && !lyrics.isEmpty -> {
            Column {
                TranslationBar(store, palette.accent.asColor())
                PanelContent(store, lyrics, palette.accent.asColor())
            }
        }
        store.isLoadingLyrics -> Loading()
        else -> Unavailable(store)
    }
}

/**
 * The translate control: a **Translate** button while there is
 * something to translate, a "Translating…" state while the request is
 * in flight, and the result once it is known — translated, already
 * English, or failed (with a retry).
 */
@Composable
private fun TranslationBar(store: AmphoraStore, accent: Color) {
    val theme = Theme.tokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val state = store.lyricsTranslationState) {
            is LyricsTranslationState.None -> {}
            is LyricsTranslationState.Translating -> {
                CircularProgressIndicator(
                    color = secondaryColor(theme),
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Translating…",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                )
            }
            is LyricsTranslationState.Translated -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Translated",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                )
            }
            is LyricsTranslationState.AlreadyEnglish -> {
                Icon(
                    imageVector = Icons.Rounded.Translate,
                    contentDescription = null,
                    tint = secondaryColor(theme),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Already in English",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                )
            }
            is LyricsTranslationState.Failed -> {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFD60A),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = state.message,
                    fontSize = 13.sp,
                    color = Color(0xFFFFD60A),
                    maxLines = 2,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        when (store.lyricsTranslationState) {
            is LyricsTranslationState.None ->
                TranslateButton(store, label = "Translate", accent = accent)
            is LyricsTranslationState.Failed ->
                TranslateButton(store, label = "Retry", accent = accent)
            else -> {}
        }
    }
}

@Composable
private fun TranslateButton(store: AmphoraStore, label: String, accent: Color) {
    val haptics = rememberHaptics()
    Button(
        onClick = {
            haptics.light()
            store.send(AmphoraStore.Intent.TranslateLyrics)
        },
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.16f),
            contentColor = accent,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.semantics {
            contentDescription = "Translate the lyrics into English"
        },
    ) {
        Icon(
            imageVector = Icons.Rounded.Translate,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(text = label, fontSize = 13.sp)
    }
}

@Composable
private fun PanelContent(store: AmphoraStore, lyrics: Lyrics, accent: Color) {
    when {
        lyrics.isSynced -> SyncedLyrics(store, lyrics, accent)
        !lyrics.plainText.isNullOrEmpty() ->
            PlainLyrics(plain = lyrics.plainText!!, translation = lyrics.plainTranslation)
        lyrics.instrumental -> Instrumental()
        else -> Unavailable(store)
    }
}

/**
 * Un-synchronised lyrics: the original words, with the English
 * translation underneath once it has been fetched.
 */
@Composable
private fun PlainLyrics(plain: String, translation: String?) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = plain,
            fontSize = 20.sp,
            color = secondaryColor(theme),
            lineHeight = 26.sp,
        )
        if (!translation.isNullOrEmpty()) {
            androidx.compose.material3.HorizontalDivider(
                color = secondaryColor(theme).copy(alpha = 0.3f),
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Text(
                text = translation,
                fontSize = 20.sp,
                color = labelColor(theme),
                lineHeight = 26.sp,
            )
        }
    }
}

/**
 * Synchronised lines: the active line is highlighted and kept
 * centred; tapping a line seeks to its start.
 */
@Composable
private fun SyncedLyrics(store: AmphoraStore, lyrics: Lyrics, accent: Color) {
    val theme = Theme.tokens()
    val haptics = rememberHaptics()
    val elapsed by store.player.elapsed.collectAsState()
    val listState = rememberLazyListState()
    // Computed here — reading `elapsed` at this level is what
    // re-renders the panel as playback advances (the iOS comment's
    // reason: inside the lazy builder it would be out of scope for the
    // onChange below).
    val activeIndex = lyrics.activeLineIndex(at = elapsed)

    LaunchedEffect(activeIndex) {
        if (activeIndex != null) {
            listState.animateScrollToItem(activeIndex)
        }
    }
    LaunchedEffect(lyrics) {
        // Arriving mid-track: land on the current line. A short delay
        // lets the list lay out first.
        delay(150)
        val index = lyrics.activeLineIndex(at = elapsed)
        if (index != null) {
            listState.scrollToItem(index)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 100.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(lyrics.lines, key = { _, line -> line.id }) { index, line ->
            val isActive = index == activeIndex
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.light()
                        store.player.seek(to = line.timestamp)
                    }
                    .semantics {
                        contentDescription = "Double-tap to jump here"
                    },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!line.romanization.isNullOrEmpty()) {
                    // Non-Latin-script lyrics: the original script stays
                    // as a reference line, the romanization is the
                    // reading line the user sings along with
                    // (accent-tinted), and the English translation sits
                    // beneath both.
                    Text(
                        text = line.text,
                        fontSize = 15.sp,
                        color = if (isActive) {
                            secondaryColor(theme)
                        } else {
                            secondaryColor(theme).copy(alpha = 0.6f)
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = line.romanization!!,
                        fontSize = 20.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isActive) accent else accent.copy(alpha = 0.7f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!line.translation.isNullOrEmpty()) {
                        Text(
                            text = line.translation!!,
                            fontSize = 15.sp,
                            color = if (isActive) {
                                secondaryColor(theme)
                            } else {
                                secondaryColor(theme).copy(alpha = 0.6f)
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        text = line.text,
                        fontSize = 20.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isActive) labelColor(theme) else secondaryColor(theme),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!line.translation.isNullOrEmpty()) {
                        Text(
                            text = line.translation!!,
                            fontSize = 15.sp,
                            color = if (isActive) {
                                secondaryColor(theme)
                            } else {
                                secondaryColor(theme).copy(alpha = 0.6f)
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = secondaryColor(theme))
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Looking up lyrics…",
            fontSize = 13.sp,
            color = secondaryColor(theme).copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun Instrumental() {
    val theme = Theme.tokens()
    UnavailableFrame(
        icon = {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = secondaryColor(theme))
        },
        title = "Instrumental",
        description = "This track has no words.",
        action = null,
    )
}

@Composable
private fun Unavailable(store: AmphoraStore) {
    val theme = Theme.tokens()
    UnavailableFrame(
        icon = {
            Icon(
                Icons.Rounded.FormatQuote,
                contentDescription = null,
                tint = secondaryColor(theme),
            )
        },
        title = "No lyrics",
        description = "Couldn't find lyrics for this track.",
        action = {
            Button(
                onClick = { store.send(AmphoraStore.Intent.RefreshLyrics) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Theme.tokens().surface,
                    contentColor = labelColor(theme),
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text("Retry", fontSize = 14.sp)
            }
        },
    )
}

@Composable
private fun UnavailableFrame(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    action: (@Composable () -> Unit)?,
) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
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
        action?.let {
            Spacer(modifier = Modifier.height(16.dp))
            it()
        }
    }
}
