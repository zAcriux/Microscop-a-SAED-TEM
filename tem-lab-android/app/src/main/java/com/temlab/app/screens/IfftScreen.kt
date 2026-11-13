package com.temlab.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.temlab.app.data.AppViewModel
import com.temlab.app.data.Doc
import com.temlab.app.data.IfftSession
import com.temlab.app.render.Figures
import com.temlab.app.render.Render
import com.temlab.app.render.RenderedTile
import com.temlab.app.ui.AdaptivePane
import com.temlab.app.ui.ChipRow
import com.temlab.app.ui.KeyValue
import com.temlab.app.ui.LabeledSlider
import com.temlab.app.ui.LineChart
import com.temlab.app.ui.LocalTr
import com.temlab.app.ui.NumberField
import com.temlab.app.ui.Section
import com.temlab.app.ui.SwitchRow
import com.temlab.app.ui.ToolHandler
import com.temlab.app.ui.ViewTransform
import com.temlab.app.ui.ZoomableImage
import com.temlab.app.ui.label
import com.temlab.app.ui.rememberFileSaver
import com.temlab.core.fft.FftAnalysis
import com.temlab.core.fft.FftOptions
import com.temlab.core.fft.FourierFilter
import com.temlab.core.fft.MaskShape
import com.temlab.core.fft.SpectrumScale
import com.temlab.core.fft.SquareRoi
import com.temlab.core.io.TiffWriter
import com.temlab.core.measure.Measure
import com.temlab.core.measure.PeriodicityResult
import com.temlab.core.measure.Profile
import com.temlab.core.measure.Profiles
import com.temlab.core.measure.Pt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.hypot

enum class MaskTool(val en: String, val es: String) { CIRCLE("Circle", "Círculo"), ELLIPSE("Ellipse", "Elipse"), RECT("Rectangle", "Rectángulo"), GAUSS("Gaussian", "Gaussiana"), MOVE("Move", "Mover"), DELETE("Delete", "Eliminar") }

private fun MaskShape.center(): Offset? = when (this) {
    is MaskShape.Circle -> Offset(dx.toFloat(), dy.toFloat()); is MaskShape.Ellipse -> Offset(dx.toFloat(), dy.toFloat())
    is MaskShape.Rect -> Offset(dx.toFloat(), dy.toFloat()); is MaskShape.Gaussian -> Offset(dx.toFloat(), dy.toFloat()); else -> null
}
private fun MaskShape.moved(x: Double, y: Double): MaskShape = when (this) {
    is MaskShape.Circle -> copy(dx = x, dy = y); is MaskShape.Ellipse -> copy(dx = x, dy = y); is MaskShape.Rect -> copy(dx = x, dy = y); is MaskShape.Gaussian -> copy(dx = x, dy = y); else -> this
}

