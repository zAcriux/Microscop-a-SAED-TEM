/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core.measure

import com.temlab.core.fft.FFT
import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

@Serializable
data class Pt(val x: Double, val y: Double)

enum class MeasureKind { DISTANCE, DIAMETER, RADIUS, ANGLE, POLYLINE, AREA, PERIMETER, FRINGES }

/** A manual measurement drawn on an image (points in original pixel coordinates). */
@Serializable
data class ManualMeasurement(
    val id: Int,
    val kind: MeasureKind,
    val points: List<Pt>,
    val value: Double,
    val unit: String,
    val label: String = "",
    /** Particle id this measurement duplicates (if its geometry falls on an auto-detected particle). */
    val linkedParticle: Int? = null,
    val includeInDistribution: Boolean = false,
    val periods: Int = 1,
)

object Measure {
    fun distance(cal: Calibration, a: Pt, b: Pt) = cal.length(b.x - a.x, b.y - a.y)

    /** Angle at vertex b formed by a-b-c, degrees (calibration-aware for anisotropic pixels). */
    fun angle(cal: Calibration, a: Pt, b: Pt, c: Pt): Double {
        val ux = (a.x - b.x) * cal.pixelWidth; val uy = (a.y - b.y) * cal.pixelHeight
        val vx = (c.x - b.x) * cal.pixelWidth; val vy = (c.y - b.y) * cal.pixelHeight
        val d = (ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))
        return Math.toDegrees(acos(d.coerceIn(-1.0, 1.0)))
    }

    /** Orientation of a segment, degrees counter-clockwise from +x (image y axis points down). */
    fun orientation(cal: Calibration, a: Pt, b: Pt) = Math.toDegrees(atan2(-(b.y - a.y) * cal.pixelHeight, (b.x - a.x) * cal.pixelWidth))

    fun polylineLength(cal: Calibration, pts: List<Pt>) = pts.zipWithNext().sumOf { (a, b) -> distance(cal, a, b) }

    /** Shoelace area of a closed polygon, physical units². */
    fun polygonArea(cal: Calibration, pts: List<Pt>): Double {
        var s = 0.0
        for (i in pts.indices) { val a = pts[i]; val b = pts[(i + 1) % pts.size]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2 * cal.pixelWidth * cal.pixelHeight
    }

    fun polygonPerimeter(cal: Calibration, pts: List<Pt>) = polylineLength(cal, pts + pts.first())

    fun manual(id: Int, kind: MeasureKind, pts: List<Pt>, cal: Calibration, periods: Int = 1): ManualMeasurement {
        val v = when (kind) {
            MeasureKind.DISTANCE, MeasureKind.DIAMETER -> distance(cal, pts[0], pts[1])
            MeasureKind.RADIUS -> distance(cal, pts[0], pts[1])
            MeasureKind.ANGLE -> angle(cal, pts[0], pts[1], pts[2])
            MeasureKind.POLYLINE -> polylineLength(cal, pts)
            MeasureKind.AREA -> polygonArea(cal, pts)
            MeasureKind.PERIMETER -> polygonPerimeter(cal, pts)
            MeasureKind.FRINGES -> distance(cal, pts[0], pts[1]) / max(1, periods)
        }
        val unit = when (kind) {
            MeasureKind.ANGLE -> "°"
            MeasureKind.AREA -> "${cal.unitLabel}²"
            else -> if (cal.isCalibrated) cal.unitLabel else "px"
        }
        return ManualMeasurement(id, kind, pts, v, unit, periods = periods)
    }
}

@Serializable
data class Profile(val positions: DoubleArray, val values: DoubleArray, val unit: String, val step: Double)

@Serializable
data class PeriodicityResult(
    val periodFft: Double,          // from the dominant Fourier component of the profile (sub-bin refined)
    val periodPeaks: Double,        // mean spacing between successive maxima
    val peakCount: Int,
    val peakPositions: List<Double>,
    val spacingSd: Double,
    val orientationDeg: Double,     // direction of the profile line
    val fringeOrientationDeg: Double, // fringes are perpendicular to the profile direction
    val unit: String,
)

