//
//  CrashReporter.kt
//  app
//
//  Port of Amphora/Support/CrashReporter.swift — best-effort crash
//  capture. A device crash ("the app disappears") was impossible to
//  diagnose with nothing to paste; the handler writes what it can to
//  `filesDir/last-crash.txt`:
//
//  - The **uncaught-exception handler** records the exception class,
//    message and stack with regular file I/O, appends the log tail
//    (`AppLog`'s buffer — the iOS port mirrored 24 lines into raw
//    memory because signal handlers may only touch signal-safe memory;
//    an uncaught-exception handler runs as regular code and can read
//    the buffer directly), then chains to the previous handler so the
//    system still records its own report.
//  - **Hard native kills** (SIGSEGV from the runtime itself, kernel
//    kills) never reach a Java handler — the system tombstone is the
//    only record, exactly as the iOS signal path's re-raise leaves the
//    system crash log as the authoritative record.
//
//  The next launch reads the file back before it is reset, and the
//  Diagnostics screen shows it — that is how a crash gets pasted into
//  a bug report.
//

package dev.personal.amphora.support

import dev.personal.amphora.AmphoraContextProvider
import java.io.File

object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"

    /** The previous session's crash report, captured at install before
     *  the file is reset for this run. Shown by the Diagnostics
     *  screen. */
    var previousCrashReport: String? = null
        private set

    @Volatile
    private var installed = false

    /** Installs the handler once. Call from `MainActivity.onCreate`. */
    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }

        val file = crashFile()

        // Read the previous crash before the file is reset for this run.
        previousCrashReport = try {
            file.takeIf { it.exists() }?.readText()
        } catch (_: Exception) {
            null
        }

        // The file is reset for this session (iOS O_TRUNC).
        try {
            file.parentFile?.mkdirs()
            file.writeText("")
        } catch (_: Exception) {
            // Best-effort — the handler still writes its own copy.
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeReport(file, thread, throwable)
            // The system still records its own crash report and the
            // process dies normally.
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Drops the stored report (the user dismissed it in Diagnostics). */
    fun clearLastCrashReport() {
        previousCrashReport = null
        try {
            crashFile().writeText("")
        } catch (_: Exception) {
            // Best-effort.
        }
    }

    private fun crashFile(): File =
        File(AmphoraContextProvider.context.filesDir, FILE_NAME)

    /** The crash path — regular code, file I/O allowed. */
    private fun writeReport(file: File, thread: Thread, throwable: Throwable) {
        val text = buildString {
            append("Amphora crashed: uncaught exception\n")
            append("thread: ${thread.name}\n")
            throwable.message?.let { append("reason: $it\n") }
            append(throwable.stackTraceToString())
            append("\n")
            // The log tail only needs the most recent lines (the iOS
            // crashTail mirror, read from the buffer at crash time).
            val tail = AppLog.recentLines.takeLast(24)
            if (tail.isNotEmpty()) {
                append("--- log tail ---\n")
                append(tail.joinToString("\n"))
                append("\n")
            }
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
        } catch (_: Exception) {
            // A crash-time write is best-effort by definition.
        }
    }
}
