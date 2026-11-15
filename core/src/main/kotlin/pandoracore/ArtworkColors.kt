//
//  ArtworkColors.kt
//  pandoracore
//
//  Port of PandoraCore/ArtworkColors.swift — dominant-color extraction
//  from artwork pixels — the shared derivation behind Amphora's
//  artwork-driven theming. Pure pixel math with no platform
//  dependencies: the app target feeds it RGBA bytes (from a Bitmap on
//  Android) and the iOS app feeds the same bytes, so both apps derive
//  identical palettes from the same cover art.
//
//  Algorithm (deterministic, ~1k pixels):
//   1. Convert every pixel to HSV.
//   2. Skip near-black (v < 0.08) and gray (s < 0.10) pixels — they
//      describe lighting, not the cover's color.
//   3. Bucket by hue (12 buckets × 30°); a pixel's weight is
//      `s · (0.2 + 0.8·v)` — saturation-weighted with a floor so fully
//      saturated brights (pure red, neon covers) keep full weight and
//      only darkness fades a pixel out.
//   4. The winning bucket's weighted-average RGB is the dominant color;
//      the accent and both gradient stops are derived from it in HSV
//      space with fixed clamps.
//   5. If the total chromatic weight is negligible the cover is
//      effectively grayscale — return null and let the caller fall back
//      to the brand palette.
//

package pandoracore

/**
 * An sRGB color component triple (0.0..1.0 per channel), platform-free
 * so it can be shared with every port.
 */
data class PaletteColor(
    var red: Double,
    var green: Double,
    var blue: Double,
) {
    /** Rec. 709 relative luminance (0.0..1.0). */
    val relativeLuminance: Double
        get() = 0.2126 * red + 0.7152 * green + 0.0722 * blue

    /**
     * True when the color is light enough that dark content (icons,
     * glyphs) reads on it — drives on-accent button contrast.
     */
    val isLight: Boolean
        get() = relativeLuminance >= 0.5
}

/**
 * The artwork-derived theme for one cover: a vivid accent for controls
 * and two deep stops for the ambient background gradient.
 */
