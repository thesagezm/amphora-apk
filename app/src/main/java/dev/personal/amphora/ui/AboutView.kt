//
//  AboutView.kt
//  app
//
//  Port of Amphora/UI/AboutView.swift — About Amphora: what the app
//  is, what it's built on, and the people whose work it stands on —
//  acknowledged by name, as they deserve.
//
//  Port delta: `waveform.circle.fill` in the brand gradient becomes a
//  brand-gradient circle behind a white glyph (Icon tints take a
//  color, not a brush); the version reads PackageManager.
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutView() {
    val theme = Theme.tokens()
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        // MARK: Intro
        item(key = "intro") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(theme.brandGradient, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = theme.accentGlyph,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = "Amphora",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor(theme),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "A personal, unofficial Pandora client. " +
                        "Not affiliated with, endorsed by, or connected to " +
                        "Pandora Media, LLC.",
                    fontSize = 12.sp,
                    color = secondaryColor(theme),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        item(key = "ack-header") { RadioSectionHeader("Acknowledgements") }
        item(key = "ack-rows") {
            Column {
                AcknowledgementRow(
                    name = "Pithos",
                    detail = "The Linux Pandora client whose feature set Amphora mirrors — " +
                        "and whose scrobble rule we follow.",
                )
                AcknowledgementRow(
                    name = "pydora, libpiano & pianobar",
                    detail = "Open-source Pandora clients whose reverse-engineered " +
                        "protocol notes made this app possible.",
                )
                AcknowledgementRow(
                    name = "CryptoSwift",
                    detail = "Blowfish-ECB encryption for Pandora's legacy login. " +
                        "© Marcin Krzyżanowski, Apache 2.0.",
                )
                AcknowledgementRow(
                    name = "Last.fm",
                    detail = "Scrobbling via their public API (auth.getMobileSession, " +
                        "track.updateNowPlaying, track.scrobble).",
                )
                AcknowledgementRow(
                    name = "LRCLIB",
                    detail = "Synced lyrics, free of charge, at lrclib.net.",
                )
                AcknowledgementRow(
                    name = "Google Translate",
                    detail = "On-device-requested translations for non-English lyrics.",
                )
            }
        }
        item(key = "version") {
            val version = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                "${info.versionName ?: "?"} (${info.versionCode})"
            } catch (_: Exception) {
                "? (?)"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp)
                    .background(theme.surface, Theme.shapeM())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Version",
                    fontSize = 12.sp,
                    color = labelColor(theme),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = version,
                    fontSize = 12.sp,
                    color = secondaryColor(theme),
                )
            }
        }
    }
}

@Composable
private fun AcknowledgementRow(name: String, detail: String) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .amphoraCardRow(theme),
    ) {
        Text(
            text = name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor(theme),
        )
        Spacer(modifier = Modifier.size(3.dp))
        Text(
            text = detail,
            fontSize = 12.sp,
            color = secondaryColor(theme),
        )
    }
}
