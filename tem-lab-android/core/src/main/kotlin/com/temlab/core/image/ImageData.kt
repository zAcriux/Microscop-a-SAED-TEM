package com.temlab.core.image

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/** Original numeric type of the source data. Calculations are always done in 32-bit float. */
enum class PixelType(val bits: Int, val label: String) {
    UINT8(8, "8-bit unsigned"), INT8(8, "8-bit signed"),
    UINT16(16, "16-bit unsigned"), INT16(16, "16-bit signed"),
    UINT32(32, "32-bit unsigned"), INT32(32, "32-bit signed"),
    FLOAT32(32, "32-bit float"), FLOAT64(64, "64-bit float"),
    COMPLEX64(64, "complex 64 (magnitude used)"), RGB24(24, "RGB (converted to luminance)"),
    FLOAT16(16, "16-bit float"), BINARY(1, "binary");
}

/** Spatial length units supported by the app. Factor is metres per unit. */
enum class LengthUnit(val symbol: String, val metres: Double) {
    PM("pm", 1e-12), ANGSTROM("Å", 1e-10), NM("nm", 1e-9), UM("µm", 1e-6), MM("mm", 1e-3), PIXEL("px", Double.NaN);

    fun convert(value: Double, to: LengthUnit): Double {
        if (this == PIXEL || to == PIXEL) return value
        return value * metres / to.metres
    }

    companion object {
        /** Parse a unit string as written by DM, ImageJ, TIFF vendors etc. Returns null if unknown/reciprocal. */
        fun parse(s: String?): LengthUnit? {
            if (s == null) return null
            val t = s.trim().replace("µ", "u").replace("μ", "u").lowercase()
            return when (t) {
                "pm", "picometer", "picometre" -> PM
                "a", "å", "Å".lowercase(), "angstrom", "ang", "angstroms" -> ANGSTROM
                "nm", "nanometer", "nanometre", "nanometers" -> NM
                "um", "micron", "microns", "micrometer", "micrometre", "micrometers" -> UM
                "mm", "millimeter", "millimetre" -> MM
                "m", "meter", "metre" -> null // handled by caller via scaling
                "px", "pixel", "pixels", "" -> PIXEL
                else -> null
            }
        }
    }
}

/**
 * Spatial calibration. pixelWidth/pixelHeight are the physical size of one pixel (X and Y independently)
 * in [unit]. When [reciprocal] is true the image is itself in reciprocal space (e.g. a diffraction pattern
 * in 1/nm), and pixel sizes are expressed in 1/[unit].
 */
@Serializable
data class Calibration(
    val pixelWidth: Double = 1.0,
    val pixelHeight: Double = 1.0,
    val unit: LengthUnit = LengthUnit.PIXEL,
    val reciprocal: Boolean = false,
    val source: String = "none",
) {
    val isCalibrated: Boolean get() = unit != LengthUnit.PIXEL
    val isIsotropic: Boolean get() = abs(pixelWidth - pixelHeight) <= 1e-9 * maxOf(pixelWidth, pixelHeight)
    val unitLabel: String get() = if (reciprocal) "1/${unit.symbol}" else unit.symbol

    /** Physical length of a segment given in pixel coordinates (handles anisotropic X/Y calibration). */
    fun length(dxPx: Double, dyPx: Double): Double {
        val x = dxPx * pixelWidth
        val y = dyPx * pixelHeight
        return sqrt(x * x + y * y)
    }

    fun area(pixels: Double): Double = pixels * pixelWidth * pixelHeight

    /** Return an equivalent calibration expressed in another unit (real space only). */
    fun inUnit(target: LengthUnit): Calibration {
        if (!isCalibrated || target == LengthUnit.PIXEL || reciprocal) return this
        val f = unit.convert(1.0, target)
        return copy(pixelWidth = pixelWidth * f, pixelHeight = pixelHeight * f, unit = target)
    }

    companion object {
        val NONE = Calibration()

        /** Picks a convenient unit for a pixel size given in metres. */
        fun fromMetres(pw: Double, ph: Double, source: String): Calibration {
            val unit = when {
                pw < 1e-9 -> LengthUnit.PM
                pw < 1e-6 -> LengthUnit.NM
                pw < 1e-3 -> LengthUnit.UM
                else -> LengthUnit.MM
            }
            return Calibration(pw / unit.metres, ph / unit.metres, unit, false, source)
        }
    }
}

/**
 * A single 2-D image held as 32-bit floats (row-major, y * width + x). The original pixel type is kept
 * for display/metadata purposes; scientific calculations never quantise to 8 bit.
 */
class ImageData(
    val width: Int,
    val height: Int,
    val pixels: FloatArray,
    val pixelType: PixelType = PixelType.FLOAT32,
    var calibration: Calibration = Calibration.NONE,
    val metadata: MutableMap<String, String> = linkedMapOf(),
    var name: String = "image",
) {
    init {
        require(width > 0 && height > 0) { "Invalid dimensions ${width}x$height" }
        require(pixels.size == width * height) { "Pixel buffer size ${pixels.size} != ${width * height}" }
    }

    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]
    operator fun set(x: Int, y: Int, v: Float) { pixels[y * width + x] = v }

    /** Bilinear interpolation, clamped to the image. */
    fun sample(x: Double, y: Double): Double {
        val xc = x.coerceIn(0.0, (width - 1).toDouble())
        val yc = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = xc.toInt(); val y0 = yc.toInt()
        val x1 = minOf(x0 + 1, width - 1); val y1 = minOf(y0 + 1, height - 1)
        val fx = xc - x0; val fy = yc - y0
        val a = this[x0, y0]; val b = this[x1, y0]; val c = this[x0, y1]; val d = this[x1, y1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    fun copy(pixels: FloatArray = this.pixels.copyOf(), name: String = this.name): ImageData =
        ImageData(width, height, pixels, pixelType, calibration, LinkedHashMap(metadata), name)

    fun withPixels(w: Int, h: Int, px: FloatArray, cal: Calibration = calibration): ImageData =
        ImageData(w, h, px, pixelType, cal, LinkedHashMap(metadata), name)

    fun minMax(): Pair<Float, Float> {
        var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY
        for (v in pixels) { if (v.isNaN()) continue; if (v < mn) mn = v; if (v > mx) mx = v }
        if (mn > mx) { mn = 0f; mx = 1f }
        return mn to mx
    }

    /** Extracts a rectangular region (clamped). Calibration is preserved (crop does not alter pixel size). */
    fun crop(x0: Int, y0: Int, w: Int, h: Int): ImageData {
        val xs = x0.coerceIn(0, width - 1); val ys = y0.coerceIn(0, height - 1)
        val ww = w.coerceAtMost(width - xs); val hh = h.coerceAtMost(height - ys)
        val out = FloatArray(ww * hh)
        for (y in 0 until hh) System.arraycopy(pixels, (ys + y) * width + xs, out, y * ww, ww)
        return ImageData(ww, hh, out, pixelType, calibration, LinkedHashMap(metadata), name).also {
            it.metadata["Crop origin (px)"] = "$xs,$ys"
        }
    }

    val bitDepthLabel: String get() = pixelType.label
}
