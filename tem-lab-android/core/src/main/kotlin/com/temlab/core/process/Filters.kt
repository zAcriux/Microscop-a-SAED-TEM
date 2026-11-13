/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core.process

import com.temlab.core.image.ImageData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Spatial-domain filters. All operate on float data and return a new image (original untouched). */
object Filters {

    private fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        var j = i
        while (j < 0 || j >= n) j = if (j < 0) -j - 1 else 2 * n - j - 1
        return j
    }

    fun gaussianKernel(sigma: Double): FloatArray {
        val r = max(1, ceil(3 * sigma).toInt())
        val k = FloatArray(2 * r + 1) { val x = it - r; exp(-x * x / (2 * sigma * sigma)).toFloat() }
        val s = k.sum(); for (i in k.indices) k[i] /= s
        return k
    }

    fun convolveSeparable(src: FloatArray, w: Int, h: Int, kx: FloatArray, ky: FloatArray): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        val rx = kx.size / 2; val ry = ky.size / 2
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                for (k in kx.indices) s += kx[k] * src[row + reflect(x + k - rx, w)]
                tmp[row + x] = s
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (k in ky.indices) s += ky[k] * tmp[reflect(y + k - ry, h) * w + x]
            out[y * w + x] = s
        }
        return out
    }

    fun gaussian(img: ImageData, sigma: Double): ImageData {
        if (sigma <= 0) return img.copy()
        val k = gaussianKernel(sigma)
        return img.withPixels(img.width, img.height, convolveSeparable(img.pixels, img.width, img.height, k, k))
    }

    fun convolve3x3(img: ImageData, k: FloatArray): ImageData {
        val w = img.width; val h = img.height; val p = img.pixels
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var i = 0
            for (dy in -1..1) for (dx in -1..1) s += k[i++] * p[reflect(y + dy, h) * w + reflect(x + dx, w)]
            out[y * w + x] = s
        }
        return img.withPixels(w, h, out)
    }

    fun median(img: ImageData, radius: Int): ImageData {
        val w = img.width; val h = img.height; val p = img.pixels
        val r = radius.coerceIn(1, 10)
        val out = FloatArray(w * h)
        val win = FloatArray((2 * r + 1) * (2 * r + 1))
        for (y in 0 until h) for (x in 0 until w) {
            var n = 0
            for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r + r) win[n++] = p[reflect(y + dy, h) * w + reflect(x + dx, w)]
            java.util.Arrays.sort(win, 0, n)
            out[y * w + x] = win[n / 2]
        }
        return img.withPixels(w, h, out)
    }

    /** Edge-preserving bilateral filter. sigmaRange is relative to the image dynamic range (0–1). */
    fun bilateral(img: ImageData, sigmaSpatial: Double, sigmaRangeRel: Double): ImageData {
        val w = img.width; val h = img.height; val p = img.pixels
        val (mn, mx) = img.minMax()
        val sr = max(1e-12, sigmaRangeRel * (mx - mn))
        val r = max(1, ceil(2 * sigmaSpatial).toInt())
        val sw = Array(2 * r + 1) { j -> FloatArray(2 * r + 1) { i -> val dx = i - r; val dy = j - r; exp(-(dx * dx + dy * dy) / (2 * sigmaSpatial * sigmaSpatial)).toFloat() } }
        val inv = 1.0 / (2 * sr * sr)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = p[y * w + x]
            var s = 0.0; var ws = 0.0
            for (dy in -r..r) {
                val yy = reflect(y + dy, h) * w
                for (dx in -r..r) {
                    val v = p[yy + reflect(x + dx, w)]
                    val d = (v - c).toDouble()
                    val wt = sw[dy + r][dx + r] * exp(-d * d * inv)
                    s += wt * v; ws += wt
                }
            }
            out[y * w + x] = (s / ws).toFloat()
        }
        return img.withPixels(w, h, out)
    }

    fun laplacian(img: ImageData) = convolve3x3(img, floatArrayOf(0f, 1f, 0f, 1f, -4f, 1f, 0f, 1f, 0f))

    fun sobel(img: ImageData): ImageData {
        val gx = convolve3x3(img, floatArrayOf(-1f, 0f, 1f, -2f, 0f, 2f, -1f, 0f, 1f)).pixels
        val gy = convolve3x3(img, floatArrayOf(-1f, -2f, -1f, 0f, 0f, 0f, 1f, 2f, 1f)).pixels
        return img.withPixels(img.width, img.height, FloatArray(gx.size) { sqrt(gx[it] * gx[it] + gy[it] * gy[it]) })
    }

    /** Laplacian of Gaussian edge response (absolute value). */
    fun logEdges(img: ImageData, sigma: Double): ImageData {
        val l = laplacian(gaussian(img, sigma)).pixels
        return img.withPixels(img.width, img.height, FloatArray(l.size) { abs(l[it]) })
    }

    fun lowPass(img: ImageData, sigma: Double) = gaussian(img, sigma)

    /** High pass: original − Gaussian background (+ mean, so the intensity level is kept). */
    fun highPass(img: ImageData, sigma: Double): ImageData {
        val g = gaussian(img, sigma).pixels
        val mean = img.pixels.average().toFloat()
        return img.withPixels(img.width, img.height, FloatArray(g.size) { img.pixels[it] - g[it] + mean })
    }

    fun unsharpMask(img: ImageData, sigma: Double, amount: Double): ImageData {
        val g = gaussian(img, sigma).pixels
        val a = amount.toFloat()
        return img.withPixels(img.width, img.height, FloatArray(g.size) { img.pixels[it] + a * (img.pixels[it] - g[it]) })
    }

    /** Adaptive Wiener noise reduction (local mean/variance, noise = mean local variance), as in Lim (1990). */
    fun wiener(img: ImageData, radius: Int): ImageData {
        val w = img.width; val h = img.height
        val ii = IntegralImage(img.pixels, w, h)
        val mean = FloatArray(w * h); val vars = FloatArray(w * h)
        var noise = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val (m, v) = ii.meanVar(x - radius, y - radius, x + radius, y + radius)
            mean[y * w + x] = m.toFloat(); vars[y * w + x] = v.toFloat(); noise += v
        }
        noise /= (w * h)
        val out = FloatArray(w * h) {
            val v = vars[it]; val m = mean[it]
            if (v <= noise) m else (m + (v - noise) / v * (img.pixels[it] - m)).toFloat()
        }
        return img.withPixels(w, h, out)
    }
}

