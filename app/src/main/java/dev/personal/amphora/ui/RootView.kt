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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.store.AuthPhase

@Composable
fun RootView(store: AmphoraStore) {
    val theme = Theme.tokens()

    LaunchedEffect(Unit) {
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
            AuthPhase.signedIn -> PlaceholderHome(store)
        }
    }
}

/** A2 placeholder — the iOS-faithful `MainTabView` + `StationsView`
 *  port replaces this in A3. */
@Composable
private fun PlaceholderHome(store: AmphoraStore) {
    val theme = Theme.tokens()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.backgroundGradient)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(64.dp))
        Text(
            text = "Amphora",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = if (theme.isLight) Color(0xFF151021) else Color(0xFFF2EFFB),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (store.isSubscriber) "Signed in · subscriber" else "Signed in",
            fontSize = 15.sp,
            color = if (theme.isLight) Color(0xFF5B5570) else Color(0xFF9B94B4),
        )
    }
}

private typealias FontWeight = androidx.compose.ui.text.font.FontWeight
