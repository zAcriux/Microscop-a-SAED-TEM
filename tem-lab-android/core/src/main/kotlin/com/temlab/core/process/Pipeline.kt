package com.temlab.core.process

import com.temlab.core.image.ImageData
import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * One reproducible processing step. A pipeline (list of ops) is always applied to the ORIGINAL data, so
 * editing is non-destructive; the list itself is stored in the project and in exported reports.
 */
@Serializable
sealed class ProcessOp {
    abstract val label: String
    abstract fun apply(img: ImageData): ImageData

    @Serializable data class Gaussian(val sigma: Double) : ProcessOp() { override val label get() = "Gaussian σ=$sigma px"; override fun apply(img: ImageData) = Filters.gaussian(img, sigma) }
    @Serializable data class Median(val radius: Int) : ProcessOp() { override val label get() = "Median r=$radius px"; override fun apply(img: ImageData) = Filters.median(img, radius) }
    @Serializable data class Bilateral(val sigmaSpatial: Double, val sigmaRange: Double) : ProcessOp() { override val label get() = "Bilateral σs=$sigmaSpatial σr=$sigmaRange"; override fun apply(img: ImageData) = Filters.bilateral(img, sigmaSpatial, sigmaRange) }
    @Serializable data object Laplacian : ProcessOp() { override val label get() = "Laplacian"; override fun apply(img: ImageData) = Filters.laplacian(img) }
    @Serializable data object Sobel : ProcessOp() { override val label get() = "Sobel gradient"; override fun apply(img: ImageData) = Filters.sobel(img) }
    @Serializable data class LogEdges(val sigma: Double) : ProcessOp() { override val label get() = "Edges (LoG σ=$sigma)"; override fun apply(img: ImageData) = Filters.logEdges(img, sigma) }
    @Serializable data class HighPass(val sigma: Double) : ProcessOp() { override val label get() = "High-pass σ=$sigma px"; override fun apply(img: ImageData) = Filters.highPass(img, sigma) }
    @Serializable data class LowPass(val sigma: Double) : ProcessOp() { override val label get() = "Low-pass σ=$sigma px"; override fun apply(img: ImageData) = Filters.lowPass(img, sigma) }
    @Serializable data class Unsharp(val sigma: Double, val amount: Double) : ProcessOp() { override val label get() = "Unsharp σ=$sigma ×$amount"; override fun apply(img: ImageData) = Filters.unsharpMask(img, sigma, amount) }
    @Serializable data class Wiener(val radius: Int) : ProcessOp() { override val label get() = "Noise reduction (Wiener r=$radius)"; override fun apply(img: ImageData) = Filters.wiener(img, radius) }
    @Serializable data object Normalize : ProcessOp() { override val label get() = "Normalize 0–1"; override fun apply(img: ImageData) = Adjust.normalize(img) }
    @Serializable data object Invert : ProcessOp() { override val label get() = "Invert"; override fun apply(img: ImageData) = Adjust.invert(img) }
    @Serializable data object Equalize : ProcessOp() { override val label get() = "Histogram equalisation"; override fun apply(img: ImageData) = Adjust.equalize(img) }
    @Serializable data class Clahe(val tiles: Int, val clip: Double) : ProcessOp() { override val label get() = "CLAHE ${tiles}×$tiles clip=$clip"; override fun apply(img: ImageData) = Adjust.clahe(img, tiles, clip) }
    @Serializable data class Background(val sigma: Double) : ProcessOp() { override val label get() = "Background subtraction σ=$sigma px"; override fun apply(img: ImageData) = Adjust.subtractGaussianBackground(img, sigma) }
    @Serializable data object PlaneFit : ProcessOp() { override val label get() = "Plane (gradient) correction"; override fun apply(img: ImageData) = Adjust.subtractPlane(img) }
    @Serializable data class Crop(val x: Int, val y: Int, val w: Int, val h: Int) : ProcessOp() { override val label get() = "Crop ($x,$y) ${w}×$h"; override fun apply(img: ImageData) = img.crop(x, y, w, h) }
    @Serializable data class Rotate90(val k: Int) : ProcessOp() { override val label get() = "Rotate ${k * 90}°"; override fun apply(img: ImageData) = Adjust.rotate90(img, k) }
    @Serializable data class Flip(val horizontal: Boolean) : ProcessOp() { override val label get() = if (horizontal) "Flip horizontal" else "Flip vertical"; override fun apply(img: ImageData) = Adjust.flip(img, horizontal) }
    @Serializable data class Rotate(val angle: Double) : ProcessOp() { override val label get() = "Rotate $angle°"; override fun apply(img: ImageData) = Adjust.rotate(img, angle) }
}

