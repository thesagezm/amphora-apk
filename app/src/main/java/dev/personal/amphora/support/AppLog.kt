//
//  AppLog.kt
//  app
//
//  Port of Amphora/Support/AppLog.swift — process-wide logging (the
//  first slice: system log + bounded ring buffer; the file mirror and
//  the crash tail land with CrashReporter in A8).
//
//  Redaction is part of the contract: nothing secret reaches the log.
//  URLs are reduced to scheme + host + path — Pandora's query strings
//  carry signed stream/image tokens; credentials and auth tokens are
//  never logged.
//

package dev.personal.amphora.support

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    enum class Category(val raw: String) {
        app("app"),
        playback("playback"),
        artwork("artwork"),
        network("network"),
        lyrics("lyrics"),
    }

    private const val SUBSYSTEM = "dev.personal.amphora"
    private const val BUFFER_LIMIT = 600

    /** Seconds since launch, used to prefix each buffered line — after a
     *  crash, "crashed 4.2s after play" is exactly what we need. */
    private val startedAt = Date()

    private val lock = Any()
    private val buffer = mutableListOf<String>()

    /** The most recent lines in memory (the Diagnostics screen reads
     *  this; the file mirror and the crash tail come in A8). */
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
        synchronized(lock) {
            buffer.add(line)
            if (buffer.size > BUFFER_LIMIT) {
                buffer.subList(0, buffer.size - BUFFER_LIMIT).clear()
            }
        }
    }
}
