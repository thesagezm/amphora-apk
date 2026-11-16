//
//  RadioStationArtwork.kt
//  app
//
//  Port of Amphora/UI/RadioStationArtwork.swift — artwork for an
//  internet-radio station.
//
//  Most stations have no favicon at all, and the ones that do usually
//  serve a 16×16 pixel icon — blown up to the full player's size that
//  reads as "no artwork" (or as a blurry smudge). So the fallback is
//  generated: the station's initials over a deterministic gradient
//  whose hue comes from the station name — the same color on every
//  launch and on every device (`RadioStationIdentity`, in pandoracore).
//
//  The real favicon still wins when it is good enough for the slot it
//  is shown in: `minimumArtworkPixels` is the caller's requirement — a
//  44dp list row needs far less than the full player's ~400dp cover.
//
//  Port deltas (documented per AGENTS.md):
//  - The iOS ArtworkCache load becomes a manual Coil request through
//    the shared loader (the ArtworkPaletteService idiom), decoded at
//    ORIGINAL size so the pixel gate reads the real favicon's
//    dimensions.
//  - SwiftUI's `.rounded` font design has no bundled counterpart here —
//    the initials render in the default sans, same weight/opacity.
//  - `.minimumScaleFactor(0.5)` has no direct Compose equivalent on the
//    pinned Compose line; initials are one or two characters, so the
//    single-line clipped text is visually equivalent.
//

package dev.personal.amphora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pandoracore.RadioStationIdentity

/** The smallest favicon still worth showing on the lock screen (which
 *  draws the cover at roughly 60–120pt). Below it, the generated tile
 *  is used. (The port of `lockScreenMinimumPixels`; the media
 *  notification sizes its own artwork.) */
const val RADIO_LOCK_SCREEN_MINIMUM_PIXELS = 120

@Composable
fun RadioStationArtwork(
    stationName: String,
    faviconURL: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Theme.radiusS,
    /** The smallest favicon still worth showing in this slot. Below it
     *  the generated tile is used instead — a 16×16 icon stretched over
     *  a 44dp row (or a 400dp cover) looks worse than the tile. */
    minimumArtworkPixels: Int = 64,
) {
    val context = LocalContext.current
    var image by remember(faviconURL) { mutableStateOf<android.graphics.Bitmap?>(null) }

    // The favicon load (iOS: ArtworkCache.shared.image(for:), cancelled
    // with the view). Coil cancels nothing here — the LaunchedEffect
    // scope does: a new faviconURL tears the old load down.
    LaunchedEffect(faviconURL) {
        image = null
        if (faviconURL.isNullOrEmpty()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(faviconURL)
                    // The gate compares the favicon's real dimensions —
                    // decode at the source size.
                    .size(coil3.size.Size.ORIGINAL)
                    .build()
                (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image
            } catch (_: Exception) {
                null
            }
        }
        val bitmap = when (val it = loaded) {
            is BitmapImage -> it.bitmap
            null -> null
            else -> try {
                it.asDrawable(context.resources).toBitmap()
            } catch (_: IllegalStateException) {
                null
            }
        }
        if (bitmap != null &&
            bitmap.width >= minimumArtworkPixels &&
            bitmap.height >= minimumArtworkPixels
        ) {
            image = bitmap
        }
    }

    Box(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        // The generated tile always underneath (the iOS ZStack).
        RadioTile(stationName, modifier = Modifier.matchParentSize())
        image?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * The generated tile: the station's initials over a gradient. The
 * gradient of the generated tile — two stops around the station's hue:
 * a brighter one in the top corner, a deeper one in the bottom — the
 * same shape as the app's brand gradient, but colored by the station's
 * name. Exposed for the media-notification artwork renderer.
 */
fun radioGradientColors(name: String): Pair<Color, Color> {
    val hue = RadioStationIdentity.hue(name).toFloat()
    val start = Color.hsv(hue * 360f, 0.55f, 0.62f)
    val end = Color.hsv(((hue + 0.08f) % 1f) * 360f, 0.72f, 0.34f)
    return start to end
}

@Composable
private fun RadioTile(stationName: String, modifier: Modifier = Modifier) {
    val (start, end) = remember(stationName) { radioGradientColors(stationName) }
    BoxWithConstraints(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(start, end),
                        // iOS: topLeading → bottomTrailing.
                        start = androidx.compose.ui.geometry.Offset.Zero,
                        end = androidx.compose.ui.geometry.Offset(
                            Float.POSITIVE_INFINITY,
                            Float.POSITIVE_INFINITY,
                        ),
                    ),
                ),
        )
        val fontSize = with(LocalDensity.current) {
            (maxWidth * 0.38f).toSp().let { if (it.value < 11f) 11.sp else it }
        }
        androidx.compose.material3.Text(
            text = RadioStationIdentity.initials(stationName),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.95f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = maxWidth * 0.08f),
        )
    }
}

/** The generated tile as a bitmap — for callers that need pixels (the
 *  media notification's artwork). The gradient and the initials are the
 *  same ones the view draws (the port of
 *  `RadioStationArtworkRenderer.tileImage`). */
fun radioTileBitmap(stationName: String, side: Int): android.graphics.Bitmap {
    val (start, end) = radioGradientColors(stationName)
    val bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, side.toFloat(), side.toFloat(),
            start.toArgb().toLong(), end.toArgb().toLong(),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.argb(
            (0.95f * 255).toInt(), 255, 255, 255,
        )
        textSize = (side * 0.38f).coerceAtLeast(11f)
        isFakeBoldText = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val initials = RadioStationIdentity.initials(stationName)
    val textY = side / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(initials, side / 2f, textY, textPaint)
    return bitmap
}
