/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core.fft

import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.image.LengthUnit
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Square region of interest in ORIGINAL image pixel coordinates (independent of the screen zoom). */
@Serializable
data class SquareRoi(val x: Int, val y: Int, val size: Int) {
    fun clampedTo(w: Int, h: Int): SquareRoi {
        val s = size.coerceIn(8, minOf(w, h))
        return SquareRoi(x.coerceIn(0, w - s), y.coerceIn(0, h - s), s)
    }
}

@Serializable
data class FftOptions(
    val hannWindow: Boolean = true,
    /** Transform size; >= roi size. Values larger than the ROI mean zero-padding. 0 = same as ROI. */
    val paddedSize: Int = 0,
    val subtractMean: Boolean = true,
)

enum class SpectrumScale { AMPLITUDE, POWER }

/**
 * FFT of a square ROI. The spectrum is stored shifted (zero frequency at index (n/2, n/2)).
 *
 * Reciprocal-space calibration: for a transform of size n computed from pixels of size (px, py),
 * one FFT pixel corresponds to Δk_x = 1 / (n·px) and Δk_y = 1 / (n·py). This holds for zero-padded
 * transforms (n > ROI size) because padding interpolates the spectrum without changing the sampling interval
 * of the real-space data.
 */
class FftResult(
    val n: Int,
    val roi: SquareRoi,
    val realCalibration: Calibration,
    val options: FftOptions,
    val re: DoubleArray,
    val im: DoubleArray,
) {
    val center: Int get() = n / 2
    val dkx: Double get() = 1.0 / (n * realCalibration.pixelWidth)
    val dky: Double get() = 1.0 / (n * realCalibration.pixelHeight)
    /** Unit of d-spacings (real-space unit, or pixels when uncalibrated). */
    val unit: LengthUnit get() = realCalibration.unit

    val amplitude: DoubleArray by lazy { DoubleArray(re.size) { hypot(re[it], im[it]) } }

    fun value(u: Int, v: Int): Double = amplitude[v * n + u]

    /** Display image (float) with optional log scaling. */
    fun display(scale: SpectrumScale, log: Boolean): FloatArray = FloatArray(n * n) { i ->
        val a = amplitude[i]
        val v = if (scale == SpectrumScale.POWER) a * a else a
        (if (log) ln(1.0 + v) else v).toFloat()
    }

    /** Spatial frequency (kx, ky) in 1/unit at FFT pixel coordinates (u, v), y pointing down as in the image. */
    fun frequency(u: Double, v: Double): Pair<Double, Double> = (u - center) * dkx to (v - center) * dky

    fun toFftPixel(kx: Double, ky: Double): Pair<Double, Double> = center + kx / dkx to center + ky / dky
}

@Serializable
data class Reflection(
    val id: Int,
    val u: Double, val v: Double,   // FFT pixel coordinates (sub-pixel)
    val kx: Double, val ky: Double, // reciprocal vector components, 1/unit
    val g: Double,                   // |g|, 1/unit
    val d: Double,                   // 1/|g|, unit
    val unit: String,
    val intensity: Double,
    val refined: Boolean,
    val angleDeg: Double,            // direction of g, degrees counter-clockwise from +x (screen y up)
    val roiLabel: String = "",
)

@Serializable
data class SymmetricPairResult(
    val a: Reflection, val b: Reflection,
    val dMean: Double, val gMean: Double,
    val dDifferencePercent: Double,
    /** Distance between the midpoint of the two reflections and the nominal origin, in FFT pixels. */
    val originOffsetPx: Double,
    val unit: String,
)

object FftAnalysis {

