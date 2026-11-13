/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.temlab.app.render.Render
import com.temlab.core.image.ImageData
import com.temlab.core.measure.ManualMeasurement
import com.temlab.core.measure.MeasureKind
import com.temlab.core.particles.ParticleSet
import com.temlab.core.project.Annotation
import com.temlab.core.project.AnnotationType
import com.temlab.core.project.ScaleBarStyle
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK) }

/** Text drawn at a constant on-screen size inside an image-space transform. */
fun DrawScope.label(text: String, x: Float, y: Float, scale: Float, color: Color = Color.Yellow, sizePx: Float = 34f, center: Boolean = false) {
    textPaint.color = color.toArgb(); textPaint.textSize = sizePx / scale
    textPaint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
    textPaint.setShadowLayer(3f / scale, 0f, 0f, android.graphics.Color.BLACK)
    drawContext.canvas.nativeCanvas.drawText(text, x, y, textPaint)
}

fun DrawScope.poly(pts: List<Offset>, scale: Float, color: Color, closed: Boolean = false, width: Float = 3f, dashed: Boolean = false) {
    if (pts.size < 2) { pts.firstOrNull()?.let { drawCircle(color, 5f / scale, it) }; return }
    val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; if (closed) close() }
    drawPath(p, color, style = Stroke(width / scale, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f / scale, 7f / scale)) else null))
}

fun DrawScope.scaleBarOverlay(img: ImageData, style: ScaleBarStyle, scale: Float) {
    if (!style.visible) return
    val (_, px, lab) = Render.scaleBar(img, style) ?: return
    val w = img.width.toFloat(); val h = img.height.toFloat()
    val thick = max(2f, (style.thicknessFraction * h).toFloat())
    val margin = 0.03f * w
    val (bx, by) = when (style.position) {
        "BOTTOM_LEFT" -> margin to h - margin - thick
        "TOP_RIGHT" -> w - margin - px.toFloat() to margin + 0.06f * h
        "TOP_LEFT" -> margin to margin + 0.06f * h
        "CUSTOM" -> style.customX.toFloat() to style.customY.toFloat()
        else -> w - margin - px.toFloat() to h - margin - thick
    }
    val col = Color(style.color.toInt())
    if (style.background) drawRect(Color.Black.copy(alpha = 0.45f), Offset(bx - 0.01f * w, by - 0.06f * h), Size(px.toFloat() + 0.02f * w, thick + 0.075f * h))
    drawRect(col, Offset(bx, by), Size(px.toFloat(), thick))
    if (style.showText) {
        textPaint.color = col.toArgb(); textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = max(12f, 0.04f * h)
        drawContext.canvas.nativeCanvas.drawText(lab, bx + px.toFloat() / 2, by - 0.012f * h, textPaint)
    }
}

fun DrawScope.measurementOverlay(m: ManualMeasurement, scale: Float, color: Color = Color.Cyan) {
    val pts = m.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }
    poly(pts, scale, color, closed = m.kind == MeasureKind.AREA || m.kind == MeasureKind.PERIMETER)
    if (m.kind == MeasureKind.RADIUS && pts.size == 2) drawCircle(color, (pts[1] - pts[0]).getDistance(), pts[0], style = Stroke(2f / scale))
    val p = pts.last()
    label(String.format(Locale.US, "#%d %.4g %s", m.id, m.value, m.unit), p.x + 8 / scale, p.y - 8 / scale, scale, color)
}

fun DrawScope.particlesOverlay(set: ParticleSet, scale: Float, selected: Set<Int>, numbers: Boolean) {
    val cal = set.image.calibration
    for (p in set.particles) {
        if (p.contour.size < 2) continue
        val col = when { p.id in selected -> Color(0xFFFFD54F); p.included -> Color(0xFF4CE07A); else -> Color(0xFFE85050) }
        poly(p.contour.map { Offset(it.first.toFloat(), it.second.toFloat()) }, scale, col, closed = true, width = if (p.id in selected) 4f else 2f, dashed = !p.included)
        if (numbers) p.measurement?.let { m -> label(p.id.toString(), (m.centroidX / cal.pixelWidth).toFloat(), (m.centroidY / cal.pixelHeight).toFloat(), scale, Color.White, 26f, center = true) }
    }
}

