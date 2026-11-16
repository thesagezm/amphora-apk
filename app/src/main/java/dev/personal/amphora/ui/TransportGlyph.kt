//
//  TransportGlyph.kt
//  app
//
//  Port of Amphora/UI/TransportGlyph.swift — the transport's icon
//  family, drawn rather than borrowed. The system's filled media
//  glyphs read chunky at transport sizes; these are drawn to one
//  design language: soft-rounded corners, matched visual weight, and
//  every proportion derived from the square the glyph is drawn in —
//  so play at 16dp and play at 44dp are the same icon, scaled.
//
//  The rounded-triangle and rounded-polygon math ports one-for-one
//  (quadratic-corner softening, the trim guard). BackFifteen draws the
//  arc + arrowhead on the Canvas and overlays the "15" as a Text —
//  SwiftUI's ZStack shape; the rounded text design maps to Android's
//  "sans-serif-round" family when present.
//

package dev.personal.amphora.ui

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.min

/** The transport's icon family. */
enum class TransportGlyphKind {
    play,
    pause,
    next,
    stop,
    back15,
}

/**
 * One transport glyph, drawn inside its own intrinsic frame — call
 * sites center it (the hero's 58dp circle, the player's transports).
 */
@Composable
fun TransportGlyph(kind: TransportGlyphKind, size: Dp, color: Color) {
    when (kind) {
        TransportGlyphKind.play -> {
            // A right-pointing triangle with softened corners; corner
            // radius proportional to the triangle's own size.
            Box(modifier = Modifier.size(width = size * 0.92f, height = size)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = this.size.width
                    val h = this.size.height
                    val path = roundedPolygon(
                        listOf(
                            Offset(0f, 0f),
                            Offset(w, h / 2f),
                            Offset(0f, h),
                        ),
                        cornerRadius = min(w, h) * 0.10f,
                    )
                    drawPath(path, color)
                }
            }
        }
        TransportGlyphKind.pause -> {
            // Two slim rounded bars, full height, a 0.16s gap between.
            Box(modifier = Modifier.size(width = size * 0.56f, height = size)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = this.size.height
                    val barWidth = s * 0.20f
                    val barHeight = s * 0.96f
                    val top = (s - barHeight) / 2f
                    val radius = s * 0.055f
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                    )
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(s * 0.36f, top),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                    )
                }
            }
        }
        TransportGlyphKind.next -> {
            // Triangle + bar: the double-triangle forward glyph, drawn
            // to the family's weight instead of the dated system one.
            Box(modifier = Modifier.size(width = size * 0.82f, height = size)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = this.size.height
                    val top = s * 0.02f
                    val triangleHeight = s * 0.96f
                    val triangleWidth = s * 0.62f
                    val triangle = roundedPolygon(
                        listOf(
                            Offset(0f, top),
                            Offset(triangleWidth, top + triangleHeight / 2f),
                            Offset(0f, top + triangleHeight),
                        ),
                        cornerRadius = min(triangleWidth, triangleHeight) * 0.10f,
                    )
                    drawPath(triangle, color)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(s * 0.69f, top),
                        size = androidx.compose.ui.geometry.Size(s * 0.13f, triangleHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.06f),
                    )
                }
            }
        }
        TransportGlyphKind.stop -> {
            Box(modifier = Modifier.size(size * 0.74f)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // iOS: RoundedRectangle(cornerRadius: size * 0.16)
                    // on a 0.74×0.74 square — the size param is the
                    // glyph's full square, not this canvas.
                    val fullSize = this.size.width / 0.74f
                    drawRoundRect(
                        color = color,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            fullSize * 0.16f,
                        ),
                    )
                }
            }
        }
        TransportGlyphKind.back15 -> {
            // Nearly-full circle, gap upper left, arrowhead at the top
            // pointing counterclockwise, the number inside — the shape
            // language of gobackward.15 at the family's weight.
            val roundedTypeface = remember {
                FontFamily(Typeface.create("sans-serif-round", Typeface.NORMAL))
            }
            Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = min(this.size.width, this.size.height)
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    // SwiftUI: arc from 200° to -90°, counterclockwise —
                    // Compose sweeps positive clockwise, so -290°.
                    drawArc(
                        color = color,
                        startAngle = 200f,
                        sweepAngle = -290f,
                        useCenter = false,
                        style = Stroke(width = s * 0.12f, cap = StrokeCap.Round),
                    )
                    // The arrowhead at the top, pointing left — the
                    // direction the arc ends travelling. The tip extends
                    // past the arc's end and the base overlaps the
                    // stroke's round cap, so the two read as one stroke.
                    val arrow = Path().apply {
                        moveTo(center.x - s * 0.135f, center.y - s * 0.40f)
                        lineTo(center.x + s * 0.01f, center.y - s * 0.495f)
                        lineTo(center.x + s * 0.01f, center.y - s * 0.305f)
                        close()
                    }
                    drawPath(arrow, color)
                }
                Text(
                    text = "15",
                    style = TextStyle(
                        fontSize = (size.value * 0.36f).sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = roundedTypeface,
                        color = color,
                    ),
                )
            }
        }
    }
}

/**
 * A closed polygon with each corner softened by a quadratic curve —
 * the edge is trimmed `cornerRadius` short of the vertex on both
 * sides and the vertex itself becomes the curve's control point.
 */
private fun roundedPolygon(points: List<Offset>, cornerRadius: Float): Path {
    val path = Path()
    val count = points.size
    if (count < 3) return path
    for (i in 0 until count) {
        val previous = points[(i + count - 1) % count]
        val current = points[i]
        val next = points[(i + 1) % count]
        val toPrevious = previous - current
        val toNext = next - current
        val previousLength = maxOf(hypot(toPrevious.x, toPrevious.y), 0.001f)
        val nextLength = maxOf(hypot(toNext.x, toNext.y), 0.001f)
        // Never trim past the middle of the adjoining edges.
        val trim = minOf(cornerRadius, previousLength / 2f, nextLength / 2f)
        val start = current + toPrevious / previousLength * trim
        val end = current + toNext / nextLength * trim
        if (i == 0) {
            path.moveTo(start.x, start.y)
        } else {
            path.lineTo(start.x, start.y)
        }
        path.quadraticTo(current.x, current.y, end.x, end.y)
    }
    path.close()
    return path
}