@Composable
fun IfftScreen(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    val img = d.processed
    if (d.roi == null) d.roi = SquareRoi((img.width - minOf(512, img.width, img.height)) / 2, (img.height - minOf(512, img.width, img.height)) / 2, minOf(512, img.width, img.height))
    val roi = d.roi!!.clampedTo(img.width, img.height)
    var tool by remember { mutableStateOf(MaskTool.CIRCLE) }
    var radius by remember { mutableStateOf(6f) }
    var ry by remember { mutableStateOf(3f) }
    var angle by remember { mutableStateOf(0f) }
    var soft by remember { mutableStateOf(2f) }
    var auto by remember { mutableStateOf(true) }
    var sync by remember { mutableStateOf(true) }
    var editRoi by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<IfftSession?>(null) }
    var liveRes by remember { mutableStateOf<com.temlab.core.fft.FilterResult?>(null) }
    var dragIdx by remember { mutableStateOf(-1) }
    var profileLine by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var period by remember { mutableStateOf<PeriodicityResult?>(null) }
    var lowR by remember { mutableStateOf(0.0) }; var highR by remember { mutableStateOf(0.0) }
    val saver = rememberFileSaver { vm.notify(it) }
    val tA = remember(d.id) { ViewTransform() }; val tB = remember(d.id) { ViewTransform() }
    val tC = remember(d.id) { ViewTransform() }; val tD = remember(d.id) { ViewTransform() }
    val tMain = remember(d.id) { ViewTransform() }

    // Unwindowed, unpadded spectrum of the ROI (the filter operates on exactly these coefficients)
    val spec = remember(img, roi) { FftAnalysis.compute(img, roi, FftOptions(hannWindow = false, paddedSize = 0, subtractMean = true)) }
    val n = spec.n; val c = spec.center
    val specTile = remember(spec) { val disp = spec.display(SpectrumScale.AMPLITUDE, true); RenderedTile(Render.floatBitmap(disp, n, n, hi = Figures.percentile(disp, 0.999f)).asImageBitmap(), 0, 0, n, n) }
    val roiImg = remember(img, roi) { img.crop(roi.x, roi.y, roi.size, roi.size) }
    val roiTile = remember(roiImg, d.display) { Render.preview(roiImg, d.display) }

    LaunchedEffect(d.mask, roi, auto) {
        if (!auto || d.mask.shapes.isEmpty()) return@LaunchedEffect
        delay(250)
        liveRes = withContext(Dispatchers.Default) { FourierFilter.apply(img, roi, d.mask) }
    }
    val res = liveRes
    val recTile = remember(res) { res?.let { RenderedTile(Render.floatBitmap(it.reconstruction.pixels, it.n, it.n, lo = Figures.percentile(it.reconstruction.pixels, 0.002f), hi = Figures.percentile(it.reconstruction.pixels, 0.998f)).asImageBitmap(), 0, 0, it.n, it.n) } }
    val maskTile = remember(res) { res?.let { RenderedTile(Render.floatBitmap(it.mask, it.n, it.n, 0f, 1f).asImageBitmap(), 0, 0, it.n, it.n) } }

    fun addShape(p: Offset) {
        val pk = FftAnalysis.locatePeak(spec, p.x.toDouble(), p.y.toDouble(), radius = 6)
        val x = (pk?.u ?: p.x.toDouble()) - c; val y = (pk?.v ?: p.y.toDouble()) - c
        val sh = when (tool) {
            MaskTool.CIRCLE -> MaskShape.Circle(x, y, radius.toDouble(), soft.toDouble())
            MaskTool.ELLIPSE -> MaskShape.Ellipse(x, y, radius.toDouble(), ry.toDouble(), angle.toDouble(), soft.toDouble())
            MaskTool.RECT -> MaskShape.Rect(x, y, radius.toDouble(), ry.toDouble(), soft.toDouble())
            MaskTool.GAUSS -> MaskShape.Gaussian(x, y, radius.toDouble())
            else -> return
        }
        d.mask = d.mask.copy(shapes = d.mask.shapes + sh)
    }
    fun nearestShape(p: Offset) = d.mask.shapes.withIndex().filter { it.value.center() != null }.minByOrNull { (it.value.center()!! + Offset(c.toFloat(), c.toFloat()) - p).getDistance() }
        ?.takeIf { (it.value.center()!! + Offset(c.toFloat(), c.toFloat()) - p).getDistance() < 20f }?.index

    val fftTool = ToolHandler(wantsDrag = true, magnifier = true,
        onTap = { p -> when (tool) { MaskTool.DELETE -> nearestShape(p)?.let { i -> d.mask = d.mask.copy(shapes = d.mask.shapes.filterIndexed { j, _ -> j != i }) }; MaskTool.MOVE -> {}; else -> addShape(p) } },
        onDragStart = { p -> dragIdx = if (tool == MaskTool.MOVE) nearestShape(p) ?: -1 else -1 },
        onDrag = { p -> if (dragIdx >= 0) d.mask = d.mask.copy(shapes = d.mask.shapes.mapIndexed { j, s -> if (j == dragIdx) s.moved(p.x.toDouble() - c, p.y.toDouble() - c) else s }) },
        onDragEnd = { dragIdx = -1 })
    val recTool = ToolHandler(wantsDrag = true, magnifier = true,
        onDragStart = { p -> profileLine = p to p }, onDrag = { p -> profileLine = profileLine?.first?.let { it to p } },
        onDragEnd = {
            val r = res; val l = profileLine
            if (r != null && l != null) {
                val a = Pt(l.first.x.toDouble(), l.first.y.toDouble()); val b = Pt(l.second.x.toDouble(), l.second.y.toDouble())
                val pr = Profiles.line(r.reconstruction, a, b, 3); profile = pr
                period = if (pr.values.size > 16) Profiles.periodicity(pr, Measure.orientation(r.reconstruction.calibration, a, b)) else null
            }
        })

    @Composable
    fun pane(title: String, tile: RenderedTile?, t: ViewTransform, mod: Modifier, tool: ToolHandler = ToolHandler(), overlay: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit = {}) {
        Box(mod.padding(1.dp)) {
            ZoomableImage(tile, n, n, Modifier.fillMaxSize(), t, tool, onTransformChanged = { if (sync) listOf(tA, tB, tC, tD).forEach { if (it !== t) it.copyFrom(t) } }, overlay = overlay)
            Text(title, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        }
    }

    AdaptivePane(image = { mod ->
        if (editRoi) DocImage(d, mod, tMain, roiTool(d, img.width, img.height), showScaleBar = false) { s -> roiOverlay(d.roi, s) }
        else Column(mod) {
            Row(Modifier.weight(1f)) {
                pane(tr("Original region", "Región original"), roiTile, tA, Modifier.weight(1f).fillMaxSize())
                pane(tr("FFT + mask (tap reflections)", "FFT + máscara (toque reflexiones)"), specTile, tB, Modifier.weight(1f).fillMaxSize(), fftTool) { s -> maskShapesOverlay(d.mask, c, s, n) }
            }
            Row(Modifier.weight(1f)) {
                pane(tr("Mask", "Máscara"), maskTile, tC, Modifier.weight(1f).fillMaxSize())
                pane(tr("IFFT – FILTERED", "IFFT – FILTRADA"), recTile, tD, Modifier.weight(1f).fillMaxSize(), recTool) { s -> profileLine?.let { (a, b) -> drawLine(Color.Green, a, b, 2f / s) } }
            }
        }
    }) {
        Section(tr("Region", "Región")) {
            Text("x=${roi.x} y=${roi.y} ${roi.size}×${roi.size} px", style = MaterialTheme.typography.bodySmall)
            SwitchRow(tr("Edit region on the micrograph", "Editar región en la micrografía"), editRoi) { editRoi = it }
            ChipRow(listOf(128, 256, 512, 1024).filter { it <= minOf(img.width, img.height) }, roi.size, { "$it" }) { d.roi = roi.copy(size = it).clampedTo(img.width, img.height) }
            SwitchRow(tr("Synchronised zoom", "Zoom sincronizado"), sync) { sync = it }
        }
        Section(tr("Fourier mask", "Máscara de Fourier")) {
            ChipRow(MaskTool.entries, tool, { tr(it.en, it.es) }) { tool = it }
            LabeledSlider(tr("Radius / half-width (FFT px)", "Radio / semiancho (px FFT)"), radius, 1f..60f, { radius = it }, format = { String.format(Locale.US, "%.1f", it) })
            if (tool == MaskTool.ELLIPSE || tool == MaskTool.RECT) LabeledSlider(tr("Second semi-axis", "Segundo semieje"), ry, 1f..60f, { ry = it }, format = { String.format(Locale.US, "%.1f", it) })
            if (tool == MaskTool.ELLIPSE) LabeledSlider(tr("Angle (°)", "Ángulo (°)"), angle, -90f..90f, { angle = it }, format = { "${it.toInt()}°" })
            LabeledSlider(tr("Edge softness (Gaussian, px)", "Suavidad de borde (gaussiana, px)"), soft, 0f..10f, { soft = it }, format = { String.format(Locale.US, "%.1f", it) })
            SwitchRow(tr("Add conjugate (−g) masks automatically (Hermitian symmetry)", "Añadir máscaras conjugadas (−g) automáticamente (simetría hermítica)"), d.mask.symmetric) { d.mask = d.mask.copy(symmetric = it) }
            SwitchRow(tr("Invert mask", "Invertir máscara"), d.mask.invert) { d.mask = d.mask.copy(invert = it) }
            SwitchRow(tr("Keep mean intensity (DC)", "Conservar intensidad media (DC)"), d.mask.keepDc) { d.mask = d.mask.copy(keepDc = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(tr("Inner r", "r interno"), lowR, Modifier.weight(1f)) { lowR = it }
                NumberField(tr("Outer r", "r externo"), highR, Modifier.weight(1f)) { highR = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { if (highR > 0) d.mask = d.mask.copy(shapes = d.mask.shapes + MaskShape.LowPass(highR, soft.toDouble())) }) { Text(tr("Low-pass", "Pasa bajas"), fontSize = 11.sp) }
                OutlinedButton(onClick = { if (lowR > 0) d.mask = d.mask.copy(shapes = d.mask.shapes + MaskShape.HighPass(lowR, soft.toDouble())) }) { Text(tr("High-pass", "Pasa altas"), fontSize = 11.sp) }
                OutlinedButton(onClick = { if (highR > lowR) d.mask = d.mask.copy(shapes = d.mask.shapes + MaskShape.BandPass(lowR, highR, soft.toDouble())) }) { Text(tr("Band", "Banda"), fontSize = 11.sp) }
            }
            d.mask.shapes.forEachIndexed { i, sh ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ctr = sh.center()
                    val dTxt = ctr?.let { o -> val g = hypot(o.x * spec.dkx, o.y * spec.dky); if (g > 0) " → d=" + Figures.dLabel(1 / g, if (img.calibration.isCalibrated) img.calibration.unit.symbol else "px", com.temlab.core.image.LengthUnit.NM) else "" } ?: ""
                    Text("${i + 1}. " + sh.toString().substringAfter("MaskShape$").substringBefore("(") + (ctr?.let { String.format(Locale.US, " (%.1f, %.1f)", it.x, it.y) } ?: "") + dTxt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { d.mask = d.mask.copy(shapes = d.mask.shapes.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Filled.Delete, null) }
                }
            }
            SwitchRow(tr("Update reconstruction automatically", "Actualizar reconstrucción automáticamente"), auto) { auto = it }
            Button(onClick = { vm.applyFourier(d, roi, d.mask) { s -> result = s; liveRes = s.result } }, enabled = d.mask.shapes.isNotEmpty()) { Text(tr("Compute IFFT & save to history", "Calcular IFFT y guardar en historial")) }
        }
        res?.let { r ->
            Section(tr("Reconstruction", "Reconstrucción")) {
                Text(tr("⚠ Filtered image: Fourier filtering can create artefacts and alter contrast. Do not report features as independent observations.",
                    "⚠ Imagen filtrada: el filtrado de Fourier puede crear artefactos y alterar el contraste. No presente sus detalles como observaciones independientes."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(r.description, style = MaterialTheme.typography.bodySmall)
                KeyValue(tr("Imaginary residual (Hermitian check)", "Residuo imaginario (control hermítico)"), String.format(Locale.US, "%.2e", r.imaginaryResidual))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.openDerived(r.reconstruction.copy(), "IFFT: " + d.mask.describe(), d) }) { Text(tr("Open as image", "Abrir como imagen")) }
                    OutlinedButton(onClick = { saver.save("${d.title}_IFFT.tif", "image/tiff") { TiffWriter.writeFloat(r.reconstruction, it, r.description) } }) { Text("TIFF 32") }
                    OutlinedButton(onClick = { saver.save("${d.title}_IFFT.png", "image/png") { Render.floatBitmap(r.reconstruction.pixels, r.n, r.n, lo = Figures.percentile(r.reconstruction.pixels, 0.002f), hi = Figures.percentile(r.reconstruction.pixels, 0.998f)).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }) { Text("PNG") }
                }
                OutlinedButton(onClick = { saver.save("${d.title}_mask.png", "image/png") { Render.floatBitmap(r.mask, r.n, r.n, 0f, 1f).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }) { Text(tr("Export mask", "Exportar máscara")) }
            }
        }
        Section(tr("Periodicity of the filtered image", "Periodicidad de la imagen filtrada")) {
            Text(tr("Drag a line across the fringes on the IFFT panel.", "Arrastre una línea perpendicular a las franjas en el panel IFFT."), style = MaterialTheme.typography.bodySmall)
            profile?.let { pr ->
                LineChart(pr.positions, pr.values, markers = period?.peakPositions ?: emptyList(), xLabel = pr.unit)
                period?.let { p ->
                    KeyValue(tr("Period (profile Fourier)", "Periodo (Fourier del perfil)"), String.format(Locale.US, "%.4f %s", p.periodFft, p.unit))
                    KeyValue(tr("Mean spacing of maxima", "Separación media de máximos"), String.format(Locale.US, "%.4f ± %.4f %s (n=%d)", p.periodPeaks, p.spacingSd, p.unit, p.peakCount))
                    KeyValue(tr("Fringe orientation", "Orientación de franjas"), String.format(Locale.US, "%.1f°", p.fringeOrientationDeg))
                    // Comparison with the d-spacing of the selected reflections
                    d.mask.shapes.mapNotNull { it.center() }.filter { it.x > 0 || (it.x == 0f && it.y > 0) }.forEach { o ->
                        val g = hypot(o.x * spec.dkx, o.y * spec.dky)
                        if (g > 0) KeyValue(tr("FFT spot d", "d de la reflexión"), String.format(Locale.US, "%.4f %s (Δ = %+.2f %%)", 1 / g, p.unit, (p.periodFft - 1 / g) * g * 100))
                    }
                }
            }
        }
        if (d.iffts.isNotEmpty()) Section(tr("IFFT history", "Historial IFFT")) {
            d.iffts.toList().forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.label}: ${s.mask.shapes.size} " + tr("shapes", "formas"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { d.mask = s.mask; d.roi = s.roi; liveRes = s.result }) { Text(tr("Restore", "Restaurar"), fontSize = 11.sp) }
                    IconButton(onClick = { d.iffts.remove(s) }) { Icon(Icons.Filled.Delete, null) }
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") result
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.maskShapesOverlay(mask: com.temlab.core.fft.FourierMask, c: Int, s: Float, n: Int) {
    val col = Color(0xFF00E5FF); val conj = Color(0x8800E5FF)
    val o = Offset(c.toFloat(), c.toFloat())
    fun drawShape(sh: MaskShape, sign: Float, color: Color) {
        val st = Stroke(2f / s)
        when (sh) {
            is MaskShape.Circle -> drawCircle(color, sh.radius.toFloat(), o + Offset(sign * sh.dx.toFloat(), sign * sh.dy.toFloat()), style = st)
            is MaskShape.Gaussian -> { drawCircle(color, sh.sigma.toFloat(), o + Offset(sign * sh.dx.toFloat(), sign * sh.dy.toFloat()), style = st); drawCircle(color, 2 * sh.sigma.toFloat(), o + Offset(sign * sh.dx.toFloat(), sign * sh.dy.toFloat()), style = Stroke(1f / s)) }
            is MaskShape.Ellipse -> { val ctr = o + Offset(sign * sh.dx.toFloat(), sign * sh.dy.toFloat()); rotate(sh.angleDeg.toFloat(), ctr) { drawOval(color, ctr - Offset(sh.rx.toFloat(), sh.ry.toFloat()), Size(2 * sh.rx.toFloat(), 2 * sh.ry.toFloat()), style = st) } }
            is MaskShape.Rect -> drawRect(color, o + Offset(sign * sh.dx.toFloat() - sh.halfW.toFloat(), sign * sh.dy.toFloat() - sh.halfH.toFloat()), Size(2 * sh.halfW.toFloat(), 2 * sh.halfH.toFloat()), style = st)
            is MaskShape.LowPass -> drawCircle(color, sh.radius.toFloat(), o, style = st)
            is MaskShape.HighPass -> drawCircle(color, sh.radius.toFloat(), o, style = st)
            is MaskShape.BandPass -> { drawCircle(color, sh.inner.toFloat(), o, style = st); drawCircle(color, sh.outer.toFloat(), o, style = st) }
        }
    }
    mask.shapes.forEach { sh -> drawShape(sh, 1f, col); if (mask.symmetric && sh.center() != null) drawShape(sh, -1f, conj) }
    mask.shapes.forEachIndexed { i, sh -> sh.center()?.let { label("${i + 1}", o.x + it.x + 6f / s, o.y + it.y - 6f / s, s, col, 26f) } }
    @Suppress("UNUSED_EXPRESSION") n
}
