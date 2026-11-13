/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core

import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.image.LengthUnit
import com.temlab.core.image.PixelType
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Generators of synthetic micrographs with exactly known content. */
object Synthetic {
    data class Fringe(val dNm: Double, val angleDeg: Double, val amplitude: Double = 1.0, val phase: Double = 0.0)

    /**
     * Lattice image I = Σ A cos(2π g·r + φ) with r in physical units (nm), pixel sizes pw × ph (nm).
     * The direction angle is measured counter-clockwise from +x with the y axis pointing UP (screen convention).
     */
    fun lattice(w: Int, h: Int, pw: Double, ph: Double, fringes: List<Fringe>, noise: Double = 0.0, seed: Long = 1): ImageData {
        val rnd = Random(seed)
        val px = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val X = x * pw; val Y = -y * ph
            var v = 100.0
            for (f in fringes) {
                val a = Math.toRadians(f.angleDeg)
                val gx = cos(a) / f.dNm; val gy = sin(a) / f.dNm
                v += f.amplitude * cos(2 * PI * (gx * X + gy * Y) + f.phase)
            }
            if (noise > 0) v += noise * rnd.nextGaussian()
            px[y * w + x] = v.toFloat()
        }
        return ImageData(w, h, px, PixelType.FLOAT32, Calibration(pw, ph, LengthUnit.NM, false, "synthetic"), name = "lattice")
    }

    data class Blob(val cx: Double, val cy: Double, val rx: Double, val ry: Double, val angleDeg: Double = 0.0)

    /** Dark particles (value [fg]) on bright background ([bg]) with 4×4 supersampled anti-aliased edges. */
    fun particles(w: Int, h: Int, blobs: List<Blob>, bg: Double = 200.0, fg: Double = 60.0, noise: Double = 4.0, seed: Long = 7, pixelNm: Double = 0.5): ImageData {
        val rnd = Random(seed)
        val px = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var cover = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val X = x + (sx + 0.5) / 4; val Y = y + (sy + 0.5) / 4
                if (blobs.any { inside(it, X, Y) }) cover++
            }
            val f = cover / 16.0
            px[y * w + x] = (bg * (1 - f) + fg * f + noise * rnd.nextGaussian()).toFloat()
        }
        return ImageData(w, h, px, PixelType.FLOAT32, Calibration(pixelNm, pixelNm, LengthUnit.NM, false, "synthetic"), name = "particles")
    }

    fun inside(b: Blob, x: Double, y: Double): Boolean {
        val a = Math.toRadians(b.angleDeg)
        val dx = x - b.cx; val dy = y - b.cy
        val xr = dx * cos(a) + dy * sin(a); val yr = -dx * sin(a) + dy * cos(a)
        return (xr / b.rx) * (xr / b.rx) + (yr / b.ry) * (yr / b.ry) <= 1.0
    }
}
