//
//  Haptics.kt
//  app
//
//  Port of Amphora/Support/Haptics.swift — lightweight haptics for the
//  design spec's "haptics on rating/skip" (and play/pause, bookmarks).
//  Callers are Composables on the main thread; the view handle comes
//  from LocalView via rememberHaptics().
//
//  Port delta: UIKit's impact generators map to platform constants —
//  `.light` → CLOCK_TICK, `.medium` → VIRTUAL_KEY.
//

package dev.personal.amphora.support

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

class Haptics(private val view: View) {
    /** Small taps: play/pause, bookmarks, ratings. */
    fun light() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Confirmed actions: skipping a track. */
    fun medium() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