object Profiles {
    /**
     * Intensity profile from a to b sampled every 1 px (bilinear), averaged over [width] px perpendicular
     * to the line. Positions are physical distances along the line.
     */
    fun line(img: ImageData, a: Pt, b: Pt, width: Int = 1): Profile {
        val len = hypot(b.x - a.x, b.y - a.y)
        val n = max(2, len.toInt() + 1)
        val ux = (b.x - a.x) / (n - 1); val uy = (b.y - a.y) / (n - 1)
        val nx = -(b.y - a.y) / max(len, 1e-9); val ny = (b.x - a.x) / max(len, 1e-9)
        val vals = DoubleArray(n); val pos = DoubleArray(n)
        val cal = img.calibration
        val stepPhys = cal.length(ux, uy)
        for (i in 0 until n) {
            var s = 0.0
            val half = (width - 1) / 2.0
            var k = -half
            var cnt = 0
            while (k <= half + 1e-9) {
                s += img.sample(a.x + i * ux + k * nx, a.y + i * uy + k * ny); cnt++; k += 1.0
            }
            vals[i] = s / cnt; pos[i] = i * stepPhys
        }
        return Profile(pos, vals, if (cal.isCalibrated) cal.unitLabel else "px", stepPhys)
    }

    /**
     * Estimates the periodicity of a profile: (1) dominant non-DC frequency of the Hann-windowed, zero-padded
     * profile with parabolic interpolation and (2) mean distance between local maxima of the lightly smoothed
     * profile. Both values are reported so they can be cross-checked with the 2-D FFT result.
     */
    fun periodicity(p: Profile, orientationDeg: Double = 0.0, minPeriodSamples: Double = 2.5): PeriodicityResult {
        val n = p.values.size
        val mean = p.values.average()
        val m = FFT.nextPowerOfTwo(n) * 8
        val re = DoubleArray(m); val im = DoubleArray(m)
        for (i in 0 until n) re[i] = (p.values[i] - mean) * (0.5 - 0.5 * cos(2 * PI * i / (n - 1)))
        FFT.transform(re, im, false)
        val amp = DoubleArray(m / 2) { hypot(re[it], im[it]) }
        val kMin = max(1, (m / (n.toDouble())).toInt() * 1) // ignore below one cycle per profile length
        val kMax = (m / minPeriodSamples).toInt().coerceAtMost(m / 2 - 2)
        var best = kMin
        for (k in kMin..kMax) if (amp[k] > amp[best]) best = k
        val a = amp[best - 1]; val b = amp[best]; val c = amp[best + 1]
        val den = a - 2 * b + c
        val delta = if (den < 0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
        val freq = (best + delta) / m // cycles per sample
        val periodFft = p.step / freq

        // Peak spacing on a 3-point smoothed profile, with a minimum separation of ~half the FFT period
        val sm = DoubleArray(n) { i -> (p.values[max(0, i - 1)] + p.values[i] + p.values[minOf(n - 1, i + 1)]) / 3 }
        val minSep = 0.5 / freq
        val peaks = ArrayList<Double>()
        var last = -1e9
        for (i in 1 until n - 1) if (sm[i] > sm[i - 1] && sm[i] >= sm[i + 1] && sm[i] > mean) {
            val aa = sm[i - 1]; val bb = sm[i]; val cc = sm[i + 1]
            val dd = aa - 2 * bb + cc
            val x = i + if (dd < 0) (0.5 * (aa - cc) / dd).coerceIn(-0.5, 0.5) else 0.0
            if (x - last >= minSep) { peaks += x; last = x }
        }
        val spacings = peaks.zipWithNext { u, v -> (v - u) * p.step }
        val periodPeaks = if (peaks.size >= 2) (peaks.last() - peaks.first()) / (peaks.size - 1) * p.step else Double.NaN
        val sMean = spacings.average()
        val sd = if (spacings.size > 1) kotlin.math.sqrt(spacings.sumOf { (it - sMean) * (it - sMean) } / (spacings.size - 1)) else 0.0
        val fo = orientationDeg + 90.0
        return PeriodicityResult(periodFft, periodPeaks, peaks.size, peaks.map { it * p.step }, sd, orientationDeg,
            ((fo % 180) + 180) % 180, p.unit)
    }

    @Suppress("unused") private fun rot(x: Double, y: Double, a: Double) = (x * cos(a) - y * sin(a)) to (x * sin(a) + y * cos(a))
}
