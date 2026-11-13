package com.temlab.core.fft

import com.temlab.core.image.ImageData
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Shapes of a Fourier mask. Coordinates are FFT pixels relative to the zero frequency (dx, dy). */
@Serializable
sealed class MaskShape {
    abstract val softness: Double // Gaussian edge width (FFT px); 0 = hard edge

    @Serializable data class Circle(val dx: Double, val dy: Double, val radius: Double, override val softness: Double = 0.0) : MaskShape()
    @Serializable data class Ellipse(val dx: Double, val dy: Double, val rx: Double, val ry: Double, val angleDeg: Double = 0.0, override val softness: Double = 0.0) : MaskShape()
    @Serializable data class Rect(val dx: Double, val dy: Double, val halfW: Double, val halfH: Double, override val softness: Double = 0.0) : MaskShape()
    /** Gaussian spot centred at (dx, dy): weight = exp(-r²/2σ²). */
    @Serializable data class Gaussian(val dx: Double, val dy: Double, val sigma: Double, override val softness: Double = 0.0) : MaskShape()
    /** Radial filters around the origin. */
    @Serializable data class LowPass(val radius: Double, override val softness: Double = 0.0) : MaskShape()
    @Serializable data class HighPass(val radius: Double, override val softness: Double = 0.0) : MaskShape()
    @Serializable data class BandPass(val inner: Double, val outer: Double, override val softness: Double = 0.0) : MaskShape()

    /** Weight in [0,1] at offset (x, y) from the zero frequency. */
    fun weight(x: Double, y: Double): Double {
        fun edge(dist: Double): Double = // dist <= 0 inside; >0 outside, in px
            if (dist <= 0) 1.0 else if (softness <= 0) 0.0 else exp(-dist * dist / (2 * softness * softness))
        return when (this) {
            is Circle -> edge(hypot(x - dx, y - dy) - radius)
            is Ellipse -> {
                val a = Math.toRadians(angleDeg)
                val xr = (x - dx) * cos(a) + (y - dy) * sin(a)
                val yr = -(x - dx) * sin(a) + (y - dy) * cos(a)
                val q = hypot(xr / rx, yr / ry)
                edge((q - 1) * minOf(rx, ry))
            }
            is Rect -> edge(max(abs(x - dx) - halfW, abs(y - dy) - halfH))
            is Gaussian -> { val r = hypot(x - dx, y - dy); exp(-r * r / (2 * sigma * sigma)) }
            is LowPass -> edge(hypot(x, y) - radius)
            is HighPass -> edge(radius - hypot(x, y))
            is BandPass -> { val r = hypot(x, y); edge(max(inner - r, r - outer)) }
        }
    }
}

@Serializable
data class FourierMask(
    val shapes: List<MaskShape> = emptyList(),
    /** Adds the Friedel mate (−g) of every shape so the filtered spectrum stays Hermitian (real reconstruction). */
    val symmetric: Boolean = true,
    val invert: Boolean = false,
    /** Keep the zero-frequency (mean) term so the reconstruction keeps the original mean intensity. */
    val keepDc: Boolean = true,
) {
    fun describe(): String = buildString {
        append("${shapes.size} shape(s): ")
        append(shapes.joinToString("; ") { it.toString().substringAfter("MaskShape.") })
        append(" | symmetric=$symmetric invert=$invert keepDC=$keepDc")
    }
}

class FilterResult(
    val reconstruction: ImageData,
    val mask: FloatArray,          // n×n shifted mask
    val n: Int,
    /** max|Im| / max|Re| of the inverse transform: ≈ 0 when Hermitian symmetry is preserved. */
    val imaginaryResidual: Double,
    val description: String,
)

object FourierFilter {

    /** Builds the n×n (shifted) weight map. */
    fun buildMask(mask: FourierMask, n: Int): FloatArray {
        val c = n / 2
        val m = FloatArray(n * n)
        for (v in 0 until n) for (u in 0 until n) {
            val x = (u - c).toDouble(); val y = (v - c).toDouble()
            var w = 0.0
            for (s in mask.shapes) {
                w = max(w, s.weight(x, y))
                if (mask.symmetric) w = max(w, s.weight(-x, -y))
            }
            if (mask.invert) w = 1 - w
            m[v * n + u] = w.toFloat()
        }
        if (mask.symmetric) { // exact symmetrisation on the discrete grid (partner index (2c - i) mod n)
            for (v in 0 until n) for (u in 0 until n) {
                val pu = Math.floorMod(2 * c - u, n); val pv = Math.floorMod(2 * c - v, n)
                val a = m[v * n + u]; val b = m[pv * n + pu]
                val mm = maxOf(a, b); m[v * n + u] = mm; m[pv * n + pu] = mm
            }
        }
        if (mask.keepDc) m[c * n + c] = 1f
        return m
    }

    /**
     * Filters a square region: FFT (no window, no padding so that the inverse maps 1:1 onto the ROI pixels),
     * multiplication by the mask, inverse FFT, real part. The calibration of the region is preserved.
     */
    fun apply(img: ImageData, roi0: SquareRoi, mask: FourierMask): FilterResult {
        val roi = roi0.clampedTo(img.width, img.height)
        val n = roi.size
        val re = DoubleArray(n * n); val im = DoubleArray(n * n)
        for (y in 0 until n) for (x in 0 until n) re[y * n + x] = img[roi.x + x, roi.y + y].toDouble()
        FFT.transform2D(re, im, n, n, false)
        val sr = FFT.shift(re, n, n); val si = FFT.shift(im, n, n)
        return applyToSpectrum(sr, si, n, mask, img, roi)
    }

    fun applyToSpectrum(shRe: DoubleArray, shIm: DoubleArray, n: Int, mask: FourierMask, src: ImageData, roi: SquareRoi): FilterResult {
        val m = buildMask(mask, n)
        val fr = DoubleArray(n * n) { shRe[it] * m[it] }
        val fi = DoubleArray(n * n) { shIm[it] * m[it] }
        val ur = FFT.shift(fr, n, n, inverse = true); val ui = FFT.shift(fi, n, n, inverse = true)
        FFT.transform2D(ur, ui, n, n, true)
        var maxRe = 0.0; var maxIm = 0.0
        for (i in ur.indices) { maxRe = max(maxRe, abs(ur[i])); maxIm = max(maxIm, abs(ui[i])) }
        val out = FloatArray(n * n) { ur[it].toFloat() }
        val desc = "Fourier-filtered (IFFT) region x=${roi.x}, y=${roi.y}, ${n}×$n px. Mask: ${mask.describe()}"
        val rec = ImageData(n, n, out, com.temlab.core.image.PixelType.FLOAT32, src.calibration,
            linkedMapOf("Processing" to desc, "Source" to src.name, "WARNING" to "Filtered image: may contain filtering artefacts; not an independent observation"),
            "${src.name} – IFFT")
        return FilterResult(rec, m, n, if (maxRe > 0) maxIm / maxRe else 0.0, desc)
    }

    /** Convenience: spot masks around reflections, given in FFT pixels relative to the origin. */
    fun spots(points: List<Pair<Double, Double>>, radius: Double, soft: Double = radius / 3, gaussian: Boolean = false): List<MaskShape> =
        points.map { (dx, dy) -> if (gaussian) MaskShape.Gaussian(dx, dy, radius) else MaskShape.Circle(dx, dy, radius, soft) }
}