/** Summed-area tables for O(1) local mean/variance. */
class IntegralImage(p: FloatArray, val w: Int, val h: Int) {
    private val s = DoubleArray((w + 1) * (h + 1))
    private val s2 = DoubleArray((w + 1) * (h + 1))
    init {
        for (y in 0 until h) {
            var r = 0.0; var r2 = 0.0
            for (x in 0 until w) {
                val v = p[y * w + x].toDouble(); r += v; r2 += v * v
                s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + r
                s2[(y + 1) * (w + 1) + x + 1] = s2[y * (w + 1) + x + 1] + r2
            }
        }
    }
    fun meanVar(x0: Int, y0: Int, x1: Int, y1: Int): Pair<Double, Double> {
        val a = max(0, x0); val b = max(0, y0); val c = min(w - 1, x1) + 1; val d = min(h - 1, y1) + 1
        val n = ((c - a) * (d - b)).toDouble()
        val W = w + 1
        val sum = s[d * W + c] - s[b * W + c] - s[d * W + a] + s[b * W + a]
        val sum2 = s2[d * W + c] - s2[b * W + c] - s2[d * W + a] + s2[b * W + a]
        val m = sum / n
        return m to max(0.0, sum2 / n - m * m)
    }
}

/** Intensity adjustments and geometric transforms. */
object Adjust {
    fun normalize(img: ImageData): ImageData {
        val (mn, mx) = img.minMax(); val r = if (mx > mn) mx - mn else 1f
        return img.withPixels(img.width, img.height, FloatArray(img.pixels.size) { (img.pixels[it] - mn) / r })
    }

