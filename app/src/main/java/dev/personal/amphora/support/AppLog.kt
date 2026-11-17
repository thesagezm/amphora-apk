//
//  AppLog.kt
//  app
//
//  Port of Amphora/Support/AppLog.swift — process-wide logging:
//
//  - **logcat** — the Android system log (Console.app's counterpart),
//    one tag per category.
//  - **A bounded ring buffer** — the most recent lines in memory.
//  - **A log file** — the buffer mirrored to filesDir on a short
//    debounce, so a hard crash still leaves the last seconds of lines
//    on disk for the in-app Diagnostics screen.
//  - **The crash tail** — the handler can read the same buffer at
//    crash time (see CrashReporter; the iOS raw-memory dance exists
//    because signal handlers may only touch signal-safe memory — an
//    uncaught-exception handler runs as regular code).
//
//  Redaction is part of the contract: nothing secret reaches the log.
//  URLs are reduced to scheme + host + path — Pandora's query strings
//  carry signed stream/image tokens; credentials and auth tokens are
//  never logged.
//
//  Port delta: the A6.1-era "no debug level" gap is closed — `debug`
//  appends to the buffer like iOS (`D` lines reach the Diagnostics
//  screen; logcat debug is not persisted by default either).
//

package dev.personal.amphora.support

import android.util.Log
import dev.personal.amphora.AmphoraContextProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AppLog {
    enum class Category(val raw: String) {
        app("app"),
        playback("playback"),
        artwork("artwork"),
        network("network"),
        lyrics("lyrics"),
    }

    private const val SUBSYSTEM = "dev.personal.amphora"
    private const val BUFFER_LIMIT = 400
    private const val FILE_NAME = "amphora.log"

    /** Seconds since launch, used to prefix each buffered line — after a
     *  crash, "crashed 4.2s after play" is exactly what we need. */
    private val startedAt = Date()

    private val lock = Any()
    private val buffer = mutableListOf<String>()
    private var dirty = false

    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The most recent lines in memory (the Diagnostics screen reads
     *  this; the crash handler reads it at crash time). */
    val recentLines: List<String>
        get() = synchronized(lock) { buffer.toList() }

    fun info(message: String, category: Category = Category.app) {
        Log.i("$SUBSYSTEM.${category.raw}", message)
        append("I", category, message)
    }

    fun error(message: String, category: Category = Category.app) {
        Log.e("$SUBSYSTEM.${category.raw}", message)
        append("E", category, message)
    }

    /** A verbose line — kept in the buffer for the Diagnostics screen,
     *  `debug` in the system log (not persisted there by default). */
    fun debug(message: String, category: Category = Category.app) {
        Log.d("$SUBSYSTEM.${category.raw}", message)
        append("D", category, message)
    }

    /** The buffered log as one block of text (for copy/share). */
    fun text(): String = synchronized(lock) {
        buffer.joinToString("\n")
    }

    /** Forces the buffered lines to disk now (Diagnostics refresh). */
    fun flushNow() {
        flushToDisk()
    }

    /** Redacts URLs to scheme + host + path — query strings carry
     *  signed tokens and must never be logged. */
    fun redact(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url.take(80)
        val rest = url.substring(schemeEnd + 3)
        val pathStart = rest.indexOf('/')
        val host = if (pathStart < 0) rest else rest.substring(0, pathStart)
        val path = if (pathStart < 0) "" else rest.substring(pathStart).substringBefore('?')
        return "${url.substring(0, schemeEnd)}://$host$path"
    }

    private fun append(level: String, category: Category, message: String) {
        val elapsed = (Date().time - startedAt.time) / 1000.0
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp (+${"%.1f".format(elapsed)}s) [$level][${category.raw}] $message"
        var shouldSchedule = false
        synchronized(lock) {
            buffer.add(line)
            if (buffer.size > BUFFER_LIMIT) {
                buffer.subList(0, buffer.size - BUFFER_LIMIT).clear()
            }
            if (!dirty) {
                dirty = true
                shouldSchedule = true
            }
        }
        if (shouldSchedule) scheduleFlush()
    }

    /**
     * The debounced disk mirror (iOS: a Task that sleeps 2 s and then
     * writes the snapshot). One in-flight flush at a time — the first
     * append after a flush schedules the next.
     */
    private fun scheduleFlush() {
        flushScope.launch {
            delay(2000)
            flushToDisk()
        }
    }

    /** Writes the snapshot (best-effort, atomic like the other stores;
     *  a failure just means the file lags the buffer). */
    private fun flushToDisk() {
        val text = synchronized(lock) {
            if (!dirty) return
            buffer.joinToString("\n")
        }
        try {
            val file = File(AmphoraContextProvider.context.filesDir, FILE_NAME)
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
            synchronized(lock) { dirty = false }
        } catch (_: Exception) {
            // Best-effort — the next flush retries.
        }
    }
}
