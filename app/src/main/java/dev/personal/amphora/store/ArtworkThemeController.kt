//
//  ArtworkThemeController.kt
//  app
//
//  Port of Amphora/Store/ArtworkThemeController.swift — owns the
//  ambient theme derived from the current track's artwork. Standalone
//  from AmphoraStore (it attaches directly to the player's
//  track-changed observers), so the theming layer stays an additive
//  module — and the palette math itself lives in `pandoracore`
//  (ArtworkColors), shared verbatim with the iOS app.
//
//  `@Observable` → Compose snapshot state: `colors` is read inside
//  composables and recomposition tracks it.
//

package dev.personal.amphora.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.personal.amphora.AmphoraContextProvider
import dev.personal.amphora.player.ArtworkPaletteService
import dev.personal.amphora.player.PlaybackClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pandoracore.ArtworkColors
import pandoracore.Track

object ArtworkThemeController {

    /**
     * The ambient palette for the current track — brand fallback until
     * artwork loads. Grayscale covers and ads keep the previous palette
     * (ads carry no artwork and must not flash the theme).
     */
    var colors: ArtworkColors by mutableStateOf(ArtworkColors.brand)
        private set

    /**
     * Memoized palettes per artwork URL, capped (artwork URLs are
     * session-scoped signed strings — a handful of entries is plenty).
     */
    private val paletteCache = mutableMapOf<String, ArtworkColors>()

    /** Guard against out-of-order completions landing on the wrong track. */
    private var paletteRequest = 0
    private var isAttached = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Idempotent — call once at launch (the port of the RootView
     *  attach). */
    fun attach(player: PlaybackClient) {
        if (isAttached) return
        isAttached = true
        player.onTrackChanged.add { track -> update(track) }
    }

    private fun update(track: Track?) {
        val url = track?.artworkUrl ?: return
        paletteCache[url]?.let { cached ->
            colors = cached
            return
        }
        paletteRequest += 1
        val request = paletteRequest
        scope.launch {
            val extracted = ArtworkPaletteService.palette(
                context = AmphoraContextProvider.context,
                artworkURL = url,
            )
            if (request != paletteRequest || extracted == null) return@launch
            colors = extracted
            if (paletteCache.size > 24) paletteCache.clear()
            paletteCache[url] = extracted
        }
    }
}