data class ArtworkColors(
    val dominant: PaletteColor,
    val accent: PaletteColor,
    val backgroundTop: PaletteColor,
    val backgroundBottom: PaletteColor,
) {
    companion object {
        /**
         * Amphora's brand palette (violet + gold, echoing the app icon) —
         * used before the first artwork loads and for grayscale covers.
         */
        val brand = ArtworkColors(
            dominant = PaletteColor(red = 0.424, green = 0.290, blue = 0.714),
            accent = PaletteColor(red = 0.949, green = 0.698, blue = 0.200),
            backgroundTop = PaletteColor(red = 0.133, green = 0.094, blue = 0.212),
            backgroundBottom = PaletteColor(red = 0.047, green = 0.039, blue = 0.078),
        )

        /** Number of hue buckets (30° each). */
        const val HUE_BUCKET_COUNT = 12

        /**
         * Minimum total chromatic weight for a cover to count as colored.
         * A handful of stray colored pixels must not theme the player; a
         * region of ≥ ~2% of a 32×32 sample does.
         */
        const val MINIMUM_CHROMATIC_WEIGHT = 8.0

        /**
         * Extracts a palette from raw RGBA8 bytes (premultiplied alpha,
         * 4 bytes per pixel, row-major). Returns null for unusable input or
         * an effectively grayscale image.
         */
        fun extract(
            pixels: ByteArray,
            width: kotlin.Int,
            height: kotlin.Int,
        ): ArtworkColors? {
            if (width <= 0 || height <= 0 || pixels.size < width * height * 4) {
                return null
            }

            val weightSums = DoubleArray(HUE_BUCKET_COUNT)
            val weightedRed = DoubleArray(HUE_BUCKET_COUNT)
            val weightedGreen = DoubleArray(HUE_BUCKET_COUNT)
            val weightedBlue = DoubleArray(HUE_BUCKET_COUNT)
            var totalWeight = 0.0

            var offset = 0
            val end = width * height * 4
            while (offset < end) {
                val red = (pixels[offset].toInt() and 0xFF) / 255.0
                val green = (pixels[offset + 1].toInt() and 0xFF) / 255.0
                val blue = (pixels[offset + 2].toInt() and 0xFF) / 255.0
                val (hue, saturation, value) = rgbToHSV(red, green, blue)
                if (value < 0.08 || saturation < 0.10) {
                    offset += 4
                    continue
                }
                val weight = saturation * (0.2 + 0.8 * value)
                if (weight <= 0) {
                    offset += 4
                    continue
                }
                val bucket = minOf(HUE_BUCKET_COUNT - 1, (hue * HUE_BUCKET_COUNT).toInt())
                weightSums[bucket] += weight
                weightedRed[bucket] += red * weight
                weightedGreen[bucket] += green * weight
                weightedBlue[bucket] += blue * weight
                totalWeight += weight
                offset += 4
            }

            if (totalWeight < MINIMUM_CHROMATIC_WEIGHT) return null
            var best = -1
            for (i in weightSums.indices) {
                if (best < 0 || weightSums[i] > weightSums[best]) best = i
            }
            if (best < 0 || weightSums[best] <= 0) return null

            val sum = weightSums[best]
            val dominant = PaletteColor(
                red = weightedRed[best] / sum,
                green = weightedGreen[best] / sum,
                blue = weightedBlue[best] / sum,
            )
            return ArtworkColors(
                dominant = dominant,
                accent = accent(dominant),
                backgroundTop = backgroundTop(dominant),
                backgroundBottom = backgroundBottom(dominant),
            )
        }

        /**
         * The control accent: vivid and mid-brightness so it reads as a tint
         * on dark backgrounds without glowing (fixed clamps, both ports).
         */
        internal fun accent(dominant: PaletteColor): PaletteColor {
            val (h, s, v) = rgbToHSV(dominant.red, dominant.green, dominant.blue)
            return hsvToColor(h, minOf(1.0, maxOf(0.45, s * 1.2)), minOf(0.80, maxOf(0.55, v)))
        }

        /** Deep tinted stop — clearly the cover's hue, near-black in value. */
        internal fun backgroundTop(dominant: PaletteColor): PaletteColor {
            val (h, s, v) = rgbToHSV(dominant.red, dominant.green, dominant.blue)
            return hsvToColor(h, minOf(1.0, s * 0.60), maxOf(0.10, v * 0.32))
        }

        /** The gradient's floor: barely-tinted near-black. */
        internal fun backgroundBottom(dominant: PaletteColor): PaletteColor {
            val (h, s, v) = rgbToHSV(dominant.red, dominant.green, dominant.blue)
            return hsvToColor(h, minOf(1.0, s * 0.35), maxOf(0.04, v * 0.10))
        }

        // MARK: HSV conversion

        /** Returns hue in 0.0..<1.0, saturation and value in 0.0..1.0. */
        internal fun rgbToHSV(
            red: Double,
            green: Double,
            blue: Double,
        ): Triple<Double, Double, Double> {
            val maxValue = maxOf(red, green, blue)
            val minValue = minOf(red, green, blue)
            val delta = maxValue - minValue
            if (maxValue <= 0 || delta <= 0) return Triple(0.0, 0.0, maxValue)
            var hue: Double = if (maxValue == red) {
                ((green - blue) / delta).rem(6)
            } else if (maxValue == green) {
                (blue - red) / delta + 2
            } else {
                (red - green) / delta + 4
            }
            hue /= 6
            if (hue < 0) hue += 1
            return Triple(hue, delta / maxValue, maxValue)
        }

        internal fun hsvToColor(hue: Double, saturation: Double, value: Double): PaletteColor {
            val sector = (((hue * 6).toInt() % 6) + 6) % 6
            val fraction = hue * 6 - (hue * 6).toInt()
            val p = value * (1 - saturation)
            val q = value * (1 - fraction * saturation)
            val t = value * (1 - (1 - fraction) * saturation)
            return when (sector) {
                0 -> PaletteColor(red = value, green = t, blue = p)
                1 -> PaletteColor(red = q, green = value, blue = p)
                2 -> PaletteColor(red = p, green = value, blue = t)
                3 -> PaletteColor(red = p, green = q, blue = value)
                4 -> PaletteColor(red = t, green = p, blue = value)
                else -> PaletteColor(red = value, green = p, blue = q)
            }
        }
    }
}