fun DrawScope.annotationOverlay(a: Annotation, scale: Float) {
    if (!a.visible || a.points.isEmpty()) return
    val col = Color(a.color.toInt())
    val p0 = Offset(a.points[0].x.toFloat(), a.points[0].y.toFloat())
    val p1 = a.points.getOrNull(1)?.let { Offset(it.x.toFloat(), it.y.toFloat()) } ?: p0
    val st = Stroke(a.strokeWidth / scale, cap = StrokeCap.Round)
    when (a.type) {
        AnnotationType.LINE, AnnotationType.DSPACING, AnnotationType.ANGLE -> poly(a.points.map { Offset(it.x.toFloat(), it.y.toFloat()) }, scale, col, width = a.strokeWidth)
        AnnotationType.ARROW -> {
            drawLine(col, p0, p1, a.strokeWidth / scale, StrokeCap.Round)
            val ang = atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble()); val hl = 22f / scale
            for (s in listOf(-0.45, 0.45)) drawLine(col, p1, Offset((p1.x - hl * cos(ang + s)).toFloat(), (p1.y - hl * sin(ang + s)).toFloat()), a.strokeWidth / scale, StrokeCap.Round)
        }
        AnnotationType.CIRCLE -> drawCircle(col, (p1 - p0).getDistance(), p0, style = st)
        AnnotationType.RECTANGLE, AnnotationType.ROI -> drawRect(col, Offset(minOf(p0.x, p1.x), minOf(p0.y, p1.y)), Size(kotlin.math.abs(p1.x - p0.x), kotlin.math.abs(p1.y - p0.y)), style = st)
        AnnotationType.SQUARE -> { val s = max(kotlin.math.abs(p1.x - p0.x), kotlin.math.abs(p1.y - p0.y)); drawRect(col, Offset(if (p1.x < p0.x) p0.x - s else p0.x, if (p1.y < p0.y) p0.y - s else p0.y), Size(s, s), style = st) }
        else -> {}
    }
    if (a.text.isNotEmpty()) {
        textPaint.color = col.toArgb(); textPaint.textSize = a.fontSize; textPaint.textAlign = Paint.Align.LEFT
        val at = if (a.points.size > 1) p1 else p0
        drawContext.canvas.nativeCanvas.drawText(a.text, at.x + 4, at.y - 4, textPaint)
    }
}

/** Simple line chart (profiles, radial profiles). */
@Composable
fun LineChart(x: DoubleArray, y: DoubleArray, modifier: Modifier = Modifier.fillMaxWidth().height(150.dp), color: Color = Color(0xFF5EC8F2), markers: List<Double> = emptyList(), xLabel: String = "", logY: Boolean = false) {
    Canvas(modifier) {
        if (x.size < 2) return@Canvas
        val yy = if (logY) y.map { kotlin.math.ln(1 + max(0.0, it)) }.toDoubleArray() else y
        val x0 = x.first(); val x1 = x.last(); val y0 = yy.min(); val y1 = yy.max().let { if (it <= y0) y0 + 1 else it }
        val ml = 6f; val mb = 26f
        fun X(v: Double) = (ml + (v - x0) / (x1 - x0) * (size.width - ml - 4)).toFloat()
        fun Y(v: Double) = (size.height - mb - (v - y0) / (y1 - y0) * (size.height - mb - 6)).toFloat()
        drawRect(Color.Black.copy(alpha = 0.25f))
        val p = Path(); for (i in x.indices) if (i == 0) p.moveTo(X(x[i]), Y(yy[i])) else p.lineTo(X(x[i]), Y(yy[i]))
        drawPath(p, color, style = Stroke(2f))
        markers.forEach { m -> drawLine(Color(0xFFFFD54F), Offset(X(m), 0f), Offset(X(m), size.height - mb), 1.5f) }
        textPaint.color = android.graphics.Color.LTGRAY; textPaint.textSize = 24f; textPaint.textAlign = Paint.Align.LEFT; textPaint.setShadowLayer(0f, 0f, 0f, 0)
        drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "%.3g", x0), ml, size.height - 4, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "%.3g %s", x1, xLabel), size.width - 4, size.height - 4, textPaint)
    }
}

fun hypotF(a: Offset, b: Offset) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
