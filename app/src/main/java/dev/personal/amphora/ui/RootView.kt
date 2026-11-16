//
//  RootView.kt
//  app
//
//  Port of Amphora/App/RootView.swift — switches on the auth phase:
//  restoring → a quiet spinner, signed out / signing in → LoginView,
//  signed in → MainTabView (stations arrive in A3; a placeholder until
//  then). Warm-launch restore is idempotent, so re-entering after a
//  background relaunch is a no-op.
//

package dev.personal.amphora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.store.ArtworkThemeController
import dev.personal.amphora.store.AuthPhase
import dev.personal.amphora.support.AppLog

@Composable
fun RootView(store: AmphoraStore) {
    val theme = Theme.tokens()

    // Android 13+ gates notifications behind a runtime permission; the
    // media notification lives there, so the ask goes out once the user
    // is actually signed in (a music app asking before showing a single
    // station would be noise). The system itself no-ops the request
    // when already granted or permanently denied, so a once-per-session
    // ask never nags. iOS has no equivalent — that is the port delta.
    var notificationsAskedThisSession by rememberSaveable { mutableStateOf(false) }
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAskedThisSession = true
        AppLog.info(
            "notification permission " +
                (if (granted) "granted" else "denied"),
            AppLog.Category.app,
        )
    }

    LaunchedEffect(store.authPhase) {
        if (store.authPhase == AuthPhase.signedIn &&
            !notificationsAskedThisSession &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            notificationsAskedThisSession = true
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        // The ambient artwork theme attaches to the player once (the
        // port of RootView's ArtworkThemeController attach).
        ArtworkThemeController.attach(store.player)
        // Warm-launch restore — idempotent, so re-entering after a
        // background relaunch is a no-op.
        store.send(AmphoraStore.Intent.RestoreSession)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        when (store.authPhase) {
            AuthPhase.restoring -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = theme.accentText)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Restoring session…",
                        fontSize = 13.sp,
                        color = if (theme.isLight) Color(0xFF5B5570) else Color(0xFF9B94B4),
                    )
                }
            }
            AuthPhase.signedOut, AuthPhase.signingIn -> LoginView(store)
            AuthPhase.signedIn -> MainTabView(store)
        }
    }
}
