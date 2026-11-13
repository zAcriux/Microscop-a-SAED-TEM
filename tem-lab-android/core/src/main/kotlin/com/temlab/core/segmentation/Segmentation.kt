/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core.segmentation

import com.temlab.core.image.ImageData
import com.temlab.core.process.Filters
import com.temlab.core.process.IntegralImage
import kotlinx.serialization.Serializable
import java.util.PriorityQueue
import kotlin.math.sqrt

/** Binary mask, row-major. */
class BinaryMask(val width: Int, val height: Int, val bits: BooleanArray = BooleanArray(width * height)) {
    operator fun get(x: Int, y: Int) = bits[y * width + x]
    operator fun set(x: Int, y: Int, v: Boolean) { bits[y * width + x] = v }
    fun count() = bits.count { it }
    fun copy() = BinaryMask(width, height, bits.copyOf())
    fun invert() = BinaryMask(width, height, BooleanArray(bits.size) { !bits[it] })
    fun and(o: BinaryMask) = BinaryMask(width, height, BooleanArray(bits.size) { bits[it] && o.bits[it] })
    fun or(o: BinaryMask) = BinaryMask(width, height, BooleanArray(bits.size) { bits[it] || o.bits[it] })
}

enum class Polarity { BRIGHT_OBJECTS, DARK_OBJECTS }

enum class ThresholdMethod { MANUAL, OTSU, ADAPTIVE, EDGES, INTENSITY_RANGE }

@Serializable
data class SegmentationParams(
    val method: ThresholdMethod = ThresholdMethod.OTSU,
    val polarity: Polarity = Polarity.DARK_OBJECTS,
    val manualThreshold: Double = Double.NaN,
    val rangeLow: Double = Double.NaN,
    val rangeHigh: Double = Double.NaN,
    val adaptiveRadius: Int = 25,
    val adaptiveOffset: Double = 0.3,   // in units of the global standard deviation
    val preSmoothSigma: Double = 1.0,
    val fillHoles: Boolean = true,
    val openRadius: Int = 1,
    val closeRadius: Int = 0,
    val watershed: Boolean = true,
    val watershedMinDistance: Double = 3.0, // px; minimum EDT value for a seed / merge tolerance
    val excludeBorder: Boolean = true,
)

object Threshold {
    /** Otsu threshold on a 256-bin histogram spanning [min,max]; returns the threshold in data units. */
    fun otsu(values: FloatArray): Double {
        var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY
        for (v in values) { if (v < mn) mn = v; if (v > mx) mx = v }
        if (mx <= mn) return mn.toDouble()
        val bins = 256; val hist = DoubleArray(bins); val r = (mx - mn).toDouble()
        for (v in values) hist[((v - mn) / r * (bins - 1)).toInt().coerceIn(0, bins - 1)]++
        val total = values.size.toDouble()
        var sumAll = 0.0; for (i in 0 until bins) sumAll += i * hist[i]
        var wB = 0.0; var sumB = 0.0; var best = -1.0; var bestT = 0
        for (t in 0 until bins) {
            wB += hist[t]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sumB += t * hist[t]
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestT = t }
        }
        return mn + (bestT + 0.5) * r / (bins - 1)
    }

    fun apply(img: ImageData, t: Double, polarity: Polarity): BinaryMask =
        BinaryMask(img.width, img.height, BooleanArray(img.pixels.size) {
            if (polarity == Polarity.BRIGHT_OBJECTS) img.pixels[it] > t else img.pixels[it] <= t
        })

    fun range(img: ImageData, lo: Double, hi: Double) =
        BinaryMask(img.width, img.height, BooleanArray(img.pixels.size) { img.pixels[it] in lo..hi })

    /**
     * Local-mean adaptive threshold (handles uneven illumination): a pixel is object if it differs from the mean
     * of its (2r+1)² neighbourhood by more than k·σ_global (σ of the whole image). Using the global σ avoids
     * classifying noise in flat background regions as objects.
     */
    fun adaptive(img: ImageData, radius: Int, k: Double, polarity: Polarity): BinaryMask {
        val ii = IntegralImage(img.pixels, img.width, img.height)
        val m = BinaryMask(img.width, img.height)
        val s = sqrt(ii.meanVar(0, 0, img.width - 1, img.height - 1).second)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val (mean, _) = ii.meanVar(x - radius, y - radius, x + radius, y + radius)
            val p = img[x, y]
            m[x, y] = if (polarity == Polarity.BRIGHT_OBJECTS) p > mean + k * s else p < mean - k * s
        }
        return m
    }
}

