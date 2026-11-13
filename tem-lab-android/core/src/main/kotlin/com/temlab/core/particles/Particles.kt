/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core.particles

import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.segmentation.BinaryMask
import com.temlab.core.segmentation.Labeling
import com.temlab.core.segmentation.SegmentationParams
import com.temlab.core.segmentation.Segmenter
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class ParticleMeasurement(
    val area: Double,
    val perimeter: Double,
    val ecd: Double,            // equivalent circular diameter
    val feretMax: Double,
    val feretMin: Double,
    val feretAngle: Double,     // degrees
    val majorAxis: Double,
    val minorAxis: Double,
    val ellipseAngle: Double,   // degrees, counter-clockwise from +x
    val aspectRatio: Double,
    val circularity: Double,
    val centroidX: Double,      // physical units
    val centroidY: Double,
    val meanIntensity: Double,
    val stdIntensity: Double,
    val pixelCount: Int,
    val touchesBorder: Boolean,
)

enum class ParticleOrigin { AUTO, MANUAL, EDITED, MERGED, SPLIT }

/** A particle as a set of pixel indices (y*w+x) in the source image. */
@Serializable
class Particle(
    val id: Int,
    var pixels: IntArray,
    var origin: ParticleOrigin = ParticleOrigin.AUTO,
    var excluded: Boolean = false,          // user exclusion
    var filteredOut: Boolean = false,       // excluded by size/shape filters
    var measurement: ParticleMeasurement? = null,
    var contour: List<Pair<Double, Double>> = emptyList(), // pixel-coordinate outline (pixel corners)
) {
    val included: Boolean get() = !excluded && !filteredOut
}

@Serializable
data class ParticleFilter(
    val minArea: Double = 0.0, val maxArea: Double = Double.MAX_VALUE,          // physical units² (px² if uncalibrated)
    val minCircularity: Double = 0.0, val maxCircularity: Double = 1.0,
    val minAspect: Double = 1.0, val maxAspect: Double = Double.MAX_VALUE,
    val excludeBorder: Boolean = true,
    val minPixels: Int = 10,
)

enum class ParticleVariable(val label: String, val isLength: Boolean, val isArea: Boolean = false) {
    ECD("Equivalent circular diameter", true), AREA("Area", false, true), PERIMETER("Perimeter", true),
    FERET_MAX("Feret max", true), FERET_MIN("Feret min", true), MAJOR("Major axis", true), MINOR("Minor axis", true),
    ASPECT("Aspect ratio", false), CIRCULARITY("Circularity", false), MEAN_INTENSITY("Mean intensity", false);

    fun of(m: ParticleMeasurement): Double = when (this) {
        ECD -> m.ecd; AREA -> m.area; PERIMETER -> m.perimeter; FERET_MAX -> m.feretMax; FERET_MIN -> m.feretMin
        MAJOR -> m.majorAxis; MINOR -> m.minorAxis; ASPECT -> m.aspectRatio; CIRCULARITY -> m.circularity; MEAN_INTENSITY -> m.meanIntensity
    }
}

/**
 * Holds the particles of one micrograph and keeps measurements consistent after every edit (delete, add,
 * exclude, merge, split, contour change). Statistics/histograms are derived from [included] on demand,
 * so any change propagates immediately.
 */
class ParticleSet(val image: ImageData) {
    val w = image.width; val h = image.height
    val particles = ArrayList<Particle>()
    var filter = ParticleFilter(); private set
    private var nextId = 1
    var revision = 0; private set
    var threshold: Double = Double.NaN

    val included: List<Particle> get() = particles.filter { it.included }

    fun segment(params: SegmentationParams, filter: ParticleFilter = ParticleFilter(excludeBorder = params.excludeBorder)) {
        val (lab, n, thr) = Segmenter.labels(image, params)
        threshold = thr
        loadLabels(lab, n)
        setFilter(filter)
    }

