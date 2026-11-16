//
//  PlayerScrubber.kt
//  app
//
//  Port of Amphora/UI/PlayerScrubber.swift — the full player's
//  scrubber: a capsule progress bar with a drag knob. Custom-drawn
//  instead of `Slider` so the look is Amphora's own — the knob and fill
//  tinted by the artwork accent. TalkBack-adjustable (±10 s steps).
//
//  Port delta: SwiftUI's DragGesture.updating → detectDragGestures with
//  a drag-fraction state; the ±10 s adjustable action maps to TalkBack
//  custom actions.
//

package dev.personal.amphora.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.personal.amphora.support.TimeFormat
import kotlin.math.max
import kotlin.math.min

@Composable
fun PlayerScrubber(
    elapsed: Double,
    duration: Double,
    accent: Color,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Double?>(null) }

    val fraction = if (duration > 0) {
        (elapsed / duration).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    // What the bar shows: the dragged position while dragging, else
    // live playback. Live movement glides (0.2 s linear); a dragging
    // finger tracks instantly.
    val display by animateFloatAsState(
        targetValue = (dragFraction ?: fraction).toFloat(),
        animationSpec = if (dragFraction != null) snap() else tween(durationMillis = 200),
        label = "scrubber-display",
    )

    val density = LocalDensity.current
    val barHeight = with(density) { 7.dp.toPx() }
    val knobRadius = with(density) { 8.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            // A dead scrubber (no length) shows dimmed and ignores
            // touches — the pointerInput below is a no-op then.
            .alpha(if (duration > 0) 1f else 0.35f)
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                val width = size.width.toFloat()
                detectDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / width).toDouble().coerceIn(0.0, 1.0)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / width).toDouble().coerceIn(0.0, 1.0)
                    },
                    onDragEnd = {
                        dragFraction?.let { onSeek(it * duration) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                )
            }
            .semantics {
                contentDescription = "Playback position"
                stateDescription = TimeFormat.string(max(0.0, elapsed))
                customActions = listOf(
                    CustomAccessibilityAction("Forward 10 seconds") {
                        if (duration > 0) onSeek(min(duration, elapsed + 10))
                        true
                    },
                    CustomAccessibilityAction("Back 10 seconds") {
                        if (duration > 0) onSeek(max(0.0, elapsed - 10))
                        true
                    },
                )
            },
    ) {
        val width = size.width
        val centerY = size.height / 2f
        val radius = CornerRadius(barHeight / 2f)
        // Track capsule.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            topLeft = Offset(0f, centerY - barHeight / 2f),
            size = Size(width, barHeight),
            cornerRadius = radius,
        )
        // Fill capsule (the iOS min width of 7 pt keeps a sliver visible
        // at position zero).
        val fillWidth = max(barHeight, width * display)
        drawRoundRect(
            color = accent,
            topLeft = Offset(0f, centerY - barHeight / 2f),
            size = Size(fillWidth, barHeight),
            cornerRadius = radius,
        )
        // The knob appears only while dragging (the port of the
        // dragFraction == nil opacity switch). The iOS knob shadow
        // (black 35%, r3, y1) becomes a translucent halo circle — Paint
        // shadow layers don't render on hardware canvases.
        if (dragFraction != null) {
            val centerX = (width * display).coerceIn(knobRadius, width - knobRadius)
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = knobRadius + 3.dp.toPx() / 2f,
                center = Offset(centerX, centerY + 1.dp.toPx() / 2f),
            )
            drawCircle(
                color = Color.White,
                radius = knobRadius,
                center = Offset(centerX, centerY),
            )
        }
    }
}
