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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import dev.personal.amphora.store.AmphoraStore
import dev.personal.amphora.support.AppLog
import dev.personal.amphora.support.CrashReporter
import dev.personal.amphora.ui.RootView
import dev.personal.amphora.ui.ThemeAppearance
import dev.personal.amphora.ui.ThemeController

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
            // The port of RootView's `.preferredColorScheme(...)`: follow
            // the system for `.system`, else force the chosen family.
            // The app's own surfaces carry the theme tokens regardless —
            // the Material scheme colors the system components (dialogs,
            // menus, switches).
            val dark = when (ThemeController.selection.appearance) {
                ThemeAppearance.system -> isSystemInDarkTheme()
                else -> !ThemeController.selection.appearance.isLight
            }
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
            ) {
                val store = remember { AmphoraStore() }
                RootView(store)
            }
        }
    }
}
