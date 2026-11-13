package com.temlab.core.crystal

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sqrt

enum class CrystalSystem { CUBIC, TETRAGONAL, ORTHORHOMBIC, HEXAGONAL, TRIGONAL_R, MONOCLINIC, TRICLINIC }

/** Lattice centring / additional reflection conditions used to discard systematically absent hkl. */
enum class Centering(val label: String) {
    P("Primitive"), I("Body-centred (h+k+l=2n)"), F("Face-centred (h,k,l unmixed)"), C("C-centred (h+k=2n)"),
    A("A-centred (k+l=2n)"), B("B-centred (h+l=2n)"),
    R_OBV("Rhombohedral, hexagonal axes (−h+k+l=3n)"),
    DIAMOND("Diamond Fd-3m (F + h+k+l=4n for all-even)"),
    HCP("Hexagonal close packed P6₃/mmc (hh2h̄l: l=2n; 00l: l=2n)");

    fun allowed(h: Int, k: Int, l: Int): Boolean = when (this) {
        P -> true
        I -> (h + k + l) % 2 == 0
        F -> (h and 1) == (k and 1) && (k and 1) == (l and 1)
        C -> (h + k) % 2 == 0
        A -> (k + l) % 2 == 0
        B -> (h + l) % 2 == 0
        R_OBV -> Math.floorMod(-h + k + l, 3) == 0
        DIAMOND -> F.allowed(h, k, l) && (!(h % 2 == 0 && k % 2 == 0 && l % 2 == 0) || Math.floorMod(h + k + l, 4) == 0)
        HCP -> !(Math.floorMod(h - k, 3) == 0 && l % 2 != 0) // includes 00l with odd l (h=k=0)
    }
}