    fun invert(img: ImageData): ImageData {
        val (mn, mx) = img.minMax()
        return img.withPixels(img.width, img.height, FloatArray(img.pixels.size) { mx + mn - img.pixels[it] })
    }

    /** Histogram equalisation computed on 4096 bins, mapped back into the original intensity range. */
    fun equalize(img: ImageData): ImageData {
        val (mn, mx) = img.minMax(); val bins = 4096
        val r = if (mx > mn) mx - mn else 1f
        val hist = IntArray(bins)
        for (v in img.pixels) hist[((v - mn) / r * (bins - 1)).toInt().coerceIn(0, bins - 1)]++
        val cdf = IntArray(bins); var acc = 0
        for (i in 0 until bins) { acc += hist[i]; cdf[i] = acc }
        val n = img.pixels.size.toFloat()
        return img.withPixels(img.width, img.height, FloatArray(img.pixels.size) {
            val b = ((img.pixels[it] - mn) / r * (bins - 1)).toInt().coerceIn(0, bins - 1)
            mn + cdf[b] / n * r
        })
    }

    /** Contrast-limited adaptive histogram equalisation (Zuiderveld 1994), bilinear interpolation between tiles. */
    fun clahe(img: ImageData, tiles: Int = 8, clipLimit: Double = 2.0, bins: Int = 256): ImageData {
        val w = img.width; val h = img.height
        val (mn, mx) = img.minMax(); val r = if (mx > mn) mx - mn else 1f
        val tx = tiles.coerceIn(1, w); val ty = tiles.coerceIn(1, h)
        val tw = ceil(w / tx.toDouble()).toInt(); val th = ceil(h / ty.toDouble()).toInt()
        val maps = Array(ty) { Array(tx) { FloatArray(bins) } }
        fun bin(v: Float) = ((v - mn) / r * (bins - 1)).toInt().coerceIn(0, bins - 1)
        for (j in 0 until ty) for (i in 0 until tx) {
            val hist = IntArray(bins); var n = 0
            for (y in j * th until min(h, (j + 1) * th)) for (x in i * tw until min(w, (i + 1) * tw)) { hist[bin(img[x, y])]++; n++ }
            if (n == 0) continue
            val limit = max(1, (clipLimit * n / bins).toInt())
            var excess = 0
            for (b in 0 until bins) if (hist[b] > limit) { excess += hist[b] - limit; hist[b] = limit }
            val add = excess / bins; var rem = excess % bins
            for (b in 0 until bins) { hist[b] += add; if (rem > 0) { hist[b]++; rem-- } }
            var acc = 0
            for (b in 0 until bins) { acc += hist[b]; maps[j][i][b] = acc.toFloat() / n }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val b = bin(img[x, y])
            val gx = (x + 0.5) / tw - 0.5; val gy = (y + 0.5) / th - 0.5
            val i0 = gx.toInt().coerceIn(0, tx - 1).let { if (gx < 0) 0 else it }; val j0 = if (gy < 0) 0 else gy.toInt().coerceIn(0, ty - 1)
            val i1 = min(i0 + 1, tx - 1); val j1 = min(j0 + 1, ty - 1)
            val fx = (gx - i0).coerceIn(0.0, 1.0).toFloat(); val fy = (gy - j0).coerceIn(0.0, 1.0).toFloat()
            val v = (maps[j0][i0][b] * (1 - fx) + maps[j0][i1][b] * fx) * (1 - fy) + (maps[j1][i0][b] * (1 - fx) + maps[j1][i1][b] * fx) * fy
            out[y * w + x] = mn + v * r
        }
        return img.withPixels(w, h, out)
    }

    /** Subtracts a smooth (large Gaussian) background, then restores the original mean. */
    fun subtractGaussianBackground(img: ImageData, sigma: Double): ImageData {
        val bg = Filters.gaussian(img, sigma).pixels
        val mean = img.pixels.average().toFloat()
        return img.withPixels(img.width, img.height, FloatArray(bg.size) { img.pixels[it] - bg[it] + mean })
    }

