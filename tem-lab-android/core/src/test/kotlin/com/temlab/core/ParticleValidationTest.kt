/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core

import com.temlab.core.particles.ParticleFilter
import com.temlab.core.particles.ParticleSet
import com.temlab.core.particles.ParticleVariable
import com.temlab.core.segmentation.Polarity
import com.temlab.core.segmentation.SegmentationParams
import com.temlab.core.segmentation.ThresholdMethod
import com.temlab.core.stats.Statistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class ParticleValidationTest {
    private val px = 0.5 // nm per pixel

    private val circles = listOf(
        Synthetic.Blob(40.0, 40.0, 10.0, 10.0), Synthetic.Blob(110.0, 45.0, 15.0, 15.0), Synthetic.Blob(190.0, 50.0, 20.0, 20.0),
        Synthetic.Blob(50.0, 130.0, 12.0, 12.0), Synthetic.Blob(130.0, 140.0, 25.0, 25.0), Synthetic.Blob(215.0, 145.0, 8.0, 8.0),
    )

    private fun seg(img: com.temlab.core.image.ImageData, ws: Boolean = false, border: Boolean = true) =
        ParticleSet(img).also { it.segment(SegmentationParams(ThresholdMethod.OTSU, Polarity.DARK_OBJECTS, watershed = ws, excludeBorder = border, preSmoothSigma = 1.0, openRadius = 0)) }

    @Test fun circlesCountAreaDiameterCircularity() {
        val img = Synthetic.particles(256, 200, circles, pixelNm = px)
        val set = seg(img)
        assertEquals(6, set.included.size)
        for (b in circles) {
            val p = set.particleAt(b.cx.toInt(), b.cy.toInt())
            assertNotNull(p); val m = p!!.measurement!!
            val r = b.rx * px
            assertEquals("area r=${b.rx}", PI * r * r, m.area, PI * r * r * 0.04)
            assertEquals("ECD r=${b.rx}", 2 * r, m.ecd, 2 * r * 0.02)
            assertEquals("Feret max r=${b.rx}", 2 * r, m.feretMax, 0.8 * px) // ±0.8 px digitisation
            assertEquals("Feret min r=${b.rx}", 2 * r, m.feretMin, 2 * r * 0.05)
            assertEquals("perimeter r=${b.rx}", 2 * PI * r, m.perimeter, 2 * PI * r * 0.04)
            assertTrue("circularity ${m.circularity} r=${b.rx}", m.circularity > 0.93)
            assertEquals(1.0, m.aspectRatio, 0.06)
            assertEquals(b.cx * px, m.centroidX, 0.3 * px)
            assertEquals(b.cy * px, m.centroidY, 0.3 * px)
        }
    }

    @Test fun ellipsesAxesAndAspectRatio() {
        val e = listOf(Synthetic.Blob(70.0, 70.0, 40.0, 20.0, 30.0), Synthetic.Blob(180.0, 130.0, 30.0, 10.0, -60.0))
        val set = seg(Synthetic.particles(256, 200, e, pixelNm = px))
        assertEquals(2, set.included.size)
        for (b in e) {
            val m = set.particleAt(b.cx.toInt(), b.cy.toInt())!!.measurement!!
            assertEquals("major", 2 * b.rx * px, m.majorAxis, 2 * b.rx * px * 0.03)
            assertEquals("minor", 2 * b.ry * px, m.minorAxis, 2 * b.ry * px * 0.05)
            assertEquals("aspect", b.rx / b.ry, m.aspectRatio, b.rx / b.ry * 0.06)
            assertEquals("feret", 2 * b.rx * px, m.feretMax, 0.8 * px)
            assertEquals("angle", b.angleDeg.let { -it }, m.ellipseAngle, 2.0) // image y down vs. y-up angle convention
            assertTrue(m.circularity < 0.95)
        }
    }

    @Test fun watershedSeparatesTouchingParticles() {
        val pair = listOf(Synthetic.Blob(90.0, 100.0, 30.0, 30.0), Synthetic.Blob(140.0, 100.0, 25.0, 25.0))
        val img = Synthetic.particles(256, 200, pair, pixelNm = px)
        assertEquals(1, seg(img, ws = false).included.size)
        val set = seg(img, ws = true)
        assertEquals(2, set.included.size)
        val areas = set.included.map { it.measurement!!.area }.sorted()
        assertEquals(PI * (25 * px) * (25 * px), areas[0], areas[0] * 0.15)
    }

    @Test fun borderParticlesExcludedOnRequest() {
        val b = circles + Synthetic.Blob(5.0, 100.0, 12.0, 12.0)
        val img = Synthetic.particles(256, 200, b, pixelNm = px)
        assertEquals(6, seg(img, border = true).included.size)
        assertEquals(7, seg(img, border = false).included.size)
    }

    @Test fun editsUpdateMeasurementsAndHistogramImmediately() {
        val set = seg(Synthetic.particles(256, 200, circles, pixelNm = px))
        fun hist() = Statistics.histogram(set.values(ParticleVariable.ECD), 5)
        assertEquals(6, hist().total)
        val small = set.particleAt(215, 145)!!
        val rev0 = set.revision
        set.delete(small.id)
        assertEquals(5, hist().total); assertTrue(set.revision > rev0)
        val big = set.particleAt(130, 140)!!
        set.setExcluded(big.id, true)
        assertEquals(4, hist().total)
        assertFalse(set.values(ParticleVariable.ECD).any { abs(it - 25.0) < 1.0 })
        set.setExcluded(big.id, false)
        assertEquals(5, hist().total)
        // Manual addition of a square particle 20x20 px
        val added = set.addPolygon(listOf(10.0 to 170.0, 30.0 to 170.0, 30.0 to 190.0, 10.0 to 190.0))!!
        assertEquals(400 * px * px, added.measurement!!.area, 1e-9)
        assertEquals(6, hist().total)
        // Merge two particles then split them again with a cut line
        val a = set.particleAt(40, 40)!!; val b = set.particleAt(110, 45)!!
        val merged = set.merge(listOf(a.id, b.id))!!
        assertEquals(5, hist().total)
        assertEquals(a.measurement!!.area + b.measurement!!.area, merged.measurement!!.area, 1e-9)
        val parts = set.split(merged.id, 75.0, 0.0, 75.0, 100.0)
        assertEquals(2, parts.size)
        assertEquals(6, hist().total)
        // Contour edit: replace particle outline with a 10x10 square
        val c = set.particleAt(50, 130)!!
        set.replaceContour(c.id, listOf(45.0 to 125.0, 55.0 to 125.0, 55.0 to 135.0, 45.0 to 135.0))
        assertEquals(100 * px * px, set.byId(c.id)!!.measurement!!.area, 1e-9)
        // Size filter
        set.setFilter(ParticleFilter(minArea = 200.0))
        assertTrue(set.included.all { it.measurement!!.area >= 200.0 })
    }

    @Test fun adaptiveAndEdgeSegmentationAlsoFindParticles() {
        val img = Synthetic.particles(256, 200, circles, pixelNm = px)
        for (m in listOf(ThresholdMethod.ADAPTIVE, ThresholdMethod.EDGES)) {
            val set = ParticleSet(img)
            set.segment(SegmentationParams(m, Polarity.DARK_OBJECTS, adaptiveRadius = 40, adaptiveOffset = 0.5, watershed = false, openRadius = 1))
            set.setFilter(ParticleFilter(minArea = 10.0))
            assertEquals("$m", 6, set.included.size)
            for (b in circles) assertEquals(2 * b.rx * px, set.particleAt(b.cx.toInt(), b.cy.toInt())!!.measurement!!.ecd, 2 * b.rx * px * 0.05) // adaptive/edge methods with local 50 % refinement: ±5 %
        }
    }

    @Test fun ecdOfRandomPopulationMatchesGroundTruthStatistics() {
        val rnd = java.util.Random(11)
        val blobs = ArrayList<Synthetic.Blob>()
        val grid = 8
        for (j in 0 until grid) for (i in 0 until grid) {
            val r = 6 + rnd.nextDouble() * 8
            blobs += Synthetic.Blob(24 + i * 40.0, 24 + j * 40.0, r, r)
        }
        val set = seg(Synthetic.particles(340, 340, blobs, pixelNm = 1.0))
        assertEquals(64, set.included.size)
        val truth = Statistics.describe(blobs.map { 2 * it.rx }.toDoubleArray())
        val meas = Statistics.describe(set.values(ParticleVariable.ECD))
        assertEquals(truth.mean, meas.mean, truth.mean * 0.015)
        assertEquals(truth.sd, meas.sd, truth.sd * 0.05)
        assertEquals(sqrt(1.0), 1.0, 0.0)
    }
}
