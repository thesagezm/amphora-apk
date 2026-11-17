//
//  DiagnosticsView.kt
//  app
//
//  Port of Amphora/UI/DiagnosticsView.swift — in-app diagnostics: the
//  recent app log and the previous session's crash report, with
//  one-tap Copy/Share so a crash can be pasted into a bug report. The
//  app ships without a debugger, so this screen is the only window
//  into what happened on the device. Presented from Settings →
//  Diagnostics & Logs.
//
//  Port deltas (documented per AGENTS.md):
//  - `ShareLink` → an ACTION_SEND chooser intent.
//  - The device summary reads Build.* + PackageManager (no utsname);
//    the audio-route line is omitted — the route picker is out of
//    scope, so the port cannot claim a route it does not track.
//  - `UIPasteboard` → the Compose clipboard.
//

package dev.personal.amphora.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.support.AppLog
import dev.personal.amphora.support.CrashReporter
import kotlinx.coroutines.delay

@Composable
fun DiagnosticsView(store: AmphoraStore) {
    val theme = Theme.tokens()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var logText by remember { mutableStateOf("") }
    var crashText by remember { mutableStateOf<String?>(null) }
    var didCopy by remember { mutableStateOf(false) }

    // `.task { refresh() }` — flush the file mirror to disk and re-read
    // everything, including the previous session's crash report.
    LaunchedEffect(Unit) {
        AppLog.flushNow()
        logText = AppLog.text()
        crashText = CrashReporter.previousCrashReport
    }

    val logLines = if (logText.isEmpty()) 0 else logText.split("\n").size
    val reportText = buildString {
        append(deviceSummary(context))
        append("\n\n")
        crashText?.let {
            append("--- last crash ---\n$it\n\n")
        }
        append("--- log ---\n$logText")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        // The toolbar (iOS: leading Copy, trailing Share).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(reportText))
                    didCopy = true
                },
                enabled = reportText.isNotBlank(),
                modifier = Modifier.semantics {
                    contentDescription = "Copy diagnostics to the clipboard"
                },
            ) {
                Icon(
                    imageVector = if (didCopy) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "Copy",
                    tint = theme.accent,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { shareReport(context, reportText) },
                enabled = reportText.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share",
                    tint = theme.accent,
                )
            }
        }
        if (logText.isEmpty() && crashText == null) {
            // iOS ContentUnavailableView — nothing recorded yet.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = secondaryColor(theme),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Text(
                    text = "Nothing recorded yet",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor(theme),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Reproduce the problem, then reopen this screen.",
                    fontSize = 13.sp,
                    color = secondaryColor(theme),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                crashText?.let { crash ->
                    DiagnosticsSection("Last crash") {
                        Text(
                            text = crash,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFF5A5A).copy(alpha = 0.9f),
                        )
                    }
                }
                DiagnosticsSection("Device") {
                    Text(
                        text = deviceSummary(context),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = secondaryColor(theme),
                    )
                }
                DiagnosticsSection("Log (most recent $logLines lines)") {
                    Text(
                        text = logText,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = labelColor(theme),
                    )
                }
            }
        }
    }

    // The copy confirmation resets after two seconds (iOS's Task.sleep).
    LaunchedEffect(didCopy) {
        if (didCopy) {
            delay(2000)
            didCopy = false
        }
    }
}

@Composable
private fun DiagnosticsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 20.dp),
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = secondaryColor(theme),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

/** The device summary (iOS `deviceSummary`, the `utsname` + bundle
 *  info port). */
private fun deviceSummary(context: android.content.Context): String {
    val version = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "?"} (${info.versionCode})"
    } catch (_: Exception) {
        "? (?)"
    }
    return buildString {
        append("device: ${android.os.Build.MODEL}")
        append("\nos: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        append("\napp: $version")
    }
}

/** The ShareLink port: an ACTION_SEND chooser over plain text. */
private fun shareReport(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
