//
//  Theme.kt
//  app
//
//  Port of Amphora/UI/Theme.swift — Amphora's design tokens. The visual
//  language is deliberately self-contained — flat dark surfaces,
//  generous continuous corner radii, one accent gradient per theme, and
//  an artwork-derived ambient gradient on the full player — so the
//  Android port reproduces every value here one-for-one. No system
//  material effects are part of the look; translucency is expressed as
//  plain opacity so it ports directly.
//
//  The palette is user-selectable (Settings → Appearance, ported in
//  A8): a background appearance × an accent. The tokens below read the
//  live selection from `ThemeController`, so a change re-renders every
//  composable that touches them.
//

package dev.personal.amphora.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import pandoracore.ArtworkColors
import pandoracore.PaletteColor

// MARK: User-selectable theme pieces

/** The background family. Pandora Night uses Pandora's brand blues
 *  (#224099 deep / #00A0EE bright) cast over near-black; True Black is
 *  OLED-pure with neutral surfaces; Daylight and Paper are the light
 *  families; System follows the device's light/dark setting. */
enum class ThemeAppearance(val displayName: String) {
    dark("Amphora Dark"),
    pandora("Pandora Night"),
    trueBlack("True Black"),
    daylight("Daylight"),
    paper("Paper"),
    system("Match System");

    /** `true` for the light families. `system` is not a palette of its
     *  own — `ThemeController` resolves it to a concrete family before
     *  the tokens are read. */
    val isLight: Boolean
        get() = this == daylight || this == paper

    data class Palette(
        val background: Color,
        val backgroundTop: Color,
        val surface: Color,
        val surfaceHighlight: Color,
    )

    val palette: Palette
        get() = when (this) {
            dark -> Palette(
                background = Color(0.047f, 0.039f, 0.078f),
                backgroundTop = Color(0.086f, 0.075f, 0.137f),
                surface = Color(0.106f, 0.094f, 0.157f),
                surfaceHighlight = Color(0.157f, 0.141f, 0.227f),
            )
            pandora -> Palette(
                background = Color(0.024f, 0.043f, 0.094f),
                backgroundTop = Color(0.051f, 0.106f, 0.220f),
                surface = Color(0.063f, 0.106f, 0.192f),
                surfaceHighlight = Color(0.106f, 0.169f, 0.302f),
            )
            trueBlack -> Palette(
                background = Color.Black,
                backgroundTop = Color(0.050f, 0.050f, 0.050f),
                surface = Color(0.078f, 0.078f, 0.078f),
                surfaceHighlight = Color(0.133f, 0.133f, 0.133f),
            )
            daylight ->
                // Cool neutral light — a very light grey floor with white
                // cards sitting on it.
                Palette(
                    background = Color(0.929f, 0.937f, 0.957f),
                    backgroundTop = Color(0.980f, 0.984f, 0.996f),
                    surface = Color(1.000f, 1.000f, 1.000f),
                    surfaceHighlight = Color(0.886f, 0.898f, 0.925f),
                )
            paper ->
                // Warm light — a paper-white floor with a cream card tone.
                Palette(
                    background = Color(0.957f, 0.937f, 0.902f),
                    backgroundTop = Color(0.996f, 0.988f, 0.969f),
                    surface = Color(1.000f, 0.996f, 0.984f),
                    surfaceHighlight = Color(0.906f, 0.878f, 0.831f),
                )
            system ->
                // Resolved by `ThemeController` before it reaches the
                // tokens; the fallback keeps a preview sane.
                Palette(
                    background = Color(0.047f, 0.039f, 0.078f),
                    backgroundTop = Color(0.086f, 0.075f, 0.137f),
                    surface = Color(0.106f, 0.094f, 0.157f),
                    surfaceHighlight = Color(0.157f, 0.141f, 0.227f),
                )
        }
}

/** The accent family: a primary color, the gradient partner it fades
 *  into, and the glyph color that stays legible on a primary fill. */
enum class ThemeAccent(val displayName: String) {
    gold("Gold"),
    pandora("Pandora Blue"),
    violet("Violet"),
    aqua("Aqua"),
    rose("Rose");

    data class Palette(
        val primary: Color,
        val secondary: Color,
        /** Black or white — whichever keeps glyphs legible on `primary`. */
        val glyph: Color,
        /**
         * A darker sibling of `primary`, for the accent used as *text*
         * on a light floor (the bright fill colours lose contrast
         * against white).
         */
        val readable: Color,
    ) {
        val gradient: Brush
            get() = Brush.linearGradient(
                colors = listOf(primary, secondary),
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset.Infinite,
            )
    }

