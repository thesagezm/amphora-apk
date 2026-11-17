//
//  MainActivity.kt
//  app
//
//  Port of AmphoraApp + RootView wiring: the process installs its
//  context, the store is created once (app lifetime), and RootView
//  switches on the auth phase. The crash handler installs with
//  CrashReporter in A8 (iOS does it in AmphoraApp.init).
//

package dev.personal.amphora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.support.AppLog
import dev.personal.amphora.support.CrashReporter
import dev.personal.amphora.ui.RootView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmphoraContextProvider.install(this)
        // The crash handler installs with the process context (iOS does
        // it in AmphoraApp.init) — the previous session's report must
        // be captured before anything can reset the file.
        CrashReporter.install()
        enableEdgeToEdge()
        val version = "0.1.0"
        AppLog.info("Amphora launched, app $version build 1")
        setContent {
            // The app's own surfaces carry the theme tokens; the system
            // scheme follows them via the controller (full theming port
            // completes in A8).
            MaterialTheme(
                colorScheme = darkColorScheme(),
            ) {
                val store = remember { AmphoraStore() }
                RootView(store)
            }
        }
    }
}
