package com.temlab.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.temlab.app.data.Doc
import com.temlab.app.data.FftSession
import com.temlab.core.export.SvgHistogram
import com.temlab.core.fft.FftAnalysis
import com.temlab.core.fft.SpectrumScale
import com.temlab.core.image.LengthUnit
import com.temlab.core.measure.MeasureKind
import com.temlab.core.particles.ParticleSet
import com.temlab.core.project.Annotation
import com.temlab.core.project.AnnotationType
import com.temlab.core.stats.HistogramBins
import com.temlab.core.stats.LognormalFit
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

enum class ParticleOverlay { NONE, CONTOURS, NUMBERED }

/** Full-resolution figure rendering for export (publication quality: no resampling of the micrograph). */
object Figures {

    fun annotatedImage(d: Doc, annotations: Boolean = true, particles: ParticleOverlay = ParticleOverlay.NONE, measurements: Boolean = true, scaleBar: Boolean = true): Bitmap {
        val img = d.processed
        val bmp = Render.fullBitmap(img, d.display).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val k = max(1f, max(img.width, img.height) / 1000f)
        if (particles != ParticleOverlay.NONE) d.particles?.let { drawParticles(c, it, k, particles == ParticleOverlay.NUMBERED) }
        if (measurements) for (m in d.measurements) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 2f * k; style = Paint.Style.STROKE }
            val path = Path(); m.points.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat()) }
            if (m.kind == MeasureKind.AREA || m.kind == MeasureKind.PERIMETER) path.close()
            c.drawPath(path, p)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; textSize = 22f * k; setShadowLayer(3f, 0f, 0f, Color.BLACK) }
            val last = m.points.last()
            c.drawText(String.format(Locale.US, "%.4g %s", m.value, m.unit), last.x.toFloat() + 6 * k, last.y.toFloat() - 6 * k, t)
        }
        if (annotations) for (a in d.annotations) if (a.visible) drawAnnotation(c, a, 1f)
        if (scaleBar) Render.drawScaleBar(c, img, d.scaleBar)
        return bmp
    }

    fun drawParticles(c: Canvas, set: ParticleSet, k: Float, numbered: Boolean) {
        val inc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 220, 120); strokeWidth = 1.6f * k; style = Paint.Style.STROKE }
        val exc = Paint(inc).apply { color = Color.rgb(230, 80, 80); pathEffect = DashPathEffect(floatArrayOf(4 * k, 3 * k), 0f) }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = 14f * k; textAlign = Paint.Align.CENTER; setShadowLayer(2f, 0f, 0f, Color.BLACK) }
        for (p in set.particles) {
            if (p.contour.size < 2) continue
            val path = Path(); p.contour.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat()) }
            path.close()
            c.drawPath(path, if (p.included) inc else exc)
            if (numbered) p.measurement?.let { m ->
                val cal = set.image.calibration
                c.drawText(p.id.toString(), (m.centroidX / cal.pixelWidth).toFloat(), (m.centroidY / cal.pixelHeight).toFloat() + txt.textSize / 3, txt)
            }
        }
    }

    fun drawAnnotation(c: Canvas, a: Annotation, k: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = a.color.toInt(); strokeWidth = a.strokeWidth * k; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = a.color.toInt(); textSize = a.fontSize * k; setShadowLayer(3f, 0f, 0f, Color.BLACK) }
        val pts = a.points
        if (pts.isEmpty()) return
        val x0 = pts[0].x.toFloat(); val y0 = pts[0].y.toFloat()
        val x1 = pts.getOrNull(1)?.x?.toFloat() ?: x0; val y1 = pts.getOrNull(1)?.y?.toFloat() ?: y0
        when (a.type) {
            AnnotationType.LINE, AnnotationType.DSPACING, AnnotationType.ANGLE -> { c.drawLine(x0, y0, x1, y1, p); if (pts.size > 2) c.drawLine(x1, y1, pts[2].x.toFloat(), pts[2].y.toFloat(), p) }
            AnnotationType.ARROW -> {
                c.drawLine(x0, y0, x1, y1, p)
                val ang = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()); val hl = max(10f, a.strokeWidth * 5) * k
                for (s in listOf(-0.45, 0.45)) c.drawLine(x1, y1, (x1 - hl * cos(ang + s)).toFloat(), (y1 - hl * sin(ang + s)).toFloat(), p)
            }
            AnnotationType.CIRCLE -> c.drawCircle(x0, y0, hypot(x1 - x0, y1 - y0), p)
            AnnotationType.RECTANGLE, AnnotationType.ROI -> c.drawRect(RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1)), p)
            AnnotationType.SQUARE -> { val s = max(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)); c.drawRect(RectF(x0, y0, x0 + s * kotlin.math.sign(x1 - x0 + 1e-3f), y0 + s * kotlin.math.sign(y1 - y0 + 1e-3f)), p) }
            else -> {}
        }
        if (a.text.isNotEmpty()) c.drawText(a.text, if (pts.size > 1) x1 + 4 * k else x0, if (pts.size > 1) y1 - 4 * k else y0, t)
    }

    /** FFT figure with reflections, origin and d-spacing labels. */
    fun fftFigure(s: FftSession, log: Boolean, scale: SpectrumScale, gamma: Double, unit: LengthUnit, saturation: Float = 0.999f): Bitmap {
        val disp = s.result.display(scale, log)
        val hi = percentile(disp, saturation)
        val bmp = Render.floatBitmap(disp, s.result.n, s.result.n, hi = hi, gamma = gamma).copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val k = max(1f, s.result.n / 512f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 79); strokeWidth = 1.5f * k; style = Paint.Style.STROKE }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 213, 79); textSize = 13f * k; setShadowLayer(2f, 0f, 0f, Color.BLACK) }
        val cc = s.result.center.toFloat()
        c.drawCircle(cc, cc, 4f * k, Paint(p).apply { color = Color.CYAN })
        for (r in s.reflections) {
            c.drawCircle(r.u.toFloat(), r.v.toFloat(), 6f * k, p)
            c.drawText("#${r.id} " + dLabel(r.d, r.unit, unit), r.u.toFloat() + 8 * k, r.v.toFloat() - 6 * k, t)
        }
        return bmp
    }

    fun dLabel(d: Double, from: String, unit: LengthUnit): String =
        if (from == "px") String.format(Locale.US, "%.3f px", d)
        else String.format(Locale.US, "%.4f %s", FftAnalysis.convert(d, from, unit), unit.symbol)

    fun percentile(a: FloatArray, q: Float): Float {
        val step = max(1, a.size / 200_000)
        val s = FloatArray((a.size + step - 1) / step) { a[it * step] }; s.sort()
        return s[((s.size - 1) * q).toInt()]
    }

    /** Raster histogram (PNG export / PDF report). */
    fun histogram(h: HistogramBins, title: String, xLabel: String, relative: Boolean, fit: LognormalFit?, mean: Double?, median: Double?, n: Int,
                  w: Int = 1600, hh: Int = 1040, overlays: List<Pair<HistogramBins, Int>> = emptyList()): Bitmap {
        val bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        val k = w / 800f
        val ml = 80 * k; val mr = 30 * k; val mt = 50 * k; val mb = 70 * k
        val pw = w - ml - mr; val ph = hh - mt - mb
        val vals = if (relative) h.relative() else h.counts.map { it.toDouble() }.toDoubleArray()
        val x0 = (listOf(h.start) + overlays.map { it.first.start }).min()
        val x1 = (listOf(h.start + h.width * h.counts.size) + overlays.map { it.first.start + it.first.width * it.first.counts.size }).max()
        var ymax = (vals.maxOrNull() ?: 1.0)
        overlays.forEach { (o, _) -> ymax = max(ymax, (if (relative) o.relative() else o.counts.map { it.toDouble() }.toDoubleArray()).maxOrNull() ?: 0.0) }
        val fitScale = if (relative) h.width else h.width * n
        if (fit != null) { var x = x0; while (x <= x1) { ymax = max(ymax, fit.pdf(x) * fitScale); x += (x1 - x0) / 200 } }
        ymax = SvgHistogram.niceCeil(ymax * 1.05)
        fun X(v: Double) = (ml + (v - x0) / (x1 - x0) * pw).toFloat()
        fun Y(v: Double) = (mt + ph - v / ymax * ph).toFloat()
        val bar = Paint().apply { color = Color.rgb(76, 120, 168) }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(31, 59, 87); style = Paint.Style.STROKE; strokeWidth = k }
        for (i in vals.indices) {
            val r = RectF(X(h.start + i * h.width), Y(vals[i]), X(h.start + (i + 1) * h.width), Y(0.0))
            c.drawRect(r, bar); c.drawRect(r, edge)
        }
        overlays.forEach { (o, color) ->
            val ov = if (relative) o.relative() else o.counts.map { it.toDouble() }.toDoubleArray()
            val op = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 2.5f * k }
            val path = Path(); path.moveTo(X(o.start), Y(0.0))
            for (i in ov.indices) { path.lineTo(X(o.start + i * o.width), Y(ov[i])); path.lineTo(X(o.start + (i + 1) * o.width), Y(ov[i])) }
            path.lineTo(X(o.start + ov.size * o.width), Y(0.0)); c.drawPath(path, op)
        }
        if (fit != null) {
            val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(228, 87, 86); style = Paint.Style.STROKE; strokeWidth = 2.5f * k }
            val path = Path()
            for (i in 0..300) { val x = x0 + (x1 - x0) * i / 300; val y = Y(fit.pdf(x) * fitScale); if (i == 0) path.moveTo(X(x), y) else path.lineTo(X(x), y) }
            c.drawPath(path, fp)
        }
        val ax = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 1.2f * k; textSize = 13 * k; textAlign = Paint.Align.CENTER }
        fun vline(v: Double?, col: Int) { if (v != null && v in x0..x1) c.drawLine(X(v), Y(0.0), X(v), Y(ymax), Paint(ax).apply { color = col; pathEffect = DashPathEffect(floatArrayOf(8 * k, 5 * k), 0f) }) }
        vline(mean, Color.DKGRAY); vline(median, Color.rgb(84, 162, 75))
        c.drawLine(ml, Y(0.0), ml + pw, Y(0.0), ax); c.drawLine(ml, mt, ml, Y(0.0), ax)
        for (tk in SvgHistogram.ticks(x0, x1, 6)) { c.drawLine(X(tk), Y(0.0), X(tk), Y(0.0) + 5 * k, ax); c.drawText(SvgHistogram.fmt(tk), X(tk), Y(0.0) + 22 * k, ax) }
        val axR = Paint(ax).apply { textAlign = Paint.Align.RIGHT }
        for (tk in SvgHistogram.ticks(0.0, ymax, 5)) { c.drawLine(ml - 5 * k, Y(tk), ml, Y(tk), ax); c.drawText(SvgHistogram.fmt(tk), ml - 8 * k, Y(tk) + 4 * k, axR) }
        c.drawText(xLabel, ml + pw / 2, hh - 20 * k, Paint(ax).apply { textSize = 15 * k })
        c.save(); c.rotate(-90f, 22 * k, mt + ph / 2); c.drawText(if (relative) "Relative frequency" else "Count", 22 * k, mt + ph / 2, Paint(ax).apply { textSize = 15 * k }); c.restore()
        c.drawText(title, w / 2f, 30 * k, Paint(ax).apply { textSize = 18 * k; isFakeBoldText = true })
        val legend = buildString {
            append("N = $n")
            if (mean != null) append(String.format(Locale.US, "   mean = %.3g", mean))
            if (median != null) append(String.format(Locale.US, "   median = %.3g", median))
            if (fit != null) append(String.format(Locale.US, "   lognormal μ=%.3f σ=%.3f", fit.mu, fit.sigma))
        }
        c.drawText(legend, w - mr, mt + 4 * k, Paint(axR).apply { textSize = 12 * k })
        return bmp
    }
}
