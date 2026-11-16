//
//  CachedArtwork.kt
//  app
//
//  Port of Amphora/UI/CachedArtwork.swift — artwork view backed by a
//  memory+disk cache. Shows a placeholder while loading and when the
//  artwork cannot be loaded; cancels the load when the view leaves
//  (Coil cancels with the composition).
//
//  Port delta: Coil's memory+disk tiers stand in for the iOS
//  ArtworkCache; the dedicated cache port (URL normalization +
//  fallback candidates + bounded-decode control) lands with A4's
//  artwork cache. Coil sizes decodes to the composable's constraints,
//  which keeps the bounded-decode rule for row thumbnails.
//

package dev.personal.amphora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

/**
 * Artwork with the iOS states: a recessed tile while loading, the
 * fallback glyph when it cannot load, the image filling a rounded
 * rect otherwise. Hidden from accessibility on iOS — same here.
 */
@Composable
fun CachedArtwork(
    urlString: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 10.dp,
    fallbackSystemImage: ImageVector = Icons.Rounded.MusicNote,
) {
    val theme = Theme.tokens()
    val secondary = if (theme.isLight) Color(0xFF5B5570) else Color(0xFF9B94B4)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(theme.surfaceHighlight.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        if (urlString == null) {
            FallbackGlyph(fallbackSystemImage, secondary)
        } else {
            SubcomposeAsyncImage(
                model = urlString,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                loading = {
                    CircularProgressIndicator(
                        color = secondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                },
                error = { FallbackGlyph(fallbackSystemImage, secondary) },
                success = { SubcomposeAsyncImageContent() },
            )
        }
    }
}

@Composable
private fun FallbackGlyph(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(16.dp),
    )
}
