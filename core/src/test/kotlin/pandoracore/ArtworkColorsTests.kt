//
//  ArtworkColorsTests.kt
//  pandoracore
//
//  Port of PandoraCoreTests/ArtworkColorsTests.swift — palette extraction
//  from synthetic RGBA8 pixels: solid colors, dominant hue election,
//  grayscale rejection, the fixed accent/background clamps, and HSV
//  round-trips. The same algorithm runs on every Amphora port, so these
//  vectors pin the derived values exactly.
//

package pandoracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkColorsTests {

    /** A solid RGBA8 image of one color. */
    private fun solidPixels(
        red: kotlin.Int,
        green: kotlin.Int,
        blue: kotlin.Int,
        width: kotlin.Int = 8,
        height: kotlin.Int = 8,
    ): ByteArray {
        val pixels = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            pixels[i * 4] = red.toByte()
            pixels[i * 4 + 1] = green.toByte()
            pixels[i * 4 + 2] = blue.toByte()
            pixels[i * 4 + 3] = 255.toByte()
        }
        return pixels
    }

    /** Recolors the first `count` pixels red. */
    private fun paintFirstRed(pixels: ByteArray, count: kotlin.Int) {
        for (i in 0 until count) {
            pixels[i * 4] = 220.toByte()
            pixels[i * 4 + 1] = 20.toByte()
            pixels[i * 4 + 2] = 20.toByte()
        }
    }

    @Test
    fun extractsSolidColorAsDominant() {
        val colors = ArtworkColors.extract(solidPixels(220, 40, 40), width = 8, height = 8)!!
        // Weighted average of identical pixels is the pixel itself.
        assertEquals(220.0 / 255, colors.dominant.red, 0.001)
        assertEquals(40.0 / 255, colors.dominant.green, 0.001)
        assertEquals(40.0 / 255, colors.dominant.blue, 0.001)
    }

    @Test
    fun dominantHueWinsOverScatteredOtherColors() {
        // 60 of 64 pixels green, 4 red: the green bucket must win even
        // though red is more saturated per pixel.
        val pixels = solidPixels(30, 200, 60)
        paintFirstRed(pixels, 4)
        val colors = ArtworkColors.extract(pixels, width = 8, height = 8)!!
        assertTrue(colors.dominant.green > colors.dominant.red)
        assertTrue(colors.dominant.green > colors.dominant.blue)
    }

    @Test
    fun grayscaleImageReturnsNull() {
        assertNull(
            ArtworkColors.extract(solidPixels(128, 128, 128), width = 8, height = 8),
        )
    }

    @Test
    fun nearBlackImageReturnsNull() {
        assertNull(
            ArtworkColors.extract(solidPixels(10, 12, 14), width = 8, height = 8),
        )
    }

    @Test
    fun tinyColoredLogoDoesNotTheme() {
        // 4 colored pixels out of 1024 is below the chromatic threshold.
        // (Swift passes an 8×8 buffer with 32×32 dimensions, so this lands
        // in the unusable-input guard — replicated call-for-call.)
        val pixels = solidPixels(20, 20, 24)
        paintFirstRed(pixels, 4)
        assertNull(ArtworkColors.extract(pixels, width = 32, height = 32))
    }

    @Test
    fun rejectsUnusableInput() {
        assertNull(ArtworkColors.extract(ByteArray(0), width = 0, height = 0))
        assertNull(ArtworkColors.extract(byteArrayOf(0, 0, 0, 255.toByte()), width = 2, height = 2))
    }

    @Test
    fun accentIsMidBrightnessAndSaturated() {
        // A dark muted blue cover: the accent must be lifted into the
        // fixed clamp window, not stay murky.
        val colors = ArtworkColors.extract(solidPixels(30, 40, 90), width = 8, height = 8)!!
        val (h, s, v) = ArtworkColors.rgbToHSV(colors.accent.red, colors.accent.green, colors.accent.blue)
        assertEquals(
            ArtworkColors.rgbToHSV(30.0 / 255, 40.0 / 255, 90.0 / 255).first,
            h,
            0.01,
        )
        assertTrue(s >= 0.45)
        assertTrue(v >= 0.55)
        assertTrue(v <= 0.80)
    }

    @Test
    fun accentCapsNeonDominants() {
        val colors = ArtworkColors.extract(solidPixels(255, 0, 255), width = 8, height = 8)!!
        val (_, _, v) = ArtworkColors.rgbToHSV(colors.accent.red, colors.accent.green, colors.accent.blue)
        assertTrue(v <= 0.80)
    }

    @Test
    fun backgroundsAreDarkerThanDominant() {
        val colors = ArtworkColors.extract(solidPixels(200, 120, 40), width = 8, height = 8)!!
        val dominantV = ArtworkColors.rgbToHSV(
            colors.dominant.red, colors.dominant.green, colors.dominant.blue,
        ).third
        val topV = ArtworkColors.rgbToHSV(
            colors.backgroundTop.red, colors.backgroundTop.green, colors.backgroundTop.blue,
        ).third
        val bottomV = ArtworkColors.rgbToHSV(
            colors.backgroundBottom.red, colors.backgroundBottom.green, colors.backgroundBottom.blue,
        ).third
        assertTrue(topV < dominantV)
        assertTrue(bottomV < topV)
        assertTrue(bottomV >= 0.04)
    }

    @Test
    fun brandPaletteIsComplete() {
        assertEquals(0.424, ArtworkColors.brand.dominant.red, 0.001)
        assertEquals(0.698, ArtworkColors.brand.accent.green, 0.001)
        val bottom = ArtworkColors.brand.backgroundBottom
        assertTrue(
            ArtworkColors.rgbToHSV(bottom.red, bottom.green, bottom.blue).third < 0.1,
        )
    }

    @Test
    fun luminanceDrivesOnAccentContrast() {
        // Brand gold is light → dark glyphs; a deep red accent is not.
        val gold = PaletteColor(red = 0.949, green = 0.698, blue = 0.200)
        val deepRed = PaletteColor(red = 0.70, green = 0.12, blue = 0.12)
        assertTrue(gold.isLight)
        assertFalse(deepRed.isLight)
    }

    @Test
    fun hsvRoundTrip() {
        // hsv→rgb→hsv must be the identity away from the gray axis.
        for (step in 0 until 12) {
            val hue = step.toDouble() / 12
            val rgb = ArtworkColors.hsvToColor(hue, 0.7, 0.6)
            val (backHue, backS, backV) = ArtworkColors.rgbToHSV(rgb.red, rgb.green, rgb.blue)
            assertEquals(hue, backHue, 0.001)
            assertEquals(0.7, backS, 0.001)
            assertEquals(0.6, backV, 0.001)
        }
    }

    @Test
    fun rgbToHSVHandlesAxisColors() {
        assertEquals(0.0, ArtworkColors.rgbToHSV(0.0, 0.0, 0.0).third, 0.0)
        assertEquals(0.0, ArtworkColors.rgbToHSV(1.0, 1.0, 1.0).second, 0.0)
        assertEquals(0.0, ArtworkColors.rgbToHSV(1.0, 0.0, 0.0).first, 0.001)
        assertEquals(11.0 / 12, ArtworkColors.rgbToHSV(1.0, 0.0, 0.5).first, 0.01)
    }
}
