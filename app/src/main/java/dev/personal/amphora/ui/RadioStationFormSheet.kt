//
//  RadioStationFormSheet.kt
//  app
//
//  Port of Amphora/UI/RadioStationFormSheet.swift — add / edit a custom
//  radio station from a raw stream URL (radio R5). Presented from the
//  Radio tab's toolbar button (add) or a custom row's Edit menu (edit).
//  Validation is local — a non-empty name and an http(s) stream URL —
//  and the intent only fires once it passes; saving is synchronous (no
//  network), so the sheet dismisses on Save.
//
//  Port delta: iOS's NavigationStack + Form becomes the sheet-host
//  column the other sheets use — the Cancel / title / Save row is the
//  AddStationView header idiom. `URL(string:)` validation becomes
//  `java.net.URI` + scheme check (the same accept/reject envelope for
//  well-formed http(s) URLs).
//

package dev.personal.amphora.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import pandoracore.RadioStation

@Composable
fun RadioStationFormSheet(
    store: AmphoraStore,
    existing: RadioStation?,
    onDismiss: () -> Unit,
) {
    val theme = Theme.tokens()

    // nil = add mode; a station = edit mode. The fields prefill in
    // edit mode (iOS `.onAppear`).
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var url by remember(existing) { mutableStateOf(existing?.streamURL ?: "") }

    val trimmedName = name.trim()
    val isValid = trimmedName.isNotEmpty() && streamURLFrom(url) != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Inline title row: Cancel / title / Save (iOS toolbar items).
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.accent)
            }
            Text(
                text = if (existing == null) "Add Station" else "Edit Station",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor(theme),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f),
            )
            TextButton(
                onClick = {
                    val streamURL = streamURLFrom(url) ?: return@TextButton
                    if (existing == null) {
                        store.send(
                            AmphoraStore.Intent.AddRadioCustomStation(name = trimmedName, url = streamURL),
                        )
                    } else {
                        store.send(
                            AmphoraStore.Intent.UpdateRadioCustomStation(
                                station = existing,
                                name = trimmedName,
                                url = streamURL,
                            ),
                        )
                    }
                    onDismiss()
                },
                enabled = isValid,
            ) {
                Text("Save", color = if (isValid) theme.accent else theme.accent.copy(alpha = 0.4f))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        RadioSectionHeader("Station")

        TextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Name", color = secondaryColor(theme), fontSize = 16.sp) },
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
                .padding(vertical = 4.dp),
        )
        TextField(
            value = url,
            onValueChange = { url = it },
            placeholder = { Text("Stream URL", color = secondaryColor(theme), fontSize = 16.sp) },
            singleLine = true,
            // iOS: .textInputAutocapitalization(.never) +
            // .autocorrectionDisabled() + .keyboardType(.URL).
            keyboardOptions = KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                keyboardType = KeyboardType.Uri,
            ),
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
                .padding(vertical = 4.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Any direct stream URL works — most stations' MP3 or AAC streams " +
                "(http or https). Station websites list them, or open their .m3u/.pls " +
                "file and copy the link inside.",
            fontSize = 12.sp,
            color = secondaryColor(theme),
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

/**
 * Trims and validates the typed URL: it must parse and carry an http
 * or https scheme (what the player can stream). Returns the trimmed
 * form, or null when unusable.
 */
fun streamURLFrom(text: String): String? {
    val trimmed = text.trim()
    val scheme = try {
        java.net.URI(trimmed).scheme?.lowercase()
    } catch (_: Exception) {
        null
    }
    return if (scheme == "http" || scheme == "https") trimmed else null
}