object Morphology {
    private fun disk(r: Int): List<Pair<Int, Int>> = buildList { for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r) add(dx to dy) }

    fun erode(m: BinaryMask, r: Int): BinaryMask {
        if (r <= 0) return m.copy()
        val k = disk(r); val o = BinaryMask(m.width, m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (!m[x, y]) continue
            o[x, y] = k.all { (dx, dy) -> val xx = x + dx; val yy = y + dy; xx !in 0 until m.width || yy !in 0 until m.height || m[xx, yy] }
        }
        return o
    }

    fun dilate(m: BinaryMask, r: Int): BinaryMask {
        if (r <= 0) return m.copy()
        val k = disk(r); val o = BinaryMask(m.width, m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (!m[x, y]) continue
            for ((dx, dy) in k) { val xx = x + dx; val yy = y + dy; if (xx in 0 until m.width && yy in 0 until m.height) o[xx, yy] = true }
        }
        return o
    }

    fun open(m: BinaryMask, r: Int) = dilate(erode(m, r), r)
    fun close(m: BinaryMask, r: Int) = erode(dilate(m, r), r)

    /** Fills background regions not connected to the image border. */
    fun fillHoles(m: BinaryMask): BinaryMask {
        val w = m.width; val h = m.height
        val outside = BooleanArray(w * h)
        val q = ArrayDeque<Int>()
        fun seed(x: Int, y: Int) { val i = y * w + x; if (!m.bits[i] && !outside[i]) { outside[i] = true; q.addLast(i) } }
        for (x in 0 until w) { seed(x, 0); seed(x, h - 1) }
        for (y in 0 until h) { seed(0, y); seed(w - 1, y) }
        while (q.isNotEmpty()) {
            val i = q.removeFirst(); val x = i % w; val y = i / w
            if (x > 0) seed(x - 1, y); if (x < w - 1) seed(x + 1, y); if (y > 0) seed(x, y - 1); if (y < h - 1) seed(x, y + 1)
        }
        return BinaryMask(w, h, BooleanArray(w * h) { m.bits[it] || !outside[it] })
    }
}

