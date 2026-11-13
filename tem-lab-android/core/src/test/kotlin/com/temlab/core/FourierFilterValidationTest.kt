/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core

import com.temlab.core.fft.FftAnalysis
import com.temlab.core.fft.FftOptions
import com.temlab.core.fft.FourierFilter
import com.temlab.core.fft.FourierMask
import com.temlab.core.fft.MaskShape
import com.temlab.core.fft.SquareRoi
import com.temlab.core.measure.Profiles
import com.temlab.core.measure.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FourierFilterValidationTest {
    private val pw = 0.02
    private val f1 = Synthetic.Fringe(0.32, 15.0, 1.0)
    private val f2 = Synthetic.Fringe(0.20, 75.0, 1.0, 0.7)
    private val n = 256

    private fun offset(f: Synthetic.Fringe): Pair<Double, Double> {
        val a = Math.toRadians(f.angleDeg)
        return (cos(a) / f.dNm) / (1.0 / (n * pw)) to (-sin(a) / f.dNm) / (1.0 / (n * pw))
    }

    private fun corr(a: FloatArray, b: FloatArray): Double {
        val ma = a.average(); val mb = b.average()
        var s = 0.0; var sa = 0.0; var sb = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; s += x * y; sa += x * x; sb += y * y }
        return s / sqrt(sa * sb)
    }

    @Test fun selectingOneReflectionPairRecoversThatPeriodicityOnly() {
        val mixed = Synthetic.lattice(n, n, pw, pw, listOf(f1, f2), noise = 0.5)
        val only1 = Synthetic.lattice(n, n, pw, pw, listOf(f1))
        val (dx, dy) = offset(f1)
        val res = FourierFilter.apply(mixed, SquareRoi(0, 0, n), FourierMask(listOf(MaskShape.Circle(dx, dy, 6.0, 2.0)), symmetric = true, keepDc = true))
        assertTrue("imag residual ${res.imaginaryResidual}", res.imaginaryResidual < 1e-9)
        val c = corr(res.reconstruction.pixels, only1.pixels)
        assertTrue("correlation with pure component $c", c > 0.97)
        assertTrue("other component suppressed", corr(res.reconstruction.pixels, Synthetic.lattice(n, n, pw, pw, listOf(f2)).pixels) < 0.05)
        // Mean preserved through the DC term
        assertEquals(mixed.pixels.average(), res.reconstruction.pixels.average(), 0.05)
        // Calibration preserved
        assertEquals(pw, res.reconstruction.calibration.pixelWidth, 1e-15)
        // Periodicity of the filtered image matches the FFT-measured spacing
        val fft = FftAnalysis.compute(res.reconstruction, SquareRoi(0, 0, n), FftOptions(paddedSize = 1024))
        val a = Math.toRadians(f1.angleDeg)
        val (u, v) = fft.toFftPixel(cos(a) / f1.dNm, -sin(a) / f1.dNm)
        assertEquals(0.32, FftAnalysis.locatePeak(fft, u, v)!!.d, 0.32 * 0.003)
    }

    @Test fun asymmetricMaskBreaksHermitianSymmetryAndSymmetricOptionRestoresIt() {
        val img = Synthetic.lattice(n, n, pw, pw, listOf(f1, f2))
        val (dx, dy) = offset(f2)
        val one = FourierFilter.apply(img, SquareRoi(0, 0, n), FourierMask(listOf(MaskShape.Circle(dx, dy, 5.0)), symmetric = false, keepDc = false))
        val sym = FourierFilter.apply(img, SquareRoi(0, 0, n), FourierMask(listOf(MaskShape.Circle(dx, dy, 5.0)), symmetric = true, keepDc = false))
        assertTrue("one-sided residual ${one.imaginaryResidual}", one.imaginaryResidual > 0.3)
        assertTrue(sym.imaginaryResidual < 1e-9)
        // Mask is centro-symmetric on the discrete grid
        val m = sym.mask; val c = n / 2
        for (v in 1 until n) for (u in 1 until n) assertEquals(m[v * n + u], m[(2 * c - v) * n + (2 * c - u)], 0f)
    }

    @Test fun oddSizedRoiKeepsHermitianSymmetry() {
        val img = Synthetic.lattice(201, 201, pw, pw, listOf(f1))
        val res = FourierFilter.apply(img, SquareRoi(0, 0, 201), FourierMask(listOf(MaskShape.Ellipse(20.0, -5.0, 6.0, 3.0, 30.0, 1.0)), symmetric = true))
        assertTrue(res.imaginaryResidual < 1e-9)
    }

    @Test fun lowPassHighPassAndBandPassAreComplementary() {
        val img = Synthetic.lattice(n, n, pw, pw, listOf(f1, f2), noise = 1.0)
        val lp = FourierFilter.apply(img, SquareRoi(0, 0, n), FourierMask(listOf(MaskShape.LowPass(30.0)), symmetric = true, keepDc = true))
        val hp = FourierFilter.apply(img, SquareRoi(0, 0, n), FourierMask(listOf(MaskShape.LowPass(30.0)), symmetric = true, invert = true, keepDc = false))
        for (i in img.pixels.indices step 97) assertEquals(img.pixels[i].toDouble(), (lp.reconstruction.pixels[i] + hp.reconstruction.pixels[i]).toDouble(), 1e-3)
        // f1 (|g|=3.125 nm^-1 → 16 px) passes the band 10–20 px, f2 (5 nm^-1 → 25.6 px) does not
        val band = FourierFilter.apply(img, SquareRoi(0, 0, n), FourierMask(listOf(MaskShape.BandPass(10.0, 20.0, 1.0))))
        assertTrue(corr(band.reconstruction.pixels, Synthetic.lattice(n, n, pw, pw, listOf(f1)).pixels) > 0.9)
    }

    @Test fun gaussianMaskIsSmooth() {
        val m = FourierFilter.buildMask(FourierMask(listOf(MaskShape.Gaussian(20.0, 0.0, 3.0)), keepDc = false), 128)
        assertEquals(1.0, m[64 * 128 + 84].toDouble(), 1e-6)
        assertEquals(kotlin.math.exp(-0.5), m[64 * 128 + 87].toDouble(), 1e-6)
        assertEquals(1.0, m[64 * 128 + 44].toDouble(), 1e-6) // Friedel mate
    }

    @Test fun periodicityAnalysisOfFilteredFringes() {
        val img = Synthetic.lattice(n, n, pw, pw, listOf(Synthetic.Fringe(0.25, 0.0)), noise = 0.3)
        // Profile perpendicular to the fringes (along x) across the image
        val prof = Profiles.line(img, Pt(10.0, 128.0), Pt(245.0, 128.0), width = 5)
        val per = Profiles.periodicity(prof, 0.0)
        assertEquals(0.25, per.periodFft, 0.25 * 0.01)
        assertEquals(0.25, per.periodPeaks, 0.25 * 0.01)
        assertTrue(per.peakCount >= 15)
        assertEquals(90.0, per.fringeOrientationDeg, 1e-9)
    }
}
