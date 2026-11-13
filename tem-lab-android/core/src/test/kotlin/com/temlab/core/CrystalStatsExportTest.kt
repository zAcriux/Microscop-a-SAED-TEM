package com.temlab.core

import com.temlab.core.crystal.Centering
import com.temlab.core.crystal.CrystalMatcher
import com.temlab.core.crystal.CrystalSystem
import com.temlab.core.crystal.Lattice
import com.temlab.core.export.CsvWriter
import com.temlab.core.export.SvgHistogram
import com.temlab.core.export.Tables
import com.temlab.core.export.XlsxWriter
import com.temlab.core.image.ImageData
import com.temlab.core.particles.ParticleSet
import com.temlab.core.process.Adjust
import com.temlab.core.process.DisplaySettings
import com.temlab.core.process.Filters
import com.temlab.core.process.History
import com.temlab.core.process.Pipeline
import com.temlab.core.process.ProcessOp
import com.temlab.core.project.Project
import com.temlab.core.project.ProjectIO
import com.temlab.core.project.ProjectImage
import com.temlab.core.project.niceScaleLength
import com.temlab.core.segmentation.SegmentationParams
import com.temlab.core.stats.Statistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipInputStream
import kotlin.math.exp
import kotlin.math.sqrt

class CrystalStatsExportTest {

    @Test fun cubicSpacingsAnglesAndExtinctions() {
        val au = Lattice.PRESETS.first { it.name.startsWith("Au") }
        assertEquals(4.0782 / sqrt(3.0), au.d(1, 1, 1), 1e-9)
        assertEquals(2.0391, au.d(2, 0, 0), 1e-4)
        assertEquals(54.7356, au.interplanarAngle(Triple(1, 0, 0), Triple(1, 1, 1)), 1e-3)
        assertEquals(70.5288, au.interplanarAngle(Triple(1, 1, 1), Triple(1, -1, 1)), 1e-3)
        assertFalse(Centering.F.allowed(1, 0, 0)); assertTrue(Centering.F.allowed(2, 2, 0))
        assertFalse(Centering.DIAMOND.allowed(2, 0, 0)); assertTrue(Centering.DIAMOND.allowed(4, 0, 0)); assertTrue(Centering.DIAMOND.allowed(3, 1, 1))
        assertFalse(Centering.I.allowed(1, 0, 0)); assertTrue(Centering.I.allowed(1, 1, 0))
    }

    @Test fun hexagonalAndTetragonalSpacings() {
        val zno = Lattice.PRESETS.first { it.name.startsWith("ZnO") }
        // ICDD 36-1451: d(100)=2.8143, d(002)=2.6033, d(101)=2.4759 Å
        assertEquals(2.8143, zno.d(1, 0, 0), 2e-4)
        assertEquals(2.6033, zno.d(0, 0, 2), 2e-4)
        assertEquals(2.4759, zno.d(1, 0, 1), 2e-4)
        assertEquals(60.0, zno.interplanarAngle(Triple(1, 0, 0), Triple(0, 1, 0)), 1e-9)
        assertFalse(Centering.HCP.allowed(0, 0, 1)); assertTrue(Centering.HCP.allowed(1, 0, 1))
        val anatase = Lattice.PRESETS.first { it.name.contains("anatase") }
        assertEquals(3.52, anatase.d(1, 0, 1), 0.005) // anatase (101) 3.52 Å
        val mono = Lattice("test", CrystalSystem.MONOCLINIC, 5.0, 6.0, 7.0, 90.0, 100.0, 90.0)
        // monoclinic d(001) = c sin(beta)
        assertEquals(7.0 * kotlin.math.sin(Math.toRadians(100.0)), mono.d(0, 0, 1), 1e-9)
    }

    @Test fun matchingIsRankedAndTentative() {
        val au = Lattice.PRESETS.first { it.name.startsWith("Au") }
        val c = CrystalMatcher.matchD(au, 2.36, 2.0)
        assertEquals(1, c.first().h + c.first().k + c.first().l - 2) // {111}
        val pairs = CrystalMatcher.matchPair(au, 2.355, 2.039, 54.7, 2.0, 1.0)
        assertTrue(pairs.isNotEmpty())
        val z = CrystalMatcher.zoneAxis(pairs.first().first, pairs.first().second)
        assertEquals(0, z.first * pairs.first().first.h + z.second * pairs.first().first.k + z.third * pairs.first().first.l)
    }