    fun loadLabels(lab: IntArray, n: Int) {
        particles.clear(); nextId = 1
        val buckets = Array(n + 1) { IntArrayBuilder() }
        for (i in lab.indices) if (lab[i] > 0) buckets[lab[i]].add(i)
        for (l in 1..n) if (buckets[l].size > 0) particles += Particle(nextId++, buckets[l].toArray())
        particles.forEach { measure(it) }
        changed()
    }

    fun setFilter(f: ParticleFilter) { filter = f; applyFilter(); changed() }

    private fun applyFilter() {
        for (p in particles) {
            val m = p.measurement ?: continue
            p.filteredOut = p.pixels.size < filter.minPixels ||
                m.area < filter.minArea || m.area > filter.maxArea ||
                m.circularity < filter.minCircularity || m.circularity > filter.maxCircularity ||
                m.aspectRatio < filter.minAspect || m.aspectRatio > filter.maxAspect ||
                (filter.excludeBorder && m.touchesBorder)
        }
    }

    private fun changed() { revision++ }

    fun byId(id: Int) = particles.firstOrNull { it.id == id }

    fun particleAt(x: Int, y: Int): Particle? {
        if (x !in 0 until w || y !in 0 until h) return null
        val idx = y * w + x
        return particles.firstOrNull { p -> java.util.Arrays.binarySearch(p.pixels, idx) >= 0 }
    }

    fun delete(id: Int) { particles.removeAll { it.id == id }; changed() }

    fun setExcluded(id: Int, ex: Boolean) { byId(id)?.excluded = ex; changed() }

    /** Adds a particle from a polygon (pixel coordinates). Pixels already belonging to others are reassigned. */
    fun addPolygon(poly: List<Pair<Double, Double>>, origin: ParticleOrigin = ParticleOrigin.MANUAL): Particle? {
        val px = rasterize(poly)
        if (px.size < 3) return null
        removePixelsFromOthers(px, -1)
        val p = Particle(nextId++, px, origin)
        particles += p; measure(p); applyFilter(); changed()
        return p
    }

    /** Replaces the outline of an existing particle with an edited polygon. */
    fun replaceContour(id: Int, poly: List<Pair<Double, Double>>): Boolean {
        val p = byId(id) ?: return false
        val px = rasterize(poly)
        if (px.size < 3) return false
        removePixelsFromOthers(px, id)
        p.pixels = px; p.origin = ParticleOrigin.EDITED
        measure(p); applyFilter(); changed(); return true
    }

    fun merge(ids: Collection<Int>): Particle? {
        val ps = particles.filter { it.id in ids }
        if (ps.size < 2) return null
        val all = ps.flatMap { it.pixels.asList() }.toIntArray().also { it.sort() }
        particles.removeAll(ps.toSet())
        val p = Particle(ps.minOf { it.id }, all, ParticleOrigin.MERGED)
        particles += p; particles.sortBy { it.id }
        measure(p); applyFilter(); changed(); return p
    }

    /** Splits a particle along a cut line (pixel coordinates); pixels within 1 px of the line are removed. */
    fun split(id: Int, x0: Double, y0: Double, x1: Double, y1: Double): List<Particle> {
        val p = byId(id) ?: return emptyList()
        val m = BinaryMask(w, h)
        val len = hypot(x1 - x0, y1 - y0).coerceAtLeast(1e-9)
        for (i in p.pixels) {
            val x = i % w + 0.5; val y = i / w + 0.5
            val t = ((x - x0) * (x1 - x0) + (y - y0) * (y1 - y0)) / (len * len)
            val dist = abs((x - x0) * (y1 - y0) - (y - y0) * (x1 - x0)) / len
            if (t in 0.0..1.0 && dist < 0.75) continue
            m.bits[i] = true
        }
        val (lab, n) = Labeling.label4Conn(m)
        if (n < 2) return emptyList()
        particles.remove(p)
        val out = ArrayList<Particle>()
        val buckets = Array(n + 1) { IntArrayBuilder() }
        for (i in lab.indices) if (lab[i] > 0) buckets[lab[i]].add(i)
        for (l in 1..n) {
            if (buckets[l].size == 0) continue
            val np = Particle(if (out.isEmpty()) p.id else nextId++, buckets[l].toArray(), ParticleOrigin.SPLIT)
            measure(np); out += np; particles += np
        }
        particles.sortBy { it.id }
        applyFilter(); changed()
        return out
    }

