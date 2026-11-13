package com.temlab.core.stats

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

@Serializable
data class Descriptive(
    val n: Int, val mean: Double, val median: Double, val sd: Double, val sem: Double, val cv: Double,
    val min: Double, val max: Double, val p10: Double, val p25: Double, val p75: Double, val p90: Double,
)

@Serializable
data class HistogramBins(val start: Double, val width: Double, val counts: IntArray) {
    val edges: DoubleArray get() = DoubleArray(counts.size + 1) { start + it * width }
    val total: Int get() = counts.sum()
    fun relative(): DoubleArray = DoubleArray(counts.size) { if (total > 0) counts[it].toDouble() / total else 0.0 }
}

@Serializable
data class LognormalFit(val mu: Double, val sigma: Double, val ksStatistic: Double, val ksPValue: Double, val n: Int) {
    /** Median exp(μ), mean exp(μ+σ²/2), mode exp(μ−σ²), geometric SD exp(σ). */
    val median: Double get() = exp(mu)
    val mean: Double get() = exp(mu + sigma * sigma / 2)
    val mode: Double get() = exp(mu - sigma * sigma)
    val geometricSd: Double get() = exp(sigma)
    fun pdf(x: Double): Double = if (x <= 0) 0.0 else exp(-(ln(x) - mu) * (ln(x) - mu) / (2 * sigma * sigma)) / (x * sigma * sqrt(2 * PI))
    fun cdf(x: Double): Double = if (x <= 0) 0.0 else Statistics.normalCdf((ln(x) - mu) / sigma)
    /** Whether the fit is not rejected at 5 % (KS, parameters estimated from the data → conservative). */
    val acceptable: Boolean get() = n >= 10 && ksPValue > 0.05
}

object Statistics {

    /** Percentile with linear interpolation between order statistics (Hyndman–Fan type 7, as Excel/NumPy). */
    fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val h = (sorted.size - 1) * p / 100.0
        val lo = floor(h).toInt(); val hi = ceil(h).toInt()
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
    }

    fun describe(values: DoubleArray): Descriptive {
        val v = values.filter { it.isFinite() }.sorted().toDoubleArray()
        val n = v.size
        if (n == 0) return Descriptive(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val mean = v.average()
        val sd = if (n > 1) sqrt(v.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
        return Descriptive(n, mean, percentile(v, 50.0), sd, sd / sqrt(n.toDouble()), if (mean != 0.0) sd / mean else Double.NaN,
            v.first(), v.last(), percentile(v, 10.0), percentile(v, 25.0), percentile(v, 75.0), percentile(v, 90.0))
    }

    fun histogram(values: DoubleArray, bins: Int, start: Double? = null, end: Double? = null): HistogramBins {
        val v = values.filter { it.isFinite() }
        if (v.isEmpty()) return HistogramBins(0.0, 1.0, IntArray(max(1, bins)))
        val lo = start ?: v.min(); var hi = end ?: v.max()
        if (hi <= lo) hi = lo + 1.0
        val nb = max(1, bins)
        val wd = (hi - lo) / nb
        val c = IntArray(nb)
        for (x in v) { if (x < lo || x > hi) continue; c[((x - lo) / wd).toInt().coerceIn(0, nb - 1)]++ }
        return HistogramBins(lo, wd, c)
    }

    fun histogramByWidth(values: DoubleArray, width: Double, start: Double? = null): HistogramBins {
        val v = values.filter { it.isFinite() }
        if (v.isEmpty() || width <= 0) return HistogramBins(0.0, max(width, 1.0), IntArray(1))
        val lo = start ?: (floor(v.min() / width) * width)
        val nb = max(1, ceil((v.max() - lo) / width + 1e-9).toInt())
        val c = IntArray(nb)
        for (x in v) { if (x < lo) continue; c[((x - lo) / width).toInt().coerceIn(0, nb - 1)]++ }
        return HistogramBins(lo, width, c)
    }

    /** Freedman–Diaconis suggested number of bins. */
    fun suggestedBins(values: DoubleArray): Int {
        val d = describe(values)
        if (d.n < 2) return 1
        val iqr = d.p75 - d.p25
        if (iqr <= 0) return max(1, sqrt(d.n.toDouble()).toInt())
        val bw = 2 * iqr / Math.cbrt(d.n.toDouble())
        return ((d.max - d.min) / bw).toInt().coerceIn(3, 100)
    }

    /** Maximum-likelihood lognormal fit with a Kolmogorov–Smirnov goodness-of-fit test. Null if any value <= 0. */
    fun lognormal(values: DoubleArray): LognormalFit? {
        val v = values.filter { it.isFinite() }.sorted()
        if (v.size < 3 || v.first() <= 0) return null
        val logs = v.map { ln(it) }
        val mu = logs.average()
        val sigma = sqrt(logs.sumOf { (it - mu) * (it - mu) } / logs.size)
        if (sigma <= 0) return null
        val n = v.size
        var d = 0.0
        for ((i, x) in v.withIndex()) {
            val f = normalCdf((ln(x) - mu) / sigma)
            d = max(d, max(abs(f - i.toDouble() / n), abs((i + 1).toDouble() / n - f)))
        }
        return LognormalFit(mu, sigma, d, ksPValue(d, n), n)
    }

    /** Asymptotic Kolmogorov distribution with Stephens' small-sample correction. */
    fun ksPValue(d: Double, n: Int): Double {
        val en = sqrt(n.toDouble())
        val lambda = (en + 0.12 + 0.11 / en) * d
        var sum = 0.0; var sign = 1.0
        for (j in 1..100) {
            val term = 2 * sign * exp(-2 * j * j * lambda * lambda)
            sum += term; sign = -sign
            if (abs(term) < 1e-10) break
        }
        return sum.coerceIn(0.0, 1.0)
    }

    fun normalCdf(z: Double): Double = 0.5 * erfc(-z / sqrt(2.0))

    /** Complementary error function, Numerical Recipes erfcc (fractional error < 1.2e-7). */
    fun erfc(x: Double): Double {
        val z = abs(x); val t = 1.0 / (1.0 + 0.5 * z)
        val ans = t * exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 +
            t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))))
        return if (x >= 0) ans else 2.0 - ans
    }
}
