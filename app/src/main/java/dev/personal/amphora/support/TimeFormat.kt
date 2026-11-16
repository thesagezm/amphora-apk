//
//  TimeFormat.kt
//  app
//
//  Port of Amphora/Support/TimeFormat.swift — the scrubber's and the
//  mini player's time labels ("1:23", "1:02:03").
//

package dev.personal.amphora.support

import java.util.Locale

object TimeFormat {
    fun string(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return "0:00"
        val total = seconds.toInt()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        }
        return String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}