    fun compute(img: ImageData, roi0: SquareRoi, options: FftOptions = FftOptions()): FftResult {
        val roi = roi0.clampedTo(img.width, img.height)
        val s = roi.size
        val n = if (options.paddedSize > s) options.paddedSize else s
        val re = DoubleArray(n * n); val im = DoubleArray(n * n)
        var mean = 0.0
        if (options.subtractMean) {
            for (y in 0 until s) for (x in 0 until s) mean += img[roi.x + x, roi.y + y]
            mean /= (s * s).toDouble()
        }
        val win = if (options.hannWindow) DoubleArray(s) { 0.5 - 0.5 * cos(2 * PI * it / (s - 1)) } else null
        for (y in 0 until s) for (x in 0 until s) {
            var v = img[roi.x + x, roi.y + y] - mean
            if (win != null) v *= win[x] * win[y]
            re[y * n + x] = v
        }
        FFT.transform2D(re, im, n, n, false)
        return FftResult(n, roi, img.calibration, options, FFT.shift(re, n, n), FFT.shift(im, n, n))
    }

    /**
     * Finds the local maximum nearest to a tap at (u, v), searching within [radius] FFT pixels (the DC region
     * within [dcExclusion] pixels is ignored), and refines it to sub-pixel precision with a separable
     * Gaussian (log-parabolic) fit on the 3×3 neighbourhood. Falls back to the integer position when the fit
     * is ill-conditioned (e.g. flat or saturated peak).
     */
    fun locatePeak(fft: FftResult, u: Double, v: Double, radius: Int = 6, dcExclusion: Double = 2.0, id: Int = 0, label: String = ""): Reflection? {
        val n = fft.n; val c = fft.center
        var best = -1.0; var bu = -1; var bv = -1
        val u0 = u.toInt(); val v0 = v.toInt()
        for (dv in -radius..radius) for (du in -radius..radius) {
            if (du * du + dv * dv > radius * radius) continue
            val uu = u0 + du; val vv = v0 + dv
            if (uu < 1 || vv < 1 || uu >= n - 1 || vv >= n - 1) continue
            if (hypot((uu - c).toDouble(), (vv - c).toDouble()) <= dcExclusion) continue
            val a = fft.value(uu, vv)
            if (a > best) { best = a; bu = uu; bv = vv }
        }
        if (bu < 0) return null
        val (su, sv, ok) = refine(fft, bu, bv)
        return makeReflection(fft, su, sv, best, ok, id, label)
    }

    private fun refine(fft: FftResult, u: Int, v: Int): Triple<Double, Double, Boolean> {
        fun l(x: Int, y: Int) = ln(maxOf(fft.value(x, y), 1e-30))
        val c = l(u, v)
        val dx = fit(l(u - 1, v), c, l(u + 1, v))
        val dy = fit(l(u, v - 1), c, l(u, v + 1))
        if (dx == null || dy == null) return Triple(u.toDouble(), v.toDouble(), false)
        return Triple(u + dx, v + dy, true)
    }

    private fun fit(a: Double, b: Double, c: Double): Double? {
        val den = a - 2 * b + c
        if (den >= 0 || den.isNaN()) return null
        val d = 0.5 * (a - c) / den
        return if (abs(d) <= 0.75) d else null
    }

    fun makeReflection(fft: FftResult, u: Double, v: Double, intensity: Double, refined: Boolean, id: Int, label: String = ""): Reflection {
        val (kx, ky) = fft.frequency(u, v)
        val g = hypot(kx, ky)
        val unit = if (fft.realCalibration.isCalibrated) fft.unit.symbol else "px"
        return Reflection(id, u, v, kx, ky, g, if (g > 0) 1.0 / g else Double.POSITIVE_INFINITY, unit, intensity, refined,
            Math.toDegrees(atan2(-ky, kx)), label)
    }

    /**
     * Uses two approximately Friedel-related reflections (g and −g). The averaged vector (g₁ − g₂)/2 is
     * insensitive to an error in the origin position, so d = 1/|(g₁ − g₂)/2| is the preferred estimate.
     */
    fun symmetricPair(fft: FftResult, a: Reflection, b: Reflection): SymmetricPairResult {
        val kx = (a.kx - b.kx) / 2; val ky = (a.ky - b.ky) / 2
        val g = hypot(kx, ky)
        val diff = abs(a.d - b.d) / ((a.d + b.d) / 2) * 100
        val mu = (a.u + b.u) / 2 - fft.center; val mv = (a.v + b.v) / 2 - fft.center
        return SymmetricPairResult(a, b, 1.0 / g, g, diff, hypot(mu, mv), a.unit)
    }

