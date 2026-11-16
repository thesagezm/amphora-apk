//
//  ArtworkPaletteService.kt
//  app
//
//  Port of Amphora/UI/ArtworkPaletteService.swift — glue between the
//  image pipeline and the shared palette extractor: loads an artwork
//  through Coil's shared image loader (the same cache the views use —
//  no second download), draws it into a small RGBA8 buffer and runs
//  `ArtworkColors.extract` on it. The extraction math lives in
//  `pandoracore` unchanged, so the palettes match the iOS derivation.
//
//  Port deltas (documented per AGENTS.md):
//  - Coil's shared loader replaces the iOS ArtworkCache (both keep one
//    disk copy per URL, shared with the views).
//  - The 32×32 draw runs through Bitmap.createScaledBitmap (bilinear ≈
//    CGContext's .medium interpolation) and the ARGB pixels are
//    premultiplied here, because Android's getPixels returns
//    non-premultiplied colors while iOS's CGContext wrote
//    premultiplied ones.
//

package dev.personal.amphora.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import pandoracore.ArtworkColors

object ArtworkPaletteService {

    /**
     * Downsample side cap — 1,024 pixels is plenty for a dominant hue
     * and keeps the draw + loop far off the interaction budget.
     */
    private const val SAMPLE_SIDE = 32

    /**
     * The palette for an artwork URL, loading through Coil's shared
     * image loader. null when the artwork cannot be loaded or is
     * effectively grayscale; callers keep their previous (or brand)
     * palette.
     */
    suspend fun palette(context: Context, artworkURL: String): ArtworkColors? {
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(artworkURL)
            .size(SAMPLE_SIDE, SAMPLE_SIDE)
            // Pixel reads need a software bitmap — a hardware one would
            // fail getPixels.
            .allowHardware(false)
            .build()
        val result = loader.execute(request) as? SuccessResult ?: return null
        // coil3 wraps android bitmaps in BitmapImage; anything else
        // (drawables) falls back to a software draw.
        val loaded: Bitmap = when (val image = result.image) {
            is coil3.BitmapImage -> image.bitmap
            else -> try {
                image.asDrawable(context.resources).toBitmap()
            } catch (error: IllegalStateException) {
                return null
            }
        }
        val sampled = smallBitmap(loaded) ?: return null
        return ArtworkColors.extract(
            pixels = rgba8Sample(sampled),
            width = sampled.width,
            height = sampled.height,
        )
    }

    /** One bitmap → a 32×32 bitmap (getPixels converts every config to
     *  ARGB; the palette math runs on opaque artwork covers). */
    private fun smallBitmap(full: Bitmap): Bitmap? {
        if (full.width <= 0 || full.height <= 0) return null
        val side = SAMPLE_SIDE
        return if (full.width == side && full.height == side) {
            full
        } else {
            full.scale(side, side, filter = true)
        }
    }

    /** The fixed 32×32 RGBA8 buffer (premultiplied) — the one
     *  bitmap-touching step; everything downstream is platform-free
     *  math. */
    private fun rgba8Sample(bitmap: Bitmap): ByteArray {
        val side = SAMPLE_SIDE
        val w = bitmap.width.coerceAtMost(side)
        val h = bitmap.height.coerceAtMost(side)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(side * side * 4)
        var o = 0
        for (i in 0 until w * h) {
            val argb = pixels[i]
            val alpha = (argb ushr 24) and 0xFF
            // Premultiplied RGBA8, matching the iOS CGContext buffer.
            val r = ((argb ushr 16) and 0xFF) * alpha / 255
            val g = ((argb ushr 8) and 0xFF) * alpha / 255
            val b = (argb and 0xFF) * alpha / 255
            out[o++] = r.toByte()
            out[o++] = g.toByte()
            out[o++] = b.toByte()
            out[o++] = alpha.toByte()
        }
        return out
    }
}
