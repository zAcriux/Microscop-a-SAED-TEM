package com.temlab.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.temlab.core.image.ImageData
import com.temlab.core.process.DisplaySettings
import com.temlab.core.project.ScaleBarStyle
import com.temlab.core.project.niceScaleLength
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A rendered view of (part of) an image: [bitmap] covers image rect (x, y, w, h) in original pixel coordinates. */
class RenderedTile(val bitmap: ImageBitmap, val x: Int, val y: Int, val w: Int, val h: Int)

object Render {
    /** Downsampled full-image preview (block averaging), longest side <= maxDim. */
    fun preview(img: ImageData, ds: DisplaySettings, maxDim: Int = 2048): RenderedTile = region(img, ds, 0, 0, img.width, img.height, maxDim)

    /** Renders an image region at full resolution or downsampled so that the longest side <= maxDim. */
    fun region(img: ImageData, ds: DisplaySettings, x0: Int, y0: Int, w0: Int, h0: Int, maxDim: Int): RenderedTile {
        val x = x0.coerceIn(0, img.width - 1); val y = y0.coerceIn(0, img.height - 1)
        val w = min(w0, img.width - x).coerceAtLeast(1); val h = min(h0, img.height - y).coerceAtLeast(1)
        val f = max(1, ceil(max(w, h) / maxDim.toDouble()).toInt())
        val bw = max(1, w / f); val bh = max(1, h / f)
        val (mn, mx) = img.minMax()
        val lut = ds.lut(mn, mx)
        val px = IntArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var s = 0f; var n = 0
            val sy = y + by * f; val sx = x + bx * f
            for (yy in sy until min(sy + f, img.height)) for (xx in sx until min(sx + f, img.width)) { s += img.pixels[yy * img.width + xx]; n++ }
            val g = lut(if (n > 0) s / n else 0f)
            px[by * bw + bx] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val bmp = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
        return RenderedTile(bmp.asImageBitmap(), x, y, w, h)
    }

    /** Grey-level bitmap of a float array (auto min/max, or [lo],[hi]). */
    fun floatBitmap(data: FloatArray, w: Int, h: Int, lo: Float? = null, hi: Float? = null, gamma: Double = 1.0, colormap: Boolean = false): Bitmap {
        var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY
        for (v in data) { if (v < mn) mn = v; if (v > mx) mx = v }
        val a = lo ?: mn; val b = hi ?: mx
        val r = if (b > a) b - a else 1f
        val px = IntArray(w * h) {
            var t = ((data[it] - a) / r).coerceIn(0f, 1f)
            if (gamma != 1.0) t = Math.pow(t.toDouble(), gamma).toFloat()
            if (colormap) inferno(t) else { val g = (t * 255).toInt(); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Perceptual colour map for FFT display (approximation of 'inferno'). */
    fun inferno(t: Float): Int {
        val stops = floatArrayOf(0f, .25f, .5f, .75f, 1f)
        val cols = arrayOf(intArrayOf(0, 0, 4), intArrayOf(87, 16, 110), intArrayOf(188, 55, 84), intArrayOf(249, 142, 9), intArrayOf(252, 255, 164))
        var i = 0; while (i < 3 && t > stops[i + 1]) i++
        val f = ((t - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
        val c = IntArray(3) { (cols[i][it] + (cols[i + 1][it] - cols[i][it]) * f).toInt() }
        return (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    /** Full-resolution rendering of an image with the display LUT (for figure export). */
    fun fullBitmap(img: ImageData, ds: DisplaySettings): Bitmap {
        val (mn, mx) = img.minMax(); val lut = ds.lut(mn, mx)
        val px = IntArray(img.width * img.height) { val g = lut(img.pixels[it]); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        return Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
    }

    /** Computes the scale bar (length in physical units, length in pixels, label). Never alters the calibration. */
    fun scaleBar(img: ImageData, style: ScaleBarStyle): Triple<Double, Double, String>? {
        val cal = img.calibration
        if (!cal.isCalibrated) return null
        val widthPhys = img.width * cal.pixelWidth
        val len = if (style.lengthPhysical.isNaN() || style.lengthPhysical <= 0) niceScaleLength(widthPhys) else style.lengthPhysical
        val px = len / cal.pixelWidth
        val label = style.text.ifBlank { formatLength(len, cal.unitLabel) }
        return Triple(len, px, label)
    }

    fun formatLength(v: Double, unit: String): String =
        if (v >= 100 || v == Math.rint(v)) String.format(Locale.US, "%.0f %s", v, unit) else String.format(Locale.US, "%.3g %s", v, unit)

    /** Draws the scale bar onto a full-resolution export canvas. */
    fun drawScaleBar(c: Canvas, img: ImageData, style: ScaleBarStyle) {
        if (!style.visible) return
        val (_, px, label) = scaleBar(img, style) ?: return
        val h = img.height.toFloat(); val w = img.width.toFloat()
        val thick = max(2f, (style.thicknessFraction * h).toFloat())
        val margin = 0.03f * w
        val textSize = max(12f, 0.04f * h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.color.toInt(); this.textSize = textSize; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val (bx, by) = when (style.position) {
            "BOTTOM_LEFT" -> margin to h - margin - thick
            "TOP_RIGHT" -> w - margin - px.toFloat() to margin + textSize * 1.4f
            "TOP_LEFT" -> margin to margin + textSize * 1.4f
            "CUSTOM" -> style.customX.toFloat() to style.customY.toFloat()
            else -> w - margin - px.toFloat() to h - margin - thick
        }
        if (style.background) {
            val bg = Paint().apply { color = if ((style.color and 0xFFFFFF) > 0x808080) 0x99000000.toInt() else 0x99FFFFFF.toInt() }
            c.drawRect(RectF(bx - 0.3f * textSize, by - textSize * 1.6f, bx + px.toFloat() + 0.3f * textSize, by + thick + 0.3f * textSize), bg)
        }
        c.drawRect(RectF(bx, by, bx + px.toFloat(), by + thick), paint)
        if (style.showText) c.drawText(label, bx + px.toFloat() / 2, by - 0.35f * textSize, paint)
    }
}