    private fun removePixelsFromOthers(px: IntArray, keepId: Int) {
        val set = px.toHashSet()
        val it = particles.iterator()
        while (it.hasNext()) {
            val o = it.next(); if (o.id == keepId) continue
            if (o.pixels.any { v -> v in set }) {
                o.pixels = o.pixels.filter { v -> v !in set }.toIntArray()
                if (o.pixels.size < 3) it.remove() else { o.origin = ParticleOrigin.EDITED; measure(o) }
            }
        }
    }

    /** Scanline polygon fill using pixel centres. */
    fun rasterize(poly: List<Pair<Double, Double>>): IntArray {
        if (poly.size < 3) return IntArray(0)
        val out = IntArrayBuilder()
        val minY = max(0, poly.minOf { it.second }.toInt()); val maxY = min(h - 1, poly.maxOf { it.second }.toInt() + 1)
        val xs = DoubleArray(poly.size)
        for (y in minY..maxY) {
            val yc = y + 0.5; var n = 0
            for (i in poly.indices) {
                val (ax, ay) = poly[i]; val (bx, by) = poly[(i + 1) % poly.size]
                if ((ay <= yc && by > yc) || (by <= yc && ay > yc)) xs[n++] = ax + (yc - ay) / (by - ay) * (bx - ax)
            }
            java.util.Arrays.sort(xs, 0, n)
            var k = 0
            while (k + 1 < n) {
                val xa = max(0, kotlin.math.ceil(xs[k] - 0.5).toInt()); val xb = min(w - 1, kotlin.math.floor(xs[k + 1] - 0.5).toInt())
                for (x in xa..xb) out.add(y * w + x)
                k += 2
            }
        }
        return out.toArray().also { it.sort() }
    }

    fun measure(p: Particle) {
        p.pixels.sort()
        p.measurement = ParticleMeasurer.measure(p.pixels, image, w, h)
        p.contour = ParticleMeasurer.outline(p.pixels, w, h)
    }

    fun values(v: ParticleVariable): DoubleArray = included.mapNotNull { it.measurement?.let { m -> v.of(m) } }.toDoubleArray()
}

class IntArrayBuilder {
    private var a = IntArray(16); var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray(): IntArray = a.copyOf(size)
}

fun Labeling.label4Conn(m: BinaryMask) = label(m)

object ParticleMeasurer {

    fun measure(px: IntArray, img: ImageData, w: Int, h: Int): ParticleMeasurement {
        val cal: Calibration = img.calibration
        val pw = cal.pixelWidth; val ph = cal.pixelHeight
        val n = px.size
        var sx = 0.0; var sy = 0.0; var si = 0.0; var si2 = 0.0; var border = false
        for (i in px) {
            val x = i % w; val y = i / w
            sx += x + 0.5; sy += y + 0.5
            val v = img.pixels[i].toDouble(); si += v; si2 += v * v
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
        }
        val cx = sx / n; val cy = sy / n
        var mxx = 0.0; var myy = 0.0; var mxy = 0.0
        for (i in px) {
            val dx = (i % w + 0.5 - cx) * pw; val dy = (i / w + 0.5 - cy) * ph
            mxx += dx * dx; myy += dy * dy; mxy += dx * dy
        }
        // Second moments of the filled pixel squares (+1/12 of pixel size² for intra-pixel spread).
        mxx = mxx / n + pw * pw / 12; myy = myy / n + ph * ph / 12; mxy /= n
        val common = sqrt(((mxx - myy) / 2) * ((mxx - myy) / 2) + mxy * mxy)
        val l1 = (mxx + myy) / 2 + common; val l2 = max(0.0, (mxx + myy) / 2 - common)
        val major = 4 * sqrt(l1); val minor = 4 * sqrt(l2)
        val ellAngle = Math.toDegrees(0.5 * atan2(-2 * mxy, mxx - myy)) // y axis up
        val area = n * pw * ph
        val perim = perimeter(px, w, h, pw, ph)
        val hull = convexHull(px, w, pw, ph)
        val (fMax, fAngle) = feretMax(hull, pw, ph)
        val fMin = feretMin(hull, pw, ph)
        val mean = si / n
        val sd = if (n > 1) sqrt(max(0.0, (si2 - n * mean * mean) / (n - 1))) else 0.0
        return ParticleMeasurement(
            area = area, perimeter = perim, ecd = 2 * sqrt(area / PI), feretMax = fMax, feretMin = fMin, feretAngle = fAngle,
            majorAxis = major, minorAxis = minor, ellipseAngle = ellAngle,
            aspectRatio = if (minor > 0) major / minor else Double.POSITIVE_INFINITY,
            circularity = if (perim > 0) min(1.0, 4 * PI * area / (perim * perim)) else 0.0,
            centroidX = cx * pw, centroidY = cy * ph, meanIntensity = mean, stdIntensity = sd, pixelCount = n, touchesBorder = border,
        )
    }