object Labeling {
    /** 8-connected component labelling. Returns label image (0 = background) and number of labels. */
    fun label(m: BinaryMask): Pair<IntArray, Int> {
        val w = m.width; val h = m.height
        val lab = IntArray(w * h); var next = 0
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!m.bits[start] || lab[start] != 0) continue
            next++
            var sp = 0; stack[sp++] = start; lab[start] = next
            while (sp > 0) {
                val i = stack[--sp]; val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (m.bits[j] && lab[j] == 0) { lab[j] = next; stack[sp++] = j }
                }
            }
        }
        return lab to next
    }

    /** Exact Euclidean distance transform (Felzenszwalb & Huttenlocher) of object pixels to background. */
    fun distanceTransform(m: BinaryMask): FloatArray {
        val w = m.width; val h = m.height
        val inf = 1e20
        val f = DoubleArray(w * h) { if (m.bits[it]) inf else 0.0 }
        val tmp = DoubleArray(maxOf(w, h))
        val out = DoubleArray(maxOf(w, h))
        for (x in 0 until w) {
            for (y in 0 until h) tmp[y] = f[y * w + x]
            edt1d(tmp, h, out)
            for (y in 0 until h) f[y * w + x] = out[y]
        }
        for (y in 0 until h) {
            for (x in 0 until w) tmp[x] = f[y * w + x]
            edt1d(tmp, w, out)
            for (x in 0 until w) f[y * w + x] = out[x]
        }
        return FloatArray(w * h) { sqrt(f[it]).toFloat() }
    }

    private fun edt1d(f: DoubleArray, n: Int, d: DoubleArray) {
        val v = IntArray(n); val z = DoubleArray(n + 1)
        var k = 0; v[0] = 0; z[0] = Double.NEGATIVE_INFINITY; z[1] = Double.POSITIVE_INFINITY
        for (q in 1 until n) {
            var s: Double
            while (true) {
                s = ((f[q] + q.toDouble() * q) - (f[v[k]] + v[k].toDouble() * v[k])) / (2.0 * q - 2.0 * v[k])
                if (s <= z[k] && k > 0) k-- else break
            }
            if (s <= z[k]) { v[k] = q; z[k] = Double.NEGATIVE_INFINITY; z[k + 1] = Double.POSITIVE_INFINITY; continue }
            k++; v[k] = q; z[k] = s; z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toDouble()
            d[q] = dq * dq + f[v[k]]
        }
    }

    /**
     * Distance-transform watershed for separating touching convex particles. Seeds are regional maxima of the
     * (lightly smoothed) EDT; two maxima are merged when the saddle between them is higher than
     * (lower peak − tolerance), which is equivalent to ImageJ's "prominence/tolerance" criterion.
     * Returns a label image restricted to the foreground.
     */
    fun watershed(m: BinaryMask, tolerance: Double = 0.5, minSeedDistance: Double = 2.0): Pair<IntArray, Int> {
        val w = m.width; val h = m.height
        val edt0 = distanceTransform(m)
        val edt = Filters.convolveSeparable(edt0, w, h, Filters.gaussianKernel(1.0), Filters.gaussianKernel(1.0))
        for (i in edt.indices) if (!m.bits[i]) edt[i] = 0f
        // Flood from the highest EDT values downwards (priority queue), uniting basins with low prominence.
        val order = (0 until w * h).filter { m.bits[it] }.sortedByDescending { edt[it] }
        val lab = IntArray(w * h)
        val parent = IntArray(order.size + 2) { it }
        val peak = FloatArray(order.size + 2)
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        var next = 0
        for (i in order) {
            val x = i % w; val y = i / w; val v = edt[i]
            var assigned = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val l = lab[yy * w + xx]; if (l == 0) continue
                val r = find(l)
                if (assigned == 0) { assigned = r; continue }
                val ra = find(assigned)
                if (ra == r) continue
                // Two basins meet at level v: merge if one is not prominent enough.
                val lower = minOf(peak[ra], peak[r])
                if (lower - v < tolerance || lower < minSeedDistance) {
                    if (peak[ra] >= peak[r]) parent[r] = ra else parent[ra] = r
                    assigned = find(ra)
                }
            }
            if (assigned == 0) { next++; peak[next] = v; lab[i] = next } else lab[i] = find(assigned)
        }
        // Resolve to final labels; pixels where distinct basins met are kept with their first basin, then
        // a one-pixel separation line is drawn between different basins.
        val remap = IntArray(next + 1); var count = 0
        for (i in 0 until w * h) if (lab[i] != 0) { val r = find(lab[i]); if (remap[r] == 0) remap[r] = ++count; lab[i] = remap[r] }
        val out = lab.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val l = lab[y * w + x]; if (l == 0) continue
            if (x + 1 < w && lab[y * w + x + 1] != 0 && lab[y * w + x + 1] != l && l > lab[y * w + x + 1]) out[y * w + x] = 0
            if (y + 1 < h && lab[(y + 1) * w + x] != 0 && lab[(y + 1) * w + x] != l && l > lab[(y + 1) * w + x]) out[y * w + x] = 0
        }
        // Relabel by connectivity so that each id is one connected region.
        val mask = BinaryMask(w, h, BooleanArray(w * h) { out[it] != 0 })
        val (cc, n) = label4(mask, out)
        return cc to n
    }

    /** Connected components that also respect existing label boundaries (8-neighbours with equal label). */
    private fun label4(m: BinaryMask, basin: IntArray): Pair<IntArray, Int> {
        val w = m.width; val h = m.height
        val lab = IntArray(w * h); var next = 0
        val stack = IntArray(w * h)
        for (s in 0 until w * h) {
            if (!m.bits[s] || lab[s] != 0) continue
            next++; var sp = 0; stack[sp++] = s; lab[s] = next
            while (sp > 0) {
                val i = stack[--sp]; val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (m.bits[j] && lab[j] == 0 && basin[j] == basin[i]) { lab[j] = next; stack[sp++] = j }
                }
            }
        }
        return lab to next
    }
}