/** Unit cell; lengths in Å, angles in degrees. */
@Serializable
data class Lattice(
    val name: String,
    val system: CrystalSystem,
    val a: Double, val b: Double, val c: Double,
    val alpha: Double = 90.0, val beta: Double = 90.0, val gamma: Double = 90.0,
    val centering: Centering = Centering.P,
    val note: String = "",
) {
    /** Reciprocal metric tensor G* = G⁻¹ (Å⁻²). */
    val gStar: Array<DoubleArray> by lazy {
        val ca = cos(Math.toRadians(alpha)); val cb = cos(Math.toRadians(beta)); val cg = cos(Math.toRadians(gamma))
        val g = arrayOf(
            doubleArrayOf(a * a, a * b * cg, a * c * cb),
            doubleArrayOf(a * b * cg, b * b, b * c * ca),
            doubleArrayOf(a * c * cb, b * c * ca, c * c),
        )
        invert3(g)
    }

    fun gSquared(h: Int, k: Int, l: Int): Double {
        val v = doubleArrayOf(h.toDouble(), k.toDouble(), l.toDouble())
        var s = 0.0
        for (i in 0..2) for (j in 0..2) s += v[i] * gStar[i][j] * v[j]
        return s
    }

    /** Interplanar spacing d(hkl) in Å. */
    fun d(h: Int, k: Int, l: Int): Double = 1.0 / sqrt(gSquared(h, k, l))

    /** Angle between the normals of planes (h1k1l1) and (h2k2l2), degrees. */
    fun interplanarAngle(p: Triple<Int, Int, Int>, q: Triple<Int, Int, Int>): Double {
        val u = doubleArrayOf(p.first.toDouble(), p.second.toDouble(), p.third.toDouble())
        val v = doubleArrayOf(q.first.toDouble(), q.second.toDouble(), q.third.toDouble())
        var uv = 0.0
        for (i in 0..2) for (j in 0..2) uv += u[i] * gStar[i][j] * v[j]
        val c = uv / sqrt(gSquared(p.first, p.second, p.third) * gSquared(q.first, q.second, q.third))
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    companion object {
        private fun invert3(m: Array<DoubleArray>): Array<DoubleArray> {
            val (a, b, c) = m[0].let { Triple(it[0], it[1], it[2]) }
            val (d, e, f) = m[1].let { Triple(it[0], it[1], it[2]) }
            val (g, h, i) = m[2].let { Triple(it[0], it[1], it[2]) }
            val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
            require(abs(det) > 1e-12) { "Singular metric tensor (check lattice parameters)" }
            return arrayOf(
                doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
                doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
                doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
            )
        }

        fun cubic(name: String, a: Double, cen: Centering, note: String = "") = Lattice(name, CrystalSystem.CUBIC, a, a, a, centering = cen, note = note)
        fun tetragonal(name: String, a: Double, c: Double, cen: Centering, note: String = "") = Lattice(name, CrystalSystem.TETRAGONAL, a, a, c, centering = cen, note = note)
        fun hexagonal(name: String, a: Double, c: Double, cen: Centering, note: String = "") = Lattice(name, CrystalSystem.HEXAGONAL, a, a, c, 90.0, 90.0, 120.0, cen, note)

        /** Reference cells (room temperature literature values). Users can add their own. */
        val PRESETS: List<Lattice> = listOf(
            cubic("Au (Fm-3m)", 4.0782, Centering.F),
            cubic("Ag (Fm-3m)", 4.0862, Centering.F),
            cubic("Pt (Fm-3m)", 3.9242, Centering.F),
            cubic("Pd (Fm-3m)", 3.8907, Centering.F),
            cubic("Cu (Fm-3m)", 3.6149, Centering.F),
            cubic("Ni (Fm-3m)", 3.5240, Centering.F),
            cubic("Al (Fm-3m)", 4.0495, Centering.F),
            cubic("α-Fe (Im-3m)", 2.8665, Centering.I),
            cubic("Si (Fd-3m)", 5.4310, Centering.DIAMOND),
            cubic("CeO₂ (Fm-3m)", 5.411, Centering.F),
            cubic("Fe₃O₄ (Fd-3m)", 8.396, Centering.DIAMOND, "spinel; diamond-type conditions approximate the full Fd-3m rules"),
            cubic("MgO (Fm-3m)", 4.212, Centering.F),
            tetragonal("TiO₂ anatase (I4₁/amd)", 3.7845, 9.5143, Centering.I, "only I-centring applied; additional glide/screw absences not applied"),
            tetragonal("TiO₂ rutile (P4₂/mnm)", 4.5937, 2.9587, Centering.P, "screw/glide absences not applied"),
            tetragonal("SnO₂ (P4₂/mnm)", 4.7382, 3.1871, Centering.P, "screw/glide absences not applied"),
            hexagonal("ZnO wurtzite (P6₃mc)", 3.2498, 5.2066, Centering.HCP),
            hexagonal("Graphite 2H (P6₃/mmc)", 2.464, 6.711, Centering.HCP),
            hexagonal("α-Fe₂O₃ (R-3c, hex. axes)", 5.0356, 13.7489, Centering.R_OBV, "c-glide absences not applied"),
            hexagonal("Ti α (P6₃/mmc)", 2.9508, 4.6855, Centering.HCP),
        )
    }
}

@Serializable
data class HklCandidate(val h: Int, val k: Int, val l: Int, val d: Double, val deviationPercent: Double, val multiplicityGroup: String)

@Serializable
data class PairCandidate(val first: HklCandidate, val second: HklCandidate, val angleTheory: Double, val angleDeviation: Double)

object CrystalMatcher {
    /** All allowed reflections up to |index| <= maxIndex, grouped by d (rounded) -> one representative per family. */
    fun reflections(lat: Lattice, maxIndex: Int = 4, dMin: Double = 0.5): List<HklCandidate> {
        val out = ArrayList<HklCandidate>()
        for (h in -maxIndex..maxIndex) for (k in -maxIndex..maxIndex) for (l in -maxIndex..maxIndex) {
            if (h == 0 && k == 0 && l == 0) continue
            if (!lat.centering.allowed(h, k, l)) continue
            val d = lat.d(h, k, l)
            if (d < dMin) continue
            out += HklCandidate(h, k, l, d, 0.0, "")
        }
        return out
    }

    /** Families {hkl}: one representative (largest indices first, positive) per distinct d. */
    fun families(lat: Lattice, maxIndex: Int = 4, dMin: Double = 0.5): List<HklCandidate> =
        reflections(lat, maxIndex, dMin).groupBy { Math.round(it.d * 1e4) }.values
            .map { g -> g.maxWith(compareBy<HklCandidate>({ it.h + it.k + it.l }, { it.h }, { it.k })).copy(multiplicityGroup = "×${g.size}") }
            .sortedByDescending { it.d }

    /**
     * Candidate families whose d lies within [tolerancePercent] of the measured d (both Å).
     * Results are TENTATIVE: a single spacing never identifies a phase.
     */
    fun matchD(lat: Lattice, dMeasured: Double, tolerancePercent: Double = 3.0, maxIndex: Int = 4): List<HklCandidate> =
        families(lat, maxIndex).map { it.copy(deviationPercent = (dMeasured - it.d) / it.d * 100) }
            .filter { abs(it.deviationPercent) <= tolerancePercent }.sortedBy { abs(it.deviationPercent) }

    /** Pairs of individual reflections compatible with two measured spacings AND the measured angle between them. */
    fun matchPair(lat: Lattice, d1: Double, d2: Double, angleDeg: Double, tolD: Double = 3.0, tolAngle: Double = 2.0, maxIndex: Int = 3): List<PairCandidate> {
        val all = reflections(lat, maxIndex)
        val c1 = all.filter { abs(d1 - it.d) / it.d * 100 <= tolD }
        val c2 = all.filter { abs(d2 - it.d) / it.d * 100 <= tolD }
        val out = ArrayList<PairCandidate>()
        for (p in c1) for (q in c2) {
            if (p.h == q.h && p.k == q.k && p.l == q.l) continue
            val a = lat.interplanarAngle(Triple(p.h, p.k, p.l), Triple(q.h, q.k, q.l))
            if (abs(a - angleDeg) <= tolAngle) out += PairCandidate(
                p.copy(deviationPercent = (d1 - p.d) / p.d * 100), q.copy(deviationPercent = (d2 - q.d) / q.d * 100), a, angleDeg - a)
        }
        // Keep one pair per (family, family, angle) combination
        return out.distinctBy { Triple(Math.round(it.first.d * 1e3), Math.round(it.second.d * 1e3), Math.round(it.angleTheory * 10)) }
            .sortedBy { abs(it.angleDeviation) + abs(it.first.deviationPercent) + abs(it.second.deviationPercent) }.take(20)
    }

    /** Zone axis [uvw] = g1 × g2 (cross product of plane indices; exact for any lattice in direct-space indices). */
    fun zoneAxis(p: HklCandidate, q: HklCandidate): Triple<Int, Int, Int> {
        var u = p.k * q.l - p.l * q.k; var v = p.l * q.h - p.h * q.l; var w = p.h * q.k - p.k * q.h
        val g = gcd(gcd(abs(u), abs(v)), abs(w)).coerceAtLeast(1)
        u /= g; v /= g; w /= g
        return Triple(u, v, w)
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    fun formatHkl(h: Int, k: Int, l: Int) = "(" + listOf(h, k, l).joinToString("") { if (it < 0) "${-it}̅" else "$it" } + ")"
}