    /** Angle between two reciprocal vectors (degrees, 0–180), computed from calibrated components. */
    fun angleBetween(a: Reflection, b: Reflection): Double {
        val dot = a.kx * b.kx + a.ky * b.ky
        val c = (dot / (a.g * b.g)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    /**
     * Semi-automatic peak detection: local maxima of the amplitude spectrum that exceed
     * mean + [sigma]·std of log-amplitude (computed outside the DC disc), separated by at least [minSeparation].
     */
    fun detectPeaks(fft: FftResult, sigma: Double = 4.0, minSeparation: Int = 4, dcExclusion: Double = 4.0, maxPeaks: Int = 40): List<Reflection> {
        val n = fft.n; val c = fft.center
        val logA = DoubleArray(n * n) { ln(1 + fft.amplitude[it]) }
        var sum = 0.0; var sum2 = 0.0; var cnt = 0
        for (v in 0 until n) for (u in 0 until n) {
            if (hypot((u - c).toDouble(), (v - c).toDouble()) <= dcExclusion) continue
            val x = logA[v * n + u]; sum += x; sum2 += x * x; cnt++
        }
        val mean = sum / cnt; val sd = sqrt(maxOf(0.0, sum2 / cnt - mean * mean))
        val thr = mean + sigma * sd
        val cands = ArrayList<Triple<Int, Int, Double>>()
        for (v in 1 until n - 1) for (u in 1 until n - 1) {
            if (hypot((u - c).toDouble(), (v - c).toDouble()) <= dcExclusion) continue
            val x = logA[v * n + u]
            if (x < thr) continue
            var isMax = true
            loop@ for (dv in -1..1) for (du in -1..1) {
                if (du == 0 && dv == 0) continue
                if (logA[(v + dv) * n + u + du] > x) { isMax = false; break@loop }
            }
            if (isMax) cands += Triple(u, v, x)
        }
        cands.sortByDescending { it.third }
        val chosen = ArrayList<Triple<Int, Int, Double>>()
        for (p in cands) {
            if (chosen.size >= maxPeaks) break
            if (chosen.none { hypot((it.first - p.first).toDouble(), (it.second - p.second).toDouble()) < minSeparation }) chosen += p
        }
        return chosen.mapIndexed { i, (u, v, _) ->
            val (su, sv, ok) = refine(fft, u, v)
            makeReflection(fft, su, sv, fft.value(u, v), ok, i + 1)
        }
    }

    /** Azimuthally averaged amplitude versus |g| (bins of the smaller of Δkx, Δky). Returns (g, mean amplitude). */
    fun radialProfile(fft: FftResult): Pair<DoubleArray, DoubleArray> {
        val dk = minOf(fft.dkx, fft.dky)
        val nb = (fft.n / 2.0 * maxOf(fft.dkx, fft.dky) / dk).toInt() + 1
        val sum = DoubleArray(nb); val cnt = IntArray(nb)
        for (v in 0 until fft.n) for (u in 0 until fft.n) {
            val (kx, ky) = fft.frequency(u.toDouble(), v.toDouble())
            val b = (hypot(kx, ky) / dk + 0.5).toInt()
            if (b < nb) { sum[b] += fft.value(u, v); cnt[b]++ }
        }
        return DoubleArray(nb) { it * dk } to DoubleArray(nb) { if (cnt[it] > 0) sum[it] / cnt[it] else 0.0 }
    }

    /** Converts a length in [from] into a display unit (nm / Å). */
    fun convert(d: Double, from: String, to: LengthUnit): Double {
        val u = LengthUnit.parse(from) ?: return d
        if (u == LengthUnit.PIXEL) return d
        return u.convert(d, to)
    }

    fun log10Safe(x: Double) = if (x > 0) log10(x) else -30.0
}