    /**
     * Perimeter from the 8-connected boundary chain code using the Vossepoel–Smeulders estimator
     * P = 0.980·n_even + 1.406·n_odd − 0.091·n_corners (error ≈ 1 % for discretised circles).
     * For anisotropic pixels each step is scaled by its physical length instead.
     */
    fun perimeter(px: IntArray, w: Int, h: Int, pw: Double, ph: Double): Double {
        val chain = chainCode(px, w, h)
        if (chain.isEmpty()) return 4 * pw
        val iso = abs(pw - ph) < 1e-9 * max(pw, ph)
        if (iso) {
            var ne = 0; var no = 0; var nc = 0
            for (k in chain.indices) {
                if (chain[k] % 2 == 0) ne++ else no++
                if (chain[k] != chain[(k + 1) % chain.size]) nc++
            }
            // The traced contour passes through pixel centres, i.e. 0.5 px inside the object edge; offsetting a
            // closed convex curve outwards by r adds 2πr to its length, hence + π·(0.5·2) px.
            return (0.980 * ne + 1.406 * no - 0.091 * nc + PI) * pw
        }
        var s = 0.0
        for (c in chain) s += hypot(DX[c] * pw, DY[c] * ph)
        return s + PI * sqrt(pw * ph)
    }

    private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)

    /**
     * Boundary tracing (Freeman 8-direction chain code, directions counter-clockwise from +x, y down).
     * Starts at the top-left pixel; the search for the next boundary pixel starts at (d+7) mod 8 for even d
     * and (d+6) mod 8 for odd d. Stops with Jacob's criterion (back at the start with the same first move).
     */
    fun chainCode(px: IntArray, w: Int, h: Int): IntArray {
        if (px.size <= 1) return IntArray(0)
        val set = px.toHashSet()
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && (y * w + x) in set
        val start = px.min()
        val sx = start % w; val sy = start / w
        val codes = ArrayList<Int>()
        var x = sx; var y = sy; var dir = 7
        val limit = 8 * px.size + 16
        while (codes.size < limit) {
            var d = if (dir % 2 == 0) (dir + 7) % 8 else (dir + 6) % 8
            var found = -1
            for (k in 0 until 8) {
                if (inside(x + DX[d], y + DY[d])) { found = d; break }
                d = (d + 1) % 8
            }
            if (found < 0) break
            if (x == sx && y == sy && codes.isNotEmpty() && found == codes[0]) break
            codes += found; x += DX[found]; y += DY[found]; dir = found
        }
        return codes.toIntArray()
    }

    /** Outline polygon through pixel centres (for display/editing). */
    fun outline(px: IntArray, w: Int, h: Int): List<Pair<Double, Double>> {
        val codes = chainCode(px, w, h)
        if (px.isEmpty()) return emptyList()
        val s = px.min(); var x = s % w; var y = s / w
        val pts = ArrayList<Pair<Double, Double>>(codes.size + 1)
        pts += (x + 0.5) to (y + 0.5)
        for (c in codes) { x += DX[c]; y += DY[c]; pts += (x + 0.5) to (y + 0.5) }
        return simplify(pts)
    }

    private fun simplify(p: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (p.size < 4) return p
        val out = ArrayList<Pair<Double, Double>>()
        out += p[0]
        for (i in 1 until p.size - 1) {
            val (ax, ay) = out.last(); val (bx, by) = p[i]; val (cx, cy) = p[i + 1]
            if (abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) > 1e-9) out += p[i]
        }
        out += p.last()
        return out
    }

    /** Convex hull of pixel centres (physical coordinates), monotone chain. Only row extremes are candidates. */
    fun convexHull(px: IntArray, w: Int, pw: Double, ph: Double): List<Pair<Double, Double>> {
        val rows = HashMap<Int, IntArray>()
        for (i in px) {
            val x = i % w; val y = i / w
            val r = rows.getOrPut(y) { intArrayOf(x, x) }
            if (x < r[0]) r[0] = x; if (x > r[1]) r[1] = x
        }
        val pts = ArrayList<Pair<Double, Double>>()
        for ((y, r) in rows) { pts += ((r[0] + 0.5) * pw) to ((y + 0.5) * ph); if (r[1] != r[0]) pts += ((r[1] + 0.5) * pw) to ((y + 0.5) * ph) }
        val s = pts.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (s.size < 3) return s
        fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = ArrayList<Pair<Double, Double>>()
        for (p in s) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.size - 1); lower += p }
        val upper = ArrayList<Pair<Double, Double>>()
        for (p in s.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.size - 1); upper += p }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** Physical length of one pixel step along direction (dx, dy). */
    private fun pixelExtent(dx: Double, dy: Double, pw: Double, ph: Double): Double {
        val l = hypot(dx, dy); if (l == 0.0) return pw
        val c = dx / l; val s = dy / l
        // A step of one pixel along the direction, in pixel units, scaled by the pixel size.
        return 1.0 / hypot(c / pw, s / ph)
    }

    /**
     * Maximum Feret diameter: largest distance between hull pixel centres plus half a pixel extent along that
     * direction. Calibrated on digitised discs/squares: centre-to-centre underestimates (≈ −0.1 px for discs,
     * −1.4 px on square diagonals) and pixel-corner hulls overestimate (≈ +1 px); the +0.5 px compromise keeps
     * the bias within ±0.7 px. Digitisation uncertainty of Feret diameters is therefore ≈ ±1 px.
     */
    fun feretMax(hull: List<Pair<Double, Double>>, pw: Double = 1.0, ph: Double = 1.0): Pair<Double, Double> {
        var best = 0.0; var ang = 0.0; var bdx = 1.0; var bdy = 0.0
        for (i in hull.indices) for (j in i + 1 until hull.size) {
            val dx = hull[j].first - hull[i].first; val dy = hull[j].second - hull[i].second
            val d = hypot(dx, dy)
            if (d > best) { best = d; bdx = dx; bdy = dy; ang = Math.toDegrees(atan2(-dy, dx)).let { if (it < 0) it + 180 else it } }
        }
        return (best + 0.5 * pixelExtent(bdx, bdy, pw, ph)) to ang
    }

    /** Minimum caliper width over hull edge directions, plus one pixel extent along the normal. */
    fun feretMin(hull: List<Pair<Double, Double>>, pw: Double = 1.0, ph: Double = 1.0): Double {
        if (hull.size < 3) return if (hull.size == 2) min(pw, ph) else min(pw, ph)
        var best = Double.MAX_VALUE; var bnx = 1.0; var bny = 0.0
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            val ex = b.first - a.first; val ey = b.second - a.second
            val len = hypot(ex, ey); if (len == 0.0) continue
            var mx = 0.0
            for (p in hull) mx = max(mx, abs(ex * (p.second - a.second) - ey * (p.first - a.first)) / len)
            if (mx < best) { best = mx; bnx = -ey; bny = ex }
        }
        return best + pixelExtent(bnx, bny, pw, ph)
    }

    @Suppress("unused") private fun rot(x: Double, y: Double, a: Double) = (x * cos(a) - y * sin(a)) to (x * sin(a) + y * cos(a))
}
