/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core

import com.temlab.core.fft.FFT
import com.temlab.core.fft.FftAnalysis
import com.temlab.core.fft.FftOptions
import com.temlab.core.fft.FftResult
import com.temlab.core.fft.SquareRoi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FftValidationTest {

    @Test fun fftMatchesDirectDftForPowerOfTwoAndArbitrarySizes() {
        val rnd = Random(3)
        for (n in listOf(2, 8, 64, 3, 12, 15, 100, 127)) {
            val re = DoubleArray(n) { rnd.nextGaussian() }; val im = DoubleArray(n) { rnd.nextGaussian() }
            val (er, ei) = FFT.naiveDft(re, im, false)
            val r = re.copyOf(); val i = im.copyOf()
            FFT.transform(r, i, false)
            for (k in 0 until n) { assertEquals("n=$n re[$k]", er[k], r[k], 1e-9 * n); assertEquals("n=$n im[$k]", ei[k], i[k], 1e-9 * n) }
            FFT.transform(r, i, true)
            for (k in 0 until n) { assertEquals(re[k], r[k], 1e-10 * n); assertEquals(im[k], i[k], 1e-10 * n) }
        }
    }

    @Test fun integerFrequencyLandsOnExpectedBin() {
        // cos with exactly 16 cycles across 256 px along x -> peak at u = 128 ± 16
        val n = 256
        val img = com.temlab.core.image.ImageData(n, n, FloatArray(n * n) { val x = it % n; cos(2 * Math.PI * 16 * x / n).toFloat() })
        val fft = FftAnalysis.compute(img, SquareRoi(0, 0, n), FftOptions(hannWindow = false))
        val r = FftAnalysis.locatePeak(fft, 128.0 + 16, 128.0)!!
        assertEquals(144.0, r.u, 1e-6); assertEquals(128.0, r.v, 1e-6)
        assertEquals(16.0 / n, r.g, 1e-12) // 1/px
        assertEquals(n / 16.0, r.d, 1e-9)
    }

    private fun measureAt(fft: FftResult, dNm: Double, angleDeg: Double) = run {
        val a = Math.toRadians(angleDeg)
        val (u, v) = fft.toFftPixel(cos(a) / dNm, -sin(a) / dNm)
        FftAnalysis.locatePeak(fft, u, v, radius = 4)!!
    }

    @Test fun interplanarDistanceCalibratedIsotropic() {
        // Au(111) 0.2355 nm and Au(200) 0.2039 nm at 54.74°, pixel 0.0189 nm (typical HRTEM)
        val img = Synthetic.lattice(512, 512, 0.0189, 0.0189, listOf(Synthetic.Fringe(0.2355, 20.0), Synthetic.Fringe(0.2039, 20.0 + 54.74, 0.7)), noise = 0.3)
        for (pad in listOf(0, 512, 1024)) for (hann in listOf(true, false)) {
            val fft = FftAnalysis.compute(img, SquareRoi(100, 80, 256), FftOptions(hannWindow = hann, paddedSize = pad))
            val r1 = measureAt(fft, 0.2355, 20.0); val r2 = measureAt(fft, 0.2039, 74.74)
            val tol = if (pad >= 512 && hann) 0.002 else 0.01
            assertEquals("d111 pad=$pad hann=$hann", 0.2355, r1.d, 0.2355 * tol)
            assertEquals("d200 pad=$pad hann=$hann", 0.2039, r2.d, 0.2039 * tol)
            assertEquals("angle pad=$pad hann=$hann", 54.74, FftAnalysis.angleBetween(r1, r2), if (pad >= 512) 0.3 else 1.0)
            assertEquals(20.0, r1.angleDeg, 1.0)
            assertEquals("nm", r1.unit)
        }
    }

    @Test fun reciprocalCalibrationIncludesZeroPadding() {
        val img = Synthetic.lattice(256, 256, 0.02, 0.02, listOf(Synthetic.Fringe(0.25, 0.0)))
        val f1 = FftAnalysis.compute(img, SquareRoi(0, 0, 256), FftOptions(paddedSize = 0))
        val f4 = FftAnalysis.compute(img, SquareRoi(0, 0, 256), FftOptions(paddedSize = 1024))
        assertEquals(1.0 / (256 * 0.02), f1.dkx, 1e-12)
        assertEquals(1.0 / (1024 * 0.02), f4.dkx, 1e-12)
        // Same physical reflection appears 4× further from the origin (in FFT px) in the padded transform.
        val a = measureAt(f1, 0.25, 0.0); val b = measureAt(f4, 0.25, 0.0)
        assertEquals((a.u - 128) * 4, b.u - 512, 0.5)
        assertEquals(a.d, b.d, 0.25 * 0.01)
    }

    @Test fun anisotropicPixelCalibration() {
        val img = Synthetic.lattice(512, 512, 0.02, 0.03, listOf(Synthetic.Fringe(0.31, 35.0), Synthetic.Fringe(0.27, 110.0)))
        val fft = FftAnalysis.compute(img, SquareRoi(0, 0, 512), FftOptions(paddedSize = 1024))
        assertTrue(abs(fft.dkx - fft.dky) > 1e-6)
        val r1 = measureAt(fft, 0.31, 35.0); val r2 = measureAt(fft, 0.27, 110.0)
        assertEquals(0.31, r1.d, 0.31 * 0.003)
        assertEquals(0.27, r2.d, 0.27 * 0.003)
        assertEquals(75.0, FftAnalysis.angleBetween(r1, r2), 0.4)
    }

    @Test fun symmetricPairCancelsOriginError() {
        val img = Synthetic.lattice(256, 256, 0.02, 0.02, listOf(Synthetic.Fringe(0.21, 40.0)), noise = 0.5)
        val fft = FftAnalysis.compute(img, SquareRoi(0, 0, 256), FftOptions(paddedSize = 512))
        val a = measureAt(fft, 0.21, 40.0); val b = measureAt(fft, 0.21, 220.0)
        val pair = FftAnalysis.symmetricPair(fft, a, b)
        assertEquals(0.21, pair.dMean, 0.21 * 0.003)
        assertTrue("origin offset ${pair.originOffsetPx}", pair.originOffsetPx < 0.2)
        assertTrue(pair.dDifferencePercent < 0.5)
        assertEquals(180.0, FftAnalysis.angleBetween(a, b), 0.2)
    }

    @Test fun tapFarFromPeakSnapsToNearestMaximumWithinRadius() {
        val img = Synthetic.lattice(256, 256, 0.02, 0.02, listOf(Synthetic.Fringe(0.2, 0.0)))
        val fft = FftAnalysis.compute(img, SquareRoi(0, 0, 256), FftOptions(paddedSize = 0))
        val exact = 128 + 256 * 0.02 / 0.2 // 153.6
        val r = FftAnalysis.locatePeak(fft, exact + 3, 128.0 - 2, radius = 6)!!
        assertEquals(exact, r.u, 0.25)
        assertTrue(r.refined)
    }

    @Test fun semiAutomaticDetectionFindsFriedelPairs() {
        val img = Synthetic.lattice(256, 256, 0.02, 0.02, listOf(Synthetic.Fringe(0.25, 10.0), Synthetic.Fringe(0.2, 70.0, 0.8)), noise = 0.2)
        val fft = FftAnalysis.compute(img, SquareRoi(0, 0, 256), FftOptions(paddedSize = 512))
        val peaks = FftAnalysis.detectPeaks(fft, sigma = 6.0, minSeparation = 6)
        val ds = peaks.take(4).map { it.d }.sorted()
        assertEquals(4, peaks.take(4).size)
        assertEquals(0.2, ds[0], 0.002); assertEquals(0.2, ds[1], 0.002)
        assertEquals(0.25, ds[2], 0.0025); assertEquals(0.25, ds[3], 0.0025)
    }

    @Test fun uncalibratedImageGivesDistancesInPixels() {
        val n = 128
        val img = com.temlab.core.image.ImageData(n, n, FloatArray(n * n) { val y = it / n; cos(2 * Math.PI * y / 8.0).toFloat() })
        val fft = FftAnalysis.compute(img, SquareRoi(0, 0, n), FftOptions(hannWindow = false))
        val r = FftAnalysis.locatePeak(fft, 64.0, 64.0 + 16)!!
        assertEquals("px", r.unit)
        assertEquals(8.0, r.d, 1e-6)
        assertEquals(90.0, abs(r.angleDeg), 1e-6)
    }

    @Test fun radialProfilePeaksAtLatticeFrequency() {
        val img = Synthetic.lattice(256, 256, 0.02, 0.02, listOf(Synthetic.Fringe(0.2, 0.0), Synthetic.Fringe(0.2, 60.0), Synthetic.Fringe(0.2, 120.0)))
        val fft = FftAnalysis.compute(img, SquareRoi(0, 0, 256), FftOptions(paddedSize = 256))
        val (g, a) = FftAnalysis.radialProfile(fft)
        var best = 3
        for (i in 3 until a.size) if (a[i] > a[best]) best = i
        assertEquals(5.0, g[best], fft.dkx * 1.01)
    }
}