    val palette: Palette
        get() = when (this) {
            gold ->
                // The app icon's gold, fading into its violet.
                Palette(
                    primary = Color(0.949f, 0.698f, 0.200f),
                    secondary = Color(0.424f, 0.290f, 0.714f),
                    glyph = Color.Black,
                    readable = Color(0.620f, 0.408f, 0.020f),
                )
            pandora ->
                // Pandora's brand pair: cerulean #00A0EE → deep blue #224099.
                Palette(
                    primary = Color(0.000f, 0.627f, 0.933f),
                    secondary = Color(0.133f, 0.251f, 0.600f),
                    glyph = Color.Black,
                    readable = Color(0.020f, 0.353f, 0.612f),
                )
            violet -> Palette(
                primary = Color(0.545f, 0.361f, 0.965f),
                secondary = Color(0.925f, 0.282f, 0.600f),
                glyph = Color.White,
                readable = Color(0.396f, 0.227f, 0.804f),
            )
            aqua -> Palette(
                primary = Color(0.176f, 0.831f, 0.749f),
                secondary = Color(0.055f, 0.647f, 0.914f),
                glyph = Color.Black,
                readable = Color(0.020f, 0.427f, 0.388f),
            )
            rose -> Palette(
                primary = Color(0.984f, 0.443f, 0.522f),
                secondary = Color(0.753f, 0.149f, 0.827f),
                glyph = Color.Black,
                readable = Color(0.706f, 0.149f, 0.239f),
            )
        }
}

/** The user's selection: a background appearance × an accent. */
data class ThemeSelection(
    val appearance: ThemeAppearance = ThemeAppearance.dark,
    val accent: ThemeAccent = ThemeAccent.gold,
)

/**
 * Holds the live selection. The iOS `ThemeController` resolves `.system`
 * against the device's light/dark setting; the same resolution happens
 * here through `isSystemInDarkTheme()` at composition time. The full
 * controller (persisted selection, settings UI) ports in A8.
 */
object ThemeController {
    var selection by mutableStateOf(ThemeSelection())
}

// MARK: Live tokens

/**
 * Amphora's live design tokens. All palette values are read through
 * `ThemeController`, so a Settings change re-renders every consumer.
 */
object Theme {

    /** The *resolved* selection — `.system` is already mapped to a
     *  concrete light or dark family by the controller. */
    private fun selection(systemDark: Boolean): ThemeSelection {
        val raw = ThemeController.selection
        return if (raw.appearance == ThemeAppearance.system) {
            raw.copy(
                appearance = if (systemDark) ThemeAppearance.dark else ThemeAppearance.daylight,
            )
        } else {
            raw
        }
    }

    // MARK: Corner radii (continuous curves everywhere)

    val radiusS = 10.dp   // small thumbnails
    val radiusM = 16.dp   // cards, buttons, text fields
    val radiusCard = 20.dp // the floating mini player card
    val radiusL = 24.dp   // large cards
    val radiusArtwork = 28.dp // full-player artwork

    fun shapeM(): Shape = RoundedCornerShape(radiusM)
    fun shapeCard(): Shape = RoundedCornerShape(radiusCard)
    fun shapeL(): Shape = RoundedCornerShape(radiusL)

    /**
     * Reads every token for one composition pass. Call this once per
     * composable that needs several tokens — the values stay consistent
     * within the pass and the recomposition tracks the selection.
     */
    data class Tokens(
        /** The app's floor. */
        val background: Color,
        /** Elevated cards, mini player, list rows. */
        val surface: Color,
        /** Pressed / selected surfaces, text fields. */
        val surfaceHighlight: Color,
        /** The active accent — the *fill* colour (buttons, dots, washes). */
        val accent: Color,
        /** The accent's gradient partner. */
        val accentSecondary: Color,
        /** Black or white — the glyph color that stays legible on `accent`. */
        val accentGlyph: Color,
        /**
         * The accent as *text* — a darker sibling that stays legible on
         * a light floor.
         */
        val accentText: Color,
        /**
         * Whisper-soft strokes (used sparingly — surfaces, not borders,
         * carry the structure). On a light floor a white stroke would be
         * invisible, so the stroke inverts to a soft black.
         */
        val stroke: Color,
        /** The recessed track of a progress bar drawn on the surfaces. */
        val progressTrack: Color,
        /** The full player's own surfaces — always a dark scene. */
        val playerSurface: Color,
        val isLight: Boolean,
        /** The active accent's gradient, for prominent moments. */
        val brandGradient: Brush,
        /** Subtle vertical lift for full-screen surfaces. */
        val backgroundGradient: Brush,
    )