@Serializable
data class Pipeline(val ops: List<ProcessOp> = emptyList()) {
    fun apply(original: ImageData): ImageData {
        var img = original
        for (op in ops) img = op.apply(img).also { it.name = original.name }
        return img
    }
    fun describe(): String = if (ops.isEmpty()) "none" else ops.joinToString(" → ") { it.label }
}

/** Undo/redo history over pipeline states. */
class History(initial: Pipeline = Pipeline()) {
    private val undo = ArrayDeque<Pipeline>()
    private val redo = ArrayDeque<Pipeline>()
    var current: Pipeline = initial; private set
    fun push(p: Pipeline) { undo.addLast(current); redo.clear(); current = p }
    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()
    fun undo(): Pipeline { if (undo.isNotEmpty()) { redo.addLast(current); current = undo.removeLast() }; return current }
    fun redo(): Pipeline { if (redo.isNotEmpty()) { undo.addLast(current); current = redo.removeLast() }; return current }
}

/** Display-only mapping (window/level, gamma, inversion). Never modifies data used for measurements. */
@Serializable
data class DisplaySettings(
    val min: Double = Double.NaN,
    val max: Double = Double.NaN,
    val gamma: Double = 1.0,
    val invert: Boolean = false,
    val brightness: Double = 0.0, // -1..1 shift of the window
    val contrast: Double = 1.0,   // window scaling factor
) {
    /** Maps a raw value to 0..255 given the data range. */
    fun lut(dataMin: Float, dataMax: Float): (Float) -> Int {
        val lo0 = if (min.isNaN()) dataMin.toDouble() else min
        val hi0 = if (max.isNaN()) dataMax.toDouble() else max
        val c = (lo0 + hi0) / 2 - brightness * (hi0 - lo0)
        val half = (hi0 - lo0) / 2 / contrast.coerceAtLeast(1e-3)
        val lo = c - half; val range = (2 * half).coerceAtLeast(1e-30)
        return { v ->
            var t = ((v - lo) / range).coerceIn(0.0, 1.0)
            if (gamma != 1.0) t = t.pow(gamma)
            if (invert) t = 1 - t
            (t * 255 + 0.5).toInt()
        }
    }

    companion object {
        /** Auto contrast saturating [saturated]% of pixels at each end (as ImageJ "Auto"). */
        fun auto(img: ImageData, saturated: Double = 0.35): DisplaySettings {
            val (mn, mx) = img.minMax()
            if (mx <= mn) return DisplaySettings()
            val bins = 4096; val hist = IntArray(bins); val r = mx - mn
            val step = maxOf(1, img.pixels.size / 2_000_000)
            var n = 0
            for (i in img.pixels.indices step step) { hist[((img.pixels[i] - mn) / r * (bins - 1)).toInt().coerceIn(0, bins - 1)]++; n++ }
            val lim = (n * saturated / 100).toInt()
            var acc = 0; var lo = 0
            while (lo < bins - 1 && acc + hist[lo] <= lim) { acc += hist[lo]; lo++ }
            acc = 0; var hi = bins - 1
            while (hi > lo && acc + hist[hi] <= lim) { acc += hist[hi]; hi-- }
            return DisplaySettings(min = mn + lo * r / (bins - 1).toDouble(), max = mn + (hi + 1) * r / (bins - 1).toDouble())
        }
    }
}