/** Full segmentation workflow producing a label image. */
object Segmenter {
    fun mask(img: ImageData, p: SegmentationParams): Pair<BinaryMask, Double> {
        val src = if (p.preSmoothSigma > 0) Filters.gaussian(img, p.preSmoothSigma) else img
        var thr = Double.NaN
        var m = when (p.method) {
            ThresholdMethod.OTSU -> { thr = Threshold.otsu(src.pixels); Threshold.apply(src, thr, p.polarity) }
            ThresholdMethod.MANUAL -> { thr = if (p.manualThreshold.isNaN()) Threshold.otsu(src.pixels) else p.manualThreshold; Threshold.apply(src, thr, p.polarity) }
            ThresholdMethod.ADAPTIVE -> Threshold.adaptive(src, p.adaptiveRadius, p.adaptiveOffset, p.polarity)
            ThresholdMethod.INTENSITY_RANGE -> Threshold.range(src, p.rangeLow, p.rangeHigh)
            ThresholdMethod.EDGES -> {
                val g = Filters.sobel(src)
                thr = Threshold.otsu(g.pixels)
                Morphology.fillHoles(Morphology.close(Threshold.apply(g, thr, Polarity.BRIGHT_OBJECTS), 1))
            }
        }
        if (p.closeRadius > 0) m = Morphology.close(m, p.closeRadius)
        if (p.fillHoles) m = Morphology.fillHoles(m)
        if (p.method == ThresholdMethod.EDGES || p.method == ThresholdMethod.ADAPTIVE) m = refineLocalMidpoint(src, m, p.polarity)
        if (p.openRadius > 0) m = Morphology.open(m, p.openRadius)
        return m to thr
    }

    /**
     * Places each object boundary at the local 50 % level between the object interior (core = eroded object)
     * and the surrounding background ring. Edge- and adaptive-based masks otherwise include part of the
     * intensity transition band and overestimate sizes.
     */
    fun refineLocalMidpoint(img: ImageData, m: BinaryMask, polarity: Polarity): BinaryMask {
        val (lab, n) = Labeling.label(m)
        if (n == 0) return m
        val w = m.width; val h = m.height
        val minX = IntArray(n + 1) { w }; val minY = IntArray(n + 1) { h }; val maxX = IntArray(n + 1) { -1 }; val maxY = IntArray(n + 1) { -1 }
        for (i in lab.indices) { val l = lab[i]; if (l == 0) continue; val x = i % w; val y = i / w
            if (x < minX[l]) minX[l] = x; if (y < minY[l]) minY[l] = y; if (x > maxX[l]) maxX[l] = x; if (y > maxY[l]) maxY[l] = y }
        val out = BinaryMask(w, h)
        val pad = 6
        for (l in 1..n) {
            val x0 = maxOf(0, minX[l] - pad); val y0 = maxOf(0, minY[l] - pad)
            val x1 = minOf(w - 1, maxX[l] + pad); val y1 = minOf(h - 1, maxY[l] + pad)
            val lw = x1 - x0 + 1; val lh = y1 - y0 + 1
            val local = BinaryMask(lw, lh)
            for (y in 0 until lh) for (x in 0 until lw) local[x, y] = lab[(y + y0) * w + x + x0] == l
            val core = Morphology.erode(local, 2).let { if (it.count() == 0) local else it }
            val outer = Morphology.dilate(local, 5); val inner = Morphology.dilate(local, 2)
            var cs = 0.0; var cn = 0; var bs = 0.0; var bn = 0
            for (y in 0 until lh) for (x in 0 until lw) {
                val v = img[x + x0, y + y0]
                if (core[x, y]) { cs += v; cn++ }
                if (outer[x, y] && !inner[x, y] && lab[(y + y0) * w + x + x0].let { it == 0 || it == l }) { bs += v; bn++ }
            }
            if (cn == 0 || bn == 0) { for (y in 0 until lh) for (x in 0 until lw) if (local[x, y]) out[x + x0, y + y0] = true; continue }
            val thr = (cs / cn + bs / bn) / 2
            val zone = Morphology.dilate(local, 3)
            val sel = BinaryMask(lw, lh)
            for (y in 0 until lh) for (x in 0 until lw) {
                if (!zone[x, y]) continue
                val v = img[x + x0, y + y0]
                sel[x, y] = if (polarity == Polarity.BRIGHT_OBJECTS) v > thr else v < thr
            }
            // keep only the part connected to the original object
            val (sl, _) = Labeling.label(sel)
            val keep = HashSet<Int>()
            for (y in 0 until lh) for (x in 0 until lw) if (core[x, y] && sl[y * lw + x] != 0) keep += sl[y * lw + x]
            for (y in 0 until lh) for (x in 0 until lw) if (sl[y * lw + x] in keep) out[x + x0, y + y0] = true
        }
        return Morphology.fillHoles(out)
    }

    fun labels(img: ImageData, p: SegmentationParams): Triple<IntArray, Int, Double> {
        val (m, thr) = mask(img, p)
        val (lab, n) = if (p.watershed) Labeling.watershed(m, tolerance = 0.5, minSeedDistance = p.watershedMinDistance) else Labeling.label(m)
        return Triple(lab, n, thr)
    }
}
