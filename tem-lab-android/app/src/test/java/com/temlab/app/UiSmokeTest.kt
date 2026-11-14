/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.app

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.temlab.app.data.AppViewModel
import com.temlab.app.data.Doc
import com.temlab.app.ui.Lang
import com.temlab.core.fft.FftOptions
import com.temlab.core.fft.FourierMask
import com.temlab.core.fft.MaskShape
import com.temlab.core.fft.SquareRoi
import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.image.LengthUnit
import com.temlab.core.image.PixelType
import com.temlab.core.measure.MeasureKind
import com.temlab.core.measure.Measure
import com.temlab.core.measure.Pt
import com.temlab.core.segmentation.SegmentationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runs the real activity on Robolectric with native graphics, loads synthetic micrographs, drives every
 * module and saves screenshots (docs/screenshots). Verifies the UI composes and the workflows execute
 * end-to-end without crashing (no emulator/KVM is available in the build environment).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class UiSmokeBase {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    abstract val tag: String

    private fun lattice(n: Int, pw: Double): ImageData {
        val rnd = Random(2)
        val px = FloatArray(n * n)
        val f = listOf(0.2355 to 20.0, 0.2039 to 74.74, 0.2355 to 129.48)
        for (y in 0 until n) for (x in 0 until n) {
            var v = 1000.0
            for ((d, a) in f) { val r = Math.toRadians(a); v += 60 * cos(2 * PI * (cos(r) / d * x * pw - sin(r) / d * y * pw)) }
            // amorphous edge region
            if (x + y < n * 0.35) v = 1000.0 + 40 * rnd.nextGaussian() else v += 25 * rnd.nextGaussian()
            px[y * n + x] = v.toFloat()
        }
        return ImageData(n, n, px, PixelType.FLOAT32, Calibration(pw, pw, LengthUnit.NM, false, "synthetic DM4"), linkedMapOf("Format" to "synthetic", "Microscope Info.Voltage" to "200000"), "Au_HRTEM")
    }

    private fun particles(w: Int, h: Int): ImageData {
        val rnd = Random(4)
        val blobs = (0 until 60).map { Triple(30 + rnd.nextDouble() * (w - 60), 30 + rnd.nextDouble() * (h - 60), 7 + rnd.nextGaussian() * 1.5) }
        val px = FloatArray(w * h) { i ->
            val x = i % w + 0.5; val y = i / w + 0.5
            val inside = blobs.any { (cx, cy, r) -> (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r }
            ((if (inside) 70.0 else 190.0) + 6 * rnd.nextGaussian()).toFloat()
        }
        return ImageData(w, h, px, PixelType.UINT16, Calibration(0.5, 0.5, LengthUnit.NM, false, "synthetic"), linkedMapOf("Format" to "synthetic"), "NP_TEM")
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(400); rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(android.graphics.Canvas(bmp)) }
        val dir = File(System.getProperty("temlab.screens", "build/screenshots")).also { it.mkdirs() }
        File(dir, "${tag}_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun click(text: String) { rule.onAllNodesWithText(text, substring = false)[0].performClick(); rule.waitForIdle() }
    private fun pump(ms: Long) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { rule.mainClock.advanceTimeBy(100); org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(50) } }
    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end) {
            rule.mainClock.advanceTimeBy(100)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            var r = false; rule.runOnUiThread { r = cond() }
            if (r) return
            Thread.sleep(100)
        }
        throw AssertionError("timeout waiting for condition")
    }

    @Test fun fullWorkflow() {
        val vm = ViewModelProvider(rule.activity)[AppViewModel::class.java]
        rule.runOnUiThread { vm.setLanguage(Lang.ES) }
        shot("01_home_empty")
        val hr = Doc(1, "synthetic://hrtem", "Au_HRTEM.dm4", 0, lattice(512, 0.0189))
        val np = Doc(2, "synthetic://np", "NP_TEM.tif", 0, particles(420, 320))
        rule.runOnUiThread { vm.addDoc(np); vm.addDoc(hr) }
        waitFor { hr.preview != null && np.preview != null }
        click("Área de trabajo (2)")
        rule.runOnUiThread {
            hr.measurements += Measure.manual(1, MeasureKind.FRINGES, listOf(Pt(300.0, 300.0), Pt(420.0, 300.0)), hr.processed.calibration, 9)
        }
        shot("02_measure")

        // FFT: ROI, transform, tap-equivalent reflections, symmetric pair
        rule.runOnUiThread { hr.roi = SquareRoi(220, 220, 256); vm.computeFft(hr, hr.roi!!, FftOptions(hannWindow = true, paddedSize = 512)) }
        waitFor { hr.ffts.isNotEmpty() }
        val s = hr.ffts.last()
        rule.runOnUiThread {
            for ((d, a) in listOf(0.2355 to 20.0, 0.2039 to 74.74, 0.2355 to 200.0)) {
                val r = Math.toRadians(a); val (u, v) = s.result.toFftPixel(cos(r) / d, -sin(r) / d)
                vm.addReflection(s, u, v, 6)
            }
        }
        assertEquals(3, s.reflections.size)
        assertEquals(0.2355, s.reflections[0].d, 0.2355 * 0.003)
        assertEquals(0.2039, s.reflections[1].d, 0.2039 * 0.003)
        click("FFT")
        shot("03_fft")

        // IFFT with symmetric spot masks around the measured reflections
        rule.runOnUiThread {
            hr.mask = FourierMask(s.reflections.take(2).map { MaskShape.Circle((it.u - s.result.center) / 2, (it.v - s.result.center) / 2, 5.0, 1.5) }, symmetric = true)
        }
        click("IFFT")
        pump(4000)
        shot("04_ifft")
        // Enlarge only the IFFT panel, then restore the 4-panel grid
        rule.onAllNodesWithContentDescription("Ampliar / restaurar")[3].performClick(); pump(1500)
        shot("04b_ifft_enlarged")
        rule.onAllNodesWithContentDescription("Ampliar / restaurar")[0].performClick(); pump(500)

        // Particles + statistics on the second micrograph
        rule.runOnUiThread { vm.current = np; vm.segment(np, SegmentationParams(watershed = true), com.temlab.core.particles.ParticleFilter()) }
        waitFor { np.particles != null }
        assertTrue("particles found: ${np.particles!!.included.size}", np.particles!!.included.size in 40..70)
        click("Partículas")
        shot("05_particles")
        click("Estadística")
        shot("06_statistics")
        click("Procesar")
        rule.runOnUiThread { vm.applyOp(np, com.temlab.core.process.ProcessOp.Gaussian(1.0)) }
        waitFor { !np.busy }
        shot("07_process")
        click("Anotar")
        shot("08_annotate")
        click("Comparar")
        shot("09_compare")
        click("Reporte")
        shot("10_report")
        // Project save / reopen round trip
        rule.runOnUiThread { vm.saveProject("smoke") }
        val f = File(vm.projectsDir, "smoke.temlab.json")
        assertTrue(f.exists())
        val p = com.temlab.core.project.ProjectIO.decode(f.readText())
        assertEquals(2, p.images.size)
        assertEquals(3, p.images.first { it.displayName == "Au_HRTEM.dm4" }.ffts.first().reflections.size)
    }
}

@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PhoneUiSmokeTest : UiSmokeBase() { override val tag = "phone" }

@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
class TabletUiSmokeTest : UiSmokeBase() { override val tag = "tablet" }
