//
//  RadioMetadataProbe.kt
//  app
//
//  Port of Amphora/Player/RadioMetadataProbe.swift — a read-only
//  listener on a radio station's ICY now-playing metadata.
//
//  The station's stream carries interleaved metadata blocks
//  (`StreamTitle='Artist - Title';…`) — the protocol is derived in
//  `pandoracore/RadioStreamMetadata.kt`, verified live against real
//  stations (Europe 1, MANGORADIO answer; France Info announces
//  nothing — no `icy-metaint`). This probe opens its **own** connection
//  to the stream URL with `Icy-MetaData: 1` and parses whatever bytes
//  arrive. The player keeps its own connection: the probe never touches
//  the audio path, it only reads along.
//
//  Consequences worth remembering:
//  - **Bandwidth**: while a station plays, the probe doubles the
//    stream's bandwidth (a second full stream connection). Acceptable —
//    and it stops the moment playback stops.
//  - **No announcements ≠ broken stream.** A station that sends no
//    `icy-metaint` is honestly reported (`announcesNothing`), not
//    retried forever.
//
//  One probe instance lives in `AmphoraStore`; `start` swaps the
//  station, `stop` ends everything.
//
//  Port deltas (documented per AGENTS.md):
//  - The Swift actor becomes a plain class: the listening loop runs on
//    Dispatchers.IO, one at a time (start/stop swap the job).
//  - URLSession.bytes (cancellable per byte) becomes a blocking OkHttp
//    read; cancellation unblocks it through `Call.cancel()` — stop()
//    cancels the call and the job together. A dead server surfaces as
//    an IOException whose *type* is logged, never its message (a
//    network error's description can carry the failing URL, and stream
//    URLs must never reach the log).
//

package dev.personal.amphora.player

import dev.personal.amphora.pandora.AmphoraHttp
import dev.personal.amphora.support.AppLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Request
import pandoracore.ICYMetadataParser
import pandoracore.RadioNowPlaying
import pandoracore.RadioStreamMetadata

class RadioMetadataProbe {

    /** What the probe learned, delivered as it learns it. */
    sealed class Event {
        /** Connected; the stream announces an `icy-metaint`. A
         *  `StreamTitle` is expected but has not arrived yet. */
        data object Listening : Event()

        /** A (new) song announcement arrived. */
        data class Announced(val nowPlaying: RadioNowPlaying) : Event()

        /** The server sent no `icy-metaint` — this station announces
         *  nothing and can never be identified from the stream. */
        data object AnnouncesNothing : Event()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var call: Call? = null

    /** The last announcement's raw title — a repeat of the same title
     *  (the station re-announcing) is not a new song. */
    private var lastAnnouncedTitle: String? = null

    /**
     * A live stream runs for hours, so no total call budget may bound
     * the listening session (`callTimeout(0)` disables it); the read
     * timeout only bounds the time between data arrivals, so a dead
     * server is noticed while a paused-but-alive one is not dropped.
     * (iOS: timeoutIntervalForRequest 20, resource 24 h, no cache.)
     */
    private val client = AmphoraHttp.client.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /**
     * Starts (or restarts) listening to [url]. Any previous listening
     * task is cancelled first — the probe follows the station that is
     * playing now. [deliver] is invoked from a background thread; the
     * caller hops its handling onto the main thread.
     */
    fun start(url: String, deliver: (Event) -> Unit) {
        stop()
        lastAnnouncedTitle = null
        job = scope.launch {
            // The listening loop checks its own job's liveness — the
            // `use` lambda below is not inline, so the suspend
            // `isActive` does not reach the read loop.
            val me = coroutineContext[Job]!!
            listen(url = url, deliver = deliver, running = me)
            // The task ends when the stream ends, the connection fails
            // or it is cancelled. A finished task is left in place
            // until the next `start`/`stop` replaces it — there is
            // nothing to clean up (the call releases its resources on
            // its own), and identity bookkeeping here would race a
            // concurrent restart.
        }
    }

    /** Stops listening and drops the connection. */
    fun stop() {
        // Cancel the call first: the blocked read unblocks with an
        // IOException the moment the connection dies.
        call?.cancel()
        call = null
        job?.cancel()
        job = null
        lastAnnouncedTitle = null
    }

    /**
     * The listening loop: open the byte stream, read the interval from
     * the headers, then feed every chunk into the core parser. Runs
     * until cancelled, the stream ends or the connection fails — a
     * dead server surfaces as a task that simply stopped, never as a
     * crash, and the audio path is unaffected either way.
     */
    private suspend fun listen(
        url: String,
        deliver: (Event) -> Unit,
        running: Job,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Icy-MetaData", "1")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        val call = client.newCall(request)
        this.call = call
        try {
            call.execute().use { response ->
                // Header lookup is case-insensitive; the map keeps the
                // last value per name (the interval header is single).
                val headers = response.headers.toMap()
                val interval = ICYMetadataParser.metadataInterval(headers)
                if (interval == null) {
                    AppLog.info("radio metadata: station announces nothing", AppLog.Category.playback)
                    deliver(Event.AnnouncesNothing)
                    return
                }
                AppLog.info(
                    "radio metadata: listening, interval $interval bytes",
                    AppLog.Category.playback,
                )
                deliver(Event.Listening)

                val parser = ICYMetadataParser()
                parser.setMetadataInterval(interval)
                val stream = response.body?.byteStream() ?: return

                // Feeds one chunk into the parser and delivers each NEW
                // title. The parser accepts any chunk boundaries; the
                // 4 KB buffer only bounds the delivery latency
                // (≈0.25 s at 128 kbps) — the port of the iOS
                // byte-by-byte read into a chunk.
                val buffer = ByteArray(4096)
                while (running.isActive) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    for (block in parser.consume(buffer.copyOf(count))) {
                        val nowPlaying = RadioStreamMetadata.nowPlaying(block) ?: continue
                        if (nowPlaying.rawTitle == lastAnnouncedTitle) continue
                        lastAnnouncedTitle = nowPlaying.rawTitle
                        AppLog.info(
                            "radio metadata: now playing \"${nowPlaying.rawTitle}\"",
                            AppLog.Category.playback,
                        )
                        deliver(Event.Announced(nowPlaying))
                    }
                }
            }
        } catch (error: java.io.IOException) {
            if (!running.isActive) {
                // Normal shutdown (the cancellation that unblocked the
                // read).
                return
            }
            // A failed probe never fails the stream: the audio keeps
            // playing and the UI keeps the station's own identity. The
            // error itself is not logged — see the header note.
            AppLog.info(
                "radio metadata: probe ended (connection failed, type ${error.javaClass.simpleName})",
                AppLog.Category.playback,
            )
        } catch (error: RuntimeException) {
            AppLog.info(
                "radio metadata: probe ended (connection failed, type ${error.javaClass.simpleName})",
                AppLog.Category.playback,
            )
        } finally {
            if (this.call === call) {
                this.call = null
            }
        }
    }
}