    /** Least-squares plane fit subtraction (corrects linear illumination gradients). */
    fun subtractPlane(img: ImageData): ImageData {
        val w = img.width; val h = img.height
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sx = 0.0; var sy = 0.0; var n = 0.0
        var sz = 0.0; var sxz = 0.0; var syz = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val z = img[x, y].toDouble()
            sxx += x.toDouble() * x; syy += y.toDouble() * y; sxy += x.toDouble() * y; sx += x; sy += y; n++
            sz += z; sxz += x * z; syz += y * z
        }
        // Solve [sxx sxy sx; sxy syy sy; sx sy n] [a b c] = [sxz syz sz]
        val m = arrayOf(doubleArrayOf(sxx, sxy, sx, sxz), doubleArrayOf(sxy, syy, sy, syz), doubleArrayOf(sx, sy, n, sz))
        for (c in 0 until 3) {
            val piv = (c until 3).maxBy { abs(m[it][c]) }; val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in 0 until 3) if (r != c) { val f = m[r][c] / m[c][c]; for (k in c until 4) m[r][k] -= f * m[c][k] }
        }
        val a = m[0][3] / m[0][0]; val b = m[1][3] / m[1][1]
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        return img.withPixels(w, h, FloatArray(w * h) { val x = it % w; val y = it / w; (img.pixels[it] - (a * (x - cx) + b * (y - cy))).toFloat() })
    }

    /** Rotation by k×90° clockwise. Calibration X/Y are swapped for odd k. */
    fun rotate90(img: ImageData, k: Int): ImageData {
        val kk = Math.floorMod(k, 4)
        if (kk == 0) return img.copy()
        val w = img.width; val h = img.height
        val nw = if (kk % 2 == 1) h else w; val nh = if (kk % 2 == 1) w else h
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (nx, ny) = when (kk) { 1 -> (h - 1 - y) to x; 2 -> (w - 1 - x) to (h - 1 - y); else -> y to (w - 1 - x) }
            out[ny * nw + nx] = img[x, y]
        }
        val c = img.calibration
        val cal = if (kk % 2 == 1) c.copy(pixelWidth = c.pixelHeight, pixelHeight = c.pixelWidth) else c
        return img.withPixels(nw, nh, out, cal)
    }

    fun flip(img: ImageData, horizontal: Boolean): ImageData {
        val w = img.width; val h = img.height
        return img.withPixels(w, h, FloatArray(w * h) { val x = it % w; val y = it / w; if (horizontal) img[w - 1 - x, y] else img[x, h - 1 - y] })
    }

    /**
     * Arbitrary rotation about the centre with bilinear interpolation (same canvas size, empty corners = mean).
     * Calibration is preserved exactly only for isotropic pixels; for anisotropic pixels the pixel size is
     * replaced by the geometric mean and the operation is flagged in the metadata.
     */
    fun rotate(img: ImageData, angleDeg: Double): ImageData {
        val w = img.width; val h = img.height
        val a = Math.toRadians(angleDeg); val ca = cos(a); val sa = sin(a)
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        val mean = img.pixels.average().toFloat()
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val sx = ca * dx + sa * dy + cx; val sy = -sa * dx + ca * dy + cy
            out[y * w + x] = if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) mean else img.sample(sx, sy).toFloat()
        }
        val c = img.calibration
        val r = img.withPixels(w, h, out, if (c.isIsotropic) c else c.copy(pixelWidth = sqrt(c.pixelWidth * c.pixelHeight), pixelHeight = sqrt(c.pixelWidth * c.pixelHeight), source = c.source + " (approx. after rotation)"))
        if (!c.isIsotropic) r.metadata["Calibration warning"] = "Arbitrary rotation of anisotropic pixels: geometric-mean pixel size used"
        return r
    }

    fun gammaValue(v: Double, gamma: Double) = v.pow(gamma)
    @Suppress("unused") private val TWO_PI = 2 * PI
}