    @Composable
    fun tokens(): Tokens {
        val systemDark = isSystemInDarkTheme()
        val resolved = selection(systemDark)
        val palette = resolved.appearance.palette
        val accent = resolved.accent.palette
        val isLight = resolved.appearance.isLight
        return Tokens(
            background = palette.background,
            surface = palette.surface,
            surfaceHighlight = palette.surfaceHighlight,
            accent = accent.primary,
            accentSecondary = accent.secondary,
            accentGlyph = accent.glyph,
            accentText = if (isLight) accent.readable else accent.primary,
            stroke = if (isLight) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.07f),
            progressTrack = if (isLight) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.10f),
            playerSurface = Color.White.copy(alpha = 0.14f),
            isLight = isLight,
            brandGradient = accent.gradient,
            backgroundGradient = Brush.verticalGradient(
                colors = listOf(palette.backgroundTop, palette.background),
            ),
        )
    }

    /**
     * The full player's ambient backdrop, derived from the current
     * artwork (brand fallback before artwork loads). Deliberately
     * independent of the theme — the artwork colors the player.
     */
    fun playerBackground(colors: ArtworkColors): Brush = Brush.verticalGradient(
        colors = listOf(colors.backgroundTop.asColor(), colors.backgroundBottom.asColor()),
    )
}

/** The Compose mapping of a platform-free palette color. */
fun PaletteColor.asColor(): Color = Color(red = red.toFloat(), green = green.toFloat(), blue = blue.toFloat())

/**
 * Apple-Music-style press feedback: the control dips and dims under a
 * finger with a soft spring — so the transport never feels static
 * while the artwork and progress move (user report 2026-10-24).
 * Labels carry their own styling; this only animates.
 */
object Pressable {
    val pressedOpacity = 0.6f
    val pressedScale = 0.92f
    const val springStiffness = 380f
}

/** Press feedback for a full-width list row — much softer than
 *  `Pressable` on purpose (a 380pt-wide row must not shrink 8%). */
object RowPressable {
    val pressedOpacity = 0.72f
    val pressedScale = 0.99f
}

/**
 * Draws one list row as a soft rounded surface card — Amphora's list
 * language everywhere (stations, history, bookmarks, search results).
 * Fill only, no stroke: structure comes from the surface tone against
 * the floor, never from a hard border. `tinted` marks an active row
 * (e.g. the playing station) with a matte accent wash.
 *
 * iOS applies this through `listRowBackground` + `listRowInsets`
 * (content inset 16/10, a 5pt gap between cards); the Compose modifier
 * draws the card and insets its content directly.
 */
fun Modifier.amphoraCardRow(theme: Theme.Tokens, tinted: Boolean = false): Modifier =
    padding(horizontal = 16.dp, vertical = 5.dp)
        .background(
            color = if (tinted) theme.accent.copy(alpha = 0.14f) else theme.surface,
            shape = RoundedCornerShape(Theme.radiusM),
        )
        .padding(horizontal = 16.dp, vertical = 10.dp)

/**
 * Port of `RowPressableButtonStyle` (Theme.swift): press feedback for
 * a full-width list row — the card dims and settles with a spring.
 * Much softer than `Pressable` on purpose: that one shrinks a glyph
 * button by 8%, which is right for a 44pt control and wrong for a
 * 380pt-wide row.
 *
 * `strong = true` selects iOS's `PressableButtonStyle` instead — the
 * full 8% dip for rows whose iOS port chose it (e.g. the browse
 * sheet's station rows).
 *
 * The iOS style animates the plain button's label; here the press
 * interaction drives the same opacity+scale on the row's own layer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowPressableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    strong: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) {
            if (strong) Pressable.pressedScale else RowPressable.pressedScale
        } else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Pressable.springStiffness),
        label = "row-press-scale",
    )
    val opacity by animateFloatAsState(
        targetValue = if (isPressed) {
            if (strong) Pressable.pressedOpacity else RowPressable.pressedOpacity
        } else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = Pressable.springStiffness),
        label = "row-press-opacity",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = opacity
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        content()
    }
}
