package com.temlab.core.fft

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Discrete Fourier transform, validated against a direct O(n²) DFT in the unit tests.
 * - power-of-two lengths: iterative radix-2 Cooley–Tukey
 * - any other length: Bluestein chirp-z algorithm (exact DFT, not an approximation)
 * Convention: forward X[k] = Σ x[n] e^{-2πi kn/N}; inverse includes the 1/N factor.
 */
object FFT {
    fun isPowerOfTwo(n: Int) = n > 0 && (n and (n - 1)) == 0
    fun nextPowerOfTwo(n: Int): Int { var p = 1; while (p < n) p = p shl 1; return p }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(im.size == n)
        if (n <= 1) return
        if (isPowerOfTwo(n)) radix2(re, im, inverse) else bluestein(re, im, inverse)
        if (inverse) { val s = 1.0 / n; for (i in 0 until n) { re[i] *= s; im[i] *= s } }
    }

    private val twiddleCache = HashMap<Int, Pair<DoubleArray, DoubleArray>>()

    @Synchronized
    private fun twiddles(n: Int): Pair<DoubleArray, DoubleArray> = twiddleCache.getOrPut(n) {
        val c = DoubleArray(n / 2) { cos(2 * PI * it / n) }
        val s = DoubleArray(n / 2) { sin(2 * PI * it / n) }
        c to s
    }

    private fun radix2(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        val (cs, sn) = twiddles(n)
        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until half) {
                    val wr = cs[k]; val wi = sign * sn[k]
                    val a = i + m; val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun bluestein(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        val m = nextPowerOfTwo(2 * n - 1)
        val sign = if (inverse) 1.0 else -1.0
        val wr = DoubleArray(n); val wi = DoubleArray(n)
        for (k in 0 until n) {
            val a = PI * ((k.toLong() * k) % (2L * n)) / n
            wr[k] = cos(a); wi[k] = -sign * sin(a)
        }
        val ar = DoubleArray(m); val ai = DoubleArray(m)
        for (k in 0 until n) { // a = x * conj(w)... using w = e^{sign i pi k^2/n}
            ar[k] = re[k] * wr[k] + im[k] * wi[k]
            ai[k] = im[k] * wr[k] - re[k] * wi[k]
        }
        val br = DoubleArray(m); val bi = DoubleArray(m)
        br[0] = wr[0]; bi[0] = wi[0]
        for (k in 1 until n) { br[k] = wr[k]; bi[k] = wi[k]; br[m - k] = wr[k]; bi[m - k] = wi[k] }
        radix2(ar, ai, false); radix2(br, bi, false)
        for (k in 0 until m) {
            val r = ar[k] * br[k] - ai[k] * bi[k]
            val i = ar[k] * bi[k] + ai[k] * br[k]
            ar[k] = r; ai[k] = i
        }
        radix2(ar, ai, true)
        for (k in 0 until m) { ar[k] /= m; ai[k] /= m }
        for (k in 0 until n) {
            re[k] = ar[k] * wr[k] + ai[k] * wi[k]
            im[k] = ai[k] * wr[k] - ar[k] * wi[k]
        }
    }

    /** In-place 2-D transform of a row-major w×h complex array. */
    fun transform2D(re: DoubleArray, im: DoubleArray, w: Int, h: Int, inverse: Boolean) {
        val rr = DoubleArray(w); val ri = DoubleArray(w)
        for (y in 0 until h) {
            System.arraycopy(re, y * w, rr, 0, w); System.arraycopy(im, y * w, ri, 0, w)
            transform(rr, ri, inverse)
            System.arraycopy(rr, 0, re, y * w, w); System.arraycopy(ri, 0, im, y * w, w)
        }
        val cr = DoubleArray(h); val ci = DoubleArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) { cr[y] = re[y * w + x]; ci[y] = im[y * w + x] }
            transform(cr, ci, inverse)
            for (y in 0 until h) { re[y * w + x] = cr[y]; im[y * w + x] = ci[y] }
        }
    }

    /** Moves the zero-frequency term to index (w/2, h/2). For even sizes this is its own inverse. */
    fun shift(a: DoubleArray, w: Int, h: Int, inverse: Boolean = false): DoubleArray {
        val out = DoubleArray(a.size)
        val sx = if (inverse) -(w / 2) else w / 2
        val sy = if (inverse) -(h / 2) else h / 2
        for (y in 0 until h) {
            val ny = Math.floorMod(y + sy, h)
            for (x in 0 until w) out[ny * w + Math.floorMod(x + sx, w)] = a[y * w + x]
        }
        return out
    }

    /** Reference O(n²) DFT used by the validation tests. */
    fun naiveDft(re: DoubleArray, im: DoubleArray, inverse: Boolean): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val or = DoubleArray(n); val oi = DoubleArray(n)
        val sign = if (inverse) 1.0 else -1.0
        for (k in 0 until n) {
            var sr = 0.0; var si = 0.0
            for (t in 0 until n) {
                val a = sign * 2 * PI * ((k.toLong() * t) % n) / n
                sr += re[t] * cos(a) - im[t] * sin(a)
                si += re[t] * sin(a) + im[t] * cos(a)
            }
            or[k] = if (inverse) sr / n else sr; oi[k] = if (inverse) si / n else si
        }
        return or to oi
    }
}