    @Test fun descriptiveStatisticsAndPercentiles() {
        val d = Statistics.describe(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0))
        assertEquals(5.5, d.mean, 1e-12); assertEquals(5.5, d.median, 1e-12)
        assertEquals(3.0276503540974917, d.sd, 1e-12)
        assertEquals(3.25, d.p25, 1e-12); assertEquals(7.75, d.p75, 1e-12); assertEquals(1.9, d.p10, 1e-12)
        val h = Statistics.histogram(doubleArrayOf(0.0, 0.5, 1.0, 1.5, 2.0), 2)
        assertEquals(listOf(2, 3), h.counts.toList())
        val hw = Statistics.histogramByWidth(doubleArrayOf(0.2, 1.1, 1.9, 2.5), 1.0)
        assertEquals(listOf(1, 2, 1), hw.counts.toList())
    }

    @Test fun lognormalFitRecoversParameters() {
        val rnd = Random(5)
        val mu = 1.8; val s = 0.25
        val x = DoubleArray(2000) { exp(mu + s * rnd.nextGaussian()) }
        val fit = Statistics.lognormal(x)!!
        assertEquals(mu, fit.mu, 0.02); assertEquals(s, fit.sigma, 0.01)
        assertTrue(fit.acceptable)
        val uniform = DoubleArray(500) { 1 + rnd.nextDouble() * 0.01 + (it % 2) * 5 }
        assertFalse(Statistics.lognormal(uniform)!!.acceptable)
        assertEquals(0.5, Statistics.normalCdf(0.0), 1e-7); assertEquals(0.97725, Statistics.normalCdf(2.0), 1e-5)
    }

    @Test fun exportsCsvXlsxSvg() {
        val img = Synthetic.particles(128, 96, listOf(Synthetic.Blob(30.0, 30.0, 10.0, 10.0), Synthetic.Blob(80.0, 50.0, 14.0, 9.0)))
        val set = ParticleSet(img).also { it.segment(SegmentationParams(watershed = false)) }
        val t = Tables.particles(set)
        val csv = ByteArrayOutputStream().also { CsvWriter.write(t, it) }.toString(Charsets.UTF_8)
        assertTrue(csv.lines()[0].contains("ECD (nm)"))
        assertEquals(3, csv.trim().lines().size)
        val xlsx = ByteArrayOutputStream().also { XlsxWriter.write(listOf(t, Tables.statistics(set.values(com.temlab.core.particles.ParticleVariable.ECD), "ECD", "nm")), it) }.toByteArray()
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(xlsx)).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")))
        val svg = SvgHistogram.render(Statistics.histogram(set.values(com.temlab.core.particles.ParticleVariable.ECD), 4), "ECD", "ECD (nm)", false, null, 5.0, 5.0)
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"))
        assertEquals("AA", XlsxWriter.col(26)); assertEquals("Z", XlsxWriter.col(25))
    }

    @Test fun projectRoundTripAndRunLengthEncoding() {
        val p = Project("demo", 1, 2, images = listOf(ProjectImage("content://x", "a.dm4", pipeline = Pipeline(listOf(ProcessOp.Gaussian(1.5), ProcessOp.Clahe(8, 2.0), ProcessOp.Invert)))))
        val back = ProjectIO.decode(ProjectIO.encode(p))
        assertEquals(p, back)
        val px = intArrayOf(3, 4, 5, 9, 10, 20)
        assertEquals(listOf(3, 3, 9, 2, 20, 1), ProjectIO.encodeRuns(px).toList())
        assertEquals(px.toList(), ProjectIO.decodeRuns(ProjectIO.encodeRuns(px)).toList())
    }

    @Test fun processingKeepsOriginalAndHistoryWorks() {
        val img = Synthetic.lattice(64, 64, 0.02, 0.02, listOf(Synthetic.Fringe(0.2, 0.0)), noise = 1.0)
        val orig = img.pixels.copyOf()
        val pipe = Pipeline(listOf(ProcessOp.Gaussian(1.0), ProcessOp.Median(1), ProcessOp.Bilateral(1.5, 0.2), ProcessOp.Clahe(4, 2.0),
            ProcessOp.Background(10.0), ProcessOp.PlaneFit, ProcessOp.Unsharp(1.0, 0.5), ProcessOp.Wiener(2), ProcessOp.Equalize,
            ProcessOp.Sobel, ProcessOp.Laplacian, ProcessOp.LogEdges(1.0), ProcessOp.HighPass(5.0), ProcessOp.Normalize, ProcessOp.Invert,
            ProcessOp.Rotate(10.0), ProcessOp.Flip(true), ProcessOp.Crop(4, 4, 40, 30), ProcessOp.Rotate90(1)))
        val out = pipe.apply(img)
        assertTrue(orig.contentEquals(img.pixels)) // non-destructive
        assertEquals(30, out.width); assertEquals(40, out.height)
        assertEquals(0.02, out.calibration.pixelWidth, 1e-12)
        val hist = History()
        hist.push(Pipeline(listOf(ProcessOp.Invert))); hist.push(Pipeline(listOf(ProcessOp.Invert, ProcessOp.Normalize)))
        assertEquals(1, hist.undo().ops.size); assertEquals(0, hist.undo().ops.size); assertEquals(1, hist.redo().ops.size)
        // Plane fit removes a pure gradient
        val grad = ImageData(32, 32, FloatArray(32 * 32) { (it % 32) * 2f + (it / 32) * 3f })
        val flat = Adjust.subtractPlane(grad)
        val (mn, mx) = flat.minMax(); assertEquals(0.0, (mx - mn).toDouble(), 1e-3)
        // anisotropic rotate90 swaps calibration
        val an = ImageData(10, 6, FloatArray(60), calibration = com.temlab.core.image.Calibration(1.0, 2.0, com.temlab.core.image.LengthUnit.NM))
        assertEquals(2.0, Adjust.rotate90(an, 1).calibration.pixelWidth, 0.0)
        // display mapping
        val lut = DisplaySettings(min = 0.0, max = 10.0).lut(0f, 10f)
        assertEquals(0, lut(0f)); assertEquals(255, lut(10f)); assertEquals(128, lut(5f))
        assertEquals(255, DisplaySettings(min = 0.0, max = 10.0, invert = true).lut(0f, 10f)(0f))
        // Gaussian of constant is constant
        val c = Filters.gaussian(ImageData(20, 20, FloatArray(400) { 7f }), 2.0)
        assertTrue(c.pixels.all { kotlin.math.abs(it - 7f) < 1e-4 })
        assertEquals(20.0, niceScaleLength(100.0), 1e-12); assertEquals(5.0, niceScaleLength(30.0), 1e-12)
    }
}
