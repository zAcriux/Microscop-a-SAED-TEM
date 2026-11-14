/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.temlab.app.data.AppViewModel
import com.temlab.app.data.Doc
import com.temlab.app.data.FftSession
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
import com.temlab.core.crystal.Centering
import com.temlab.core.crystal.CrystalMatcher
import com.temlab.core.crystal.CrystalSystem
import com.temlab.core.crystal.Lattice
import com.temlab.core.export.CsvWriter
import com.temlab.core.export.Table
import com.temlab.core.export.Tables
import com.temlab.core.export.XlsxWriter
import com.temlab.core.fft.FFT
import com.temlab.core.fft.FftAnalysis
import com.temlab.core.fft.FftOptions
import com.temlab.core.fft.Reflection
import com.temlab.core.fft.SpectrumScale
import com.temlab.core.fft.SquareRoi
import com.temlab.core.image.LengthUnit
import com.temlab.core.measure.Profiles
import com.temlab.core.measure.Pt
import com.temlab.core.project.AngleRecord
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

enum class FMode(val en: String, val es: String) {
    SINGLE("Tap maximum", "Tocar máximo"), PAIR("Symmetric pair", "Par simétrico"), ANGLE("Angle (2 reflections)", "Ángulo (2 reflexiones)"),
    PROFILE("Profile (drag)", "Perfil (arrastrar)"), DELETE("Delete", "Eliminar"),
}

/** Square ROI editing on the micrograph: drag inside = move, drag the corner handle = resize (1:1), drag outside = new square. */
@Composable
fun roiTool(d: Doc, w: Int, h: Int): ToolHandler {
    var mode by remember { mutableStateOf(0) }
    var start by remember { mutableStateOf(Offset.Zero) }
    var startRoi by remember { mutableStateOf<SquareRoi?>(null) }
    return ToolHandler(wantsDrag = true, magnifier = true,
        onTap = { p -> val s = d.roi?.size ?: minOf(256, w, h); d.roi = SquareRoi((p.x - s / 2).toInt(), (p.y - s / 2).toInt(), s).clampedTo(w, h) },
        onDragStart = { p ->
            start = p; val r = d.roi; startRoi = r
            mode = when {
                r == null -> 0
                hypot((p.x - (r.x + r.size)).toDouble(), (p.y - (r.y + r.size)).toDouble()) < max(24.0, r.size * 0.12) -> 2
                p.x >= r.x && p.y >= r.y && p.x <= r.x + r.size && p.y <= r.y + r.size -> 1
                else -> 0
            }
        },
        onDrag = { p ->
            val r = startRoi
            d.roi = when {
                mode == 1 && r != null -> SquareRoi((r.x + p.x - start.x).toInt(), (r.y + p.y - start.y).toInt(), r.size).clampedTo(w, h)
                mode == 2 && r != null -> SquareRoi(r.x, r.y, max(p.x - r.x, p.y - r.y).toInt().coerceAtLeast(16)).let { it.copy(size = minOf(it.size, w - r.x, h - r.y)) }
                else -> { val s = max(abs(p.x - start.x), abs(p.y - start.y)).toInt().coerceAtLeast(16); SquareRoi(minOf(start.x, p.x).toInt().let { if (p.x < start.x) (start.x - s).toInt() else it }, if (p.y < start.y) (start.y - s).toInt() else start.y.toInt(), s).clampedTo(w, h) }
            }
        })
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.roiOverlay(r: SquareRoi?, s: Float, color: Color = Color(0xFFFFD54F)) {
    r ?: return
    drawRect(color, Offset(r.x.toFloat(), r.y.toFloat()), Size(r.size.toFloat(), r.size.toFloat()), style = Stroke(2.5f / s))
    drawCircle(color, 10f / s, Offset((r.x + r.size).toFloat(), (r.y + r.size).toFloat()))
    label("${r.size}×${r.size} px", r.x.toFloat(), r.y - 6f / s, s, color, 28f)
}

@Composable
fun FftScreen(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    val img = d.processed
    val tImg = remember(d.id) { ViewTransform() }
    val tFft = remember(d.id) { ViewTransform() }
    var log by remember { mutableStateOf(true) }
    var power by remember { mutableStateOf(false) }
    var gamma by remember { mutableStateOf(1f) }
    var sat by remember { mutableStateOf(0.999f) }
    var colormap by remember { mutableStateOf(false) }
    var padFactor by remember { mutableStateOf(2) }
    var fmode by remember { mutableStateOf(FMode.SINGLE) }
    var radius by remember { mutableStateOf(6f) }
    var unit by remember { mutableStateOf(LengthUnit.NM) }
    var sigma by remember { mutableStateOf(5.0) }
    var cursor by remember { mutableStateOf<Offset?>(null) }
    var expanded by remember { mutableStateOf(-1) }
    val pending = remember { mutableStateListOf<Reflection>() }
    var lastMsg by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf<Pair<DoubleArray, DoubleArray>?>(null) }
    var profileLine by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val saver = rememberFileSaver { vm.notify(it) }
    if (d.roi == null) d.roi = SquareRoi((img.width - minOf(512, img.width, img.height)) / 2, (img.height - minOf(512, img.width, img.height)) / 2, minOf(512, img.width, img.height))
    val s = d.activeFft

    val tile: RenderedTile? = remember(s, log, power, gamma, sat, colormap) {
        s?.let {
            val disp = it.result.display(if (power) SpectrumScale.POWER else SpectrumScale.AMPLITUDE, log)
            val hi = Figures.percentile(disp, sat)
            RenderedTile(Render.floatBitmap(disp, it.result.n, it.result.n, lo = Figures.percentile(disp, 0.5f), hi = hi, gamma = gamma.toDouble(), colormap = colormap).asImageBitmap(), 0, 0, it.result.n, it.result.n)
        }
    }
    fun dStr(r: Reflection) = Figures.dLabel(r.d, r.unit, unit)
    fun nearest(sess: FftSession, p: Offset) = sess.reflections.minByOrNull { hypot(it.u - p.x, it.v - p.y) }?.takeIf { hypot(it.u - p.x, it.v - p.y) < max(12.0, radius * 2.0) }

    fun act(sess: FftSession, p: Offset) {
        val rad = radius.toInt()
        when (fmode) {
            FMode.SINGLE -> vm.addReflection(sess, p.x.toDouble(), p.y.toDouble(), rad)?.let { lastMsg = "#${it.id}: d = ${dStr(it)}  |g| = ${String.format(Locale.US, "%.4f", it.g)} 1/${it.unit}" }
            FMode.PAIR -> {
                val r = nearest(sess, p) ?: vm.addReflection(sess, p.x.toDouble(), p.y.toDouble(), rad) ?: return
                pending += r
                if (pending.size == 2) {
                    val pr = FftAnalysis.symmetricPair(sess.result, pending[0], pending[1])
                    sess.pairs += pr
                    lastMsg = String.format(Locale.US, tr("Pair #%d/#%d: d = %s (Δd = %.2f %%, origin offset %.2f px)", "Par #%d/#%d: d = %s (Δd = %.2f %%, desplazamiento del origen %.2f px)"),
                        pr.a.id, pr.b.id, Figures.dLabel(pr.dMean, pr.unit, unit), pr.dDifferencePercent, pr.originOffsetPx)
                    pending.clear()
                }
            }
            FMode.ANGLE -> {
                val r = nearest(sess, p) ?: vm.addReflection(sess, p.x.toDouble(), p.y.toDouble(), rad) ?: return
                pending += r
                if (pending.size == 2) {
                    val a = FftAnalysis.angleBetween(pending[0], pending[1])
                    sess.angles += AngleRecord(pending[0].id, pending[1].id, a)
                    lastMsg = String.format(Locale.US, tr("Angle #%d–#%d = %.2f°", "Ángulo #%d–#%d = %.2f°"), pending[0].id, pending[1].id, a)
                    pending.clear()
                }
            }
            FMode.DELETE -> nearest(sess, p)?.let { sess.reflections.remove(it) }
            FMode.PROFILE -> {}
        }
    }

    val fftTool = s?.let { sess ->
        if (fmode == FMode.PROFILE) ToolHandler(wantsDrag = true, magnifier = true,
            onDragStart = { p -> profileLine = p to p }, onDrag = { p -> profileLine = profileLine?.first?.let { it to p } },
            onDragEnd = {
                profileLine?.let { (a, b) ->
                    val n = sess.result.n
                    val amp = com.temlab.core.image.ImageData(n, n, FloatArray(n * n) { sess.result.amplitude[it].toFloat() })
                    val pr = Profiles.line(amp, Pt(a.x.toDouble(), a.y.toDouble()), Pt(b.x.toDouble(), b.y.toDouble()), 1)
                    val dk = hypot((b.x - a.x) * sess.result.dkx, (b.y - a.y) * sess.result.dky) / max(1, pr.values.size - 1)
                    profile = DoubleArray(pr.values.size) { it * dk } to pr.values
                }
            })
        else ToolHandler(wantsDrag = true, magnifier = true, onTap = { p -> act(sess, p) },
            onDragStart = { p -> cursor = p }, onDrag = { p -> cursor = p }, onDragEnd = { cursor?.let { act(sess, it) }; cursor = null })
    } ?: ToolHandler()

    AdaptivePane(image = { mod ->
        BoxWithConstraints(mod) {
            val wide = maxWidth > maxHeight
            @Composable fun expandBtn(idx: Int, m: Modifier) = IconButton(onClick = { expanded = if (expanded == idx) -1 else idx }, modifier = m) {
                Icon(if (expanded == idx) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, tr("Enlarge / restore", "Ampliar / restaurar"), tint = Color.White)
            }
            @Composable fun imagePane(m1: Modifier) = androidx.compose.foundation.layout.Box(m1) {
                DocImage(d, Modifier.fillMaxSize(), tImg, roiTool(d, img.width, img.height), showScaleBar = false) { sc -> roiOverlay(d.roi, sc); d.ffts.filter { it != s }.forEach { roiOverlay(it.roi, sc, Color(0x8800E5FF)) } }
                expandBtn(0, Modifier.align(Alignment.TopEnd))
            }
            @Composable fun fftPane(m2: Modifier) = androidx.compose.foundation.layout.Box(m2) {
                if (s != null) ZoomableImage(tile, s.result.n, s.result.n, Modifier.fillMaxSize(), tFft, fftTool) { sc ->
                    val c = s.result.center.toFloat()
                    drawCircle(Color.Cyan, 5f / sc, Offset(c, c), style = Stroke(2f / sc))
                    for (r in s.reflections) {
                        val col = if (r in pending) Color.Magenta else Color(0xFFFFD54F)
                        drawCircle(col, max(radius, 4f), Offset(r.u.toFloat(), r.v.toFloat()), style = Stroke(2f / sc))
                        drawLine(col.copy(alpha = 0.5f), Offset(c, c), Offset(r.u.toFloat(), r.v.toFloat()), 1f / sc)
                        label("#${r.id} ${dStr(r)}", r.u.toFloat() + max(radius, 4f) + 3f / sc, r.v.toFloat(), sc, col, 28f)
                    }
                    cursor?.let { drawCircle(Color.Green, radius, it, style = Stroke(1.5f / sc)) }
                    profileLine?.let { (a, b) -> drawLine(Color.Green, a, b, 2f / sc) }
                } else com.temlab.app.ui.EmptyHint(tr("Select a square region on the micrograph and press “Compute FFT”.", "Seleccione una región cuadrada en la micrografía y pulse “Calcular FFT”."))
            
                if (s != null) expandBtn(1, Modifier.align(Alignment.TopEnd))
            }
            val content: @Composable (Modifier, Modifier) -> Unit = { m1, m2 -> imagePane(m1); fftPane(m2) }
            if (expanded == 0) imagePane(Modifier.fillMaxSize()) else if (expanded == 1) fftPane(Modifier.fillMaxSize()) else
            if (wide) Row(Modifier.fillMaxSize()) { content(Modifier.weight(1f).fillMaxSize(), Modifier.weight(1f).fillMaxSize()) }
            else Column(Modifier.fillMaxSize()) { content(Modifier.weight(1f).fillMaxWidth(), Modifier.weight(1f).fillMaxWidth()) }
        }
    }) {
        Section(tr("Square region (original pixels)", "Región cuadrada (píxeles originales)")) {
            val r = d.roi!!
            Text("x=${r.x}  y=${r.y}  ${r.size}×${r.size} px" + if (img.calibration.isCalibrated) String.format(Locale.US, "  (%.4g %s)", r.size * img.calibration.pixelWidth, img.calibration.unitLabel) else "", style = MaterialTheme.typography.bodySmall)
            ChipRow(listOf(128, 256, 512, 1024, 2048).filter { it <= minOf(img.width, img.height) }, r.size, { "$it" }) { d.roi = r.copy(size = it).clampedTo(img.width, img.height) }
            NumberField(tr("Custom size (px)", "Tamaño personalizado (px)"), r.size.toDouble()) { v -> d.roi = r.copy(size = v.toInt()).clampedTo(img.width, img.height) }
            Text(tr("Drag inside the square to move it, drag the corner handle to resize (1:1), drag elsewhere to draw a new one.",
                "Arrastre dentro del cuadrado para moverlo, el asa de la esquina para redimensionar (1:1) o fuera para dibujar uno nuevo."), style = MaterialTheme.typography.bodySmall)
        }
        Section(tr("Transform", "Transformada")) {
            SwitchRow(tr("Hann window", "Ventana Hann"), d.fftOptions.hannWindow) { d.fftOptions = d.fftOptions.copy(hannWindow = it) }
            Text(tr("Zero-padding", "Relleno con ceros (zero-padding)"), style = MaterialTheme.typography.bodySmall)
            ChipRow(listOf(1, 2, 4), padFactor, { if (it == 1) tr("none", "ninguno") else "×$it" }) { padFactor = it }
            val r = d.roi!!
            val n = if (padFactor == 1) r.size else minOf(4096, FFT.nextPowerOfTwo(r.size) * padFactor)
            Text(tr("Transform size: $n × $n", "Tamaño de la transformada: $n × $n") + if (!FFT.isPowerOfTwo(r.size) && padFactor == 1) tr(" (exact Bluestein DFT)", " (DFT exacta de Bluestein)") else "", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.computeFft(d, r, d.fftOptions.copy(paddedSize = if (padFactor == 1) 0 else n)) }) { Text(tr("Compute FFT", "Calcular FFT")) }
        }
        if (s != null) {
            Section(tr("Spectrum display", "Visualización del espectro")) {
                SwitchRow(tr("Logarithmic scale", "Escala logarítmica"), log) { log = it }
                SwitchRow(tr("Power spectrum (|F|²)", "Espectro de potencia (|F|²)"), power) { power = it }
                SwitchRow(tr("Colour map", "Mapa de color"), colormap) { colormap = it }
                LabeledSlider(tr("Contrast (saturation percentile)", "Contraste (percentil de saturación)"), sat, 0.9f..1f, { sat = it }, format = { String.format(Locale.US, "%.3f", it) })
                LabeledSlider(tr("Gamma / brightness", "Gamma / brillo"), gamma, 0.2f..3f, { gamma = it })
                Text(String.format(Locale.US, tr("Reciprocal calibration: Δk = %.5g × %.5g 1/%s per pixel", "Calibración recíproca: Δk = %.5g × %.5g 1/%s por píxel"), s.result.dkx, s.result.dky, if (img.calibration.isCalibrated) img.calibration.unit.symbol else "px"), style = MaterialTheme.typography.bodySmall)
            }
            Section(tr("Measure reflections", "Medir reflexiones")) {
                ChipRow(FMode.entries, fmode, { tr(it.en, it.es) }) { fmode = it; pending.clear() }
                LabeledSlider(tr("Search radius (FFT px)", "Radio de búsqueda (px FFT)"), radius, 2f..30f, { radius = it }, format = { "${it.toInt()}" })
                ChipRow(listOf(LengthUnit.NM, LengthUnit.ANGSTROM), unit, { it.symbol }) { unit = it }
                if (lastMsg.isNotEmpty()) Text(lastMsg, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(tr("Detection threshold (σ)", "Umbral de detección (σ)"), sigma, Modifier.weight(1f)) { sigma = it }
                    OutlinedButton(onClick = { vm.autoDetect(s, sigma) }) { Text(tr("Auto-detect", "Detectar")) }
                }
                Row { TextButton(onClick = { s.reflections.clear(); s.pairs.clear(); s.angles.clear() }) { Text(tr("Clear all", "Borrar todo")) } }
                Text(tr("Tap a maximum (or drag to place precisely with the magnifier). The nearest local maximum is located and refined to sub-pixel precision; d = 1/|g|.",
                    "Toque un máximo (o arrastre para posicionar con la lupa). Se localiza el máximo local más cercano y se refina con precisión subpíxel; d = 1/|g|."), style = MaterialTheme.typography.bodySmall)
            }
            Section(tr("Results", "Resultados")) {
                s.reflections.toList().forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(String.format(Locale.US, "#%d (%.2f, %.2f)  |g|=%.4f  d=%s  %.1f°%s", r.id, r.u - s.result.center, r.v - s.result.center, r.g, dStr(r), r.angleDeg, if (r.refined) "" else " (int)"),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { s.reflections.remove(r) }) { Icon(Icons.Filled.Delete, null) }
                    }
                }
                s.pairs.forEach { p -> KeyValue(tr("Pair", "Par") + " #${p.a.id}/#${p.b.id}", String.format(Locale.US, "d=%s Δ=%.2f%%", Figures.dLabel(p.dMean, p.unit, unit), p.dDifferencePercent)) }
                s.angles.forEach { a -> KeyValue(tr("Angle", "Ángulo") + " #${a.a}–#${a.b}", String.format(Locale.US, "%.2f°", a.angleDeg)) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { saver.save("${d.title}_${s.label.replace(' ', '_')}.png", "image/png") { Figures.fftFigure(s, log, if (power) SpectrumScale.POWER else SpectrumScale.AMPLITUDE, gamma.toDouble(), unit, sat).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }) { Text("PNG") }
                    OutlinedButton(onClick = { saver.save("${d.title}_reflections.csv", "text/csv") { CsvWriter.write(reflectionTable(s, unit), it) } }) { Text("CSV") }
                    OutlinedButton(onClick = { saver.save("${d.title}_fft.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") { XlsxWriter.write(listOf(reflectionTable(s, unit), pairTable(s, unit), angleTable(s)), it) } }) { Text("XLSX") }
                }
            }
            profile?.let { (x, y) -> Section(tr("Profile across the FFT", "Perfil en la FFT")) { LineChart(x, y, logY = true, xLabel = "1/${s.result.unit.symbol}") } }
            Section(tr("Radial intensity profile", "Perfil radial de intensidad")) {
                val rp = remember(s) { FftAnalysis.radialProfile(s.result) }
                LineChart(rp.first, rp.second, logY = true, xLabel = "1/${if (img.calibration.isCalibrated) img.calibration.unit.symbol else "px"}")
            }
            CrystalPanel(s, unit)
            Section(tr("Saved FFTs of this micrograph", "FFT guardadas de esta micrografía")) {
                d.ffts.toList().forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { d.activeFft = f; d.roi = f.roi }, modifier = Modifier.weight(1f)) { Text((if (f == s) "● " else "") + f.label + " (${f.reflections.size})") }
                        IconButton(onClick = { d.ffts.remove(f); if (d.activeFft == f) d.activeFft = d.ffts.lastOrNull() }) { Icon(Icons.Filled.Delete, null) }
                    }
                }
            }
        }
    }
}

fun reflectionTable(s: FftSession, unit: LengthUnit): Table {
    val px = s.reflections.firstOrNull()?.unit == "px"
    return Tables.reflections(s.reflections, if (px) "px" else unit.symbol, if (px) 1.0 else FftAnalysis.convert(1.0, s.reflections.firstOrNull()?.unit ?: "nm", unit))
}
fun pairTable(s: FftSession, unit: LengthUnit) = Table("Symmetric pairs", listOf("A", "B", "d mean (${unit.symbol})", "|g| mean", "Δd (%)", "Origin offset (px)", "Region"),
    s.pairs.map { listOf(it.a.id, it.b.id, if (it.unit == "px") it.dMean else FftAnalysis.convert(it.dMean, it.unit, unit), it.gMean, it.dDifferencePercent, it.originOffsetPx, s.label) })
fun angleTable(s: FftSession) = Table("Angles", listOf("Reflection A", "Reflection B", "Angle (°)", "Region"), s.angles.map { listOf(it.a, it.b, it.angleDeg, s.label) })

@Composable
fun CrystalPanel(s: FftSession, unit: LengthUnit) {
    val tr = LocalTr.current
    var lattice by remember { mutableStateOf(Lattice.PRESETS.first()) }
    var tol by remember { mutableStateOf(3.0) }
    var custom by remember { mutableStateOf(false) }
    var sys by remember { mutableStateOf(CrystalSystem.CUBIC) }
    var a by remember { mutableStateOf(4.0) }; var c by remember { mutableStateOf(4.0) }
    var cen by remember { mutableStateOf(Centering.P) }
    Section(tr("Crystallographic comparison (tentative)", "Comparación cristalográfica (tentativa)")) {
        SwitchRow(tr("Custom lattice", "Red personalizada"), custom) { custom = it }
        if (!custom) ChipRow(Lattice.PRESETS, lattice, { it.name }) { lattice = it }
        else {
            ChipRow(listOf(CrystalSystem.CUBIC, CrystalSystem.TETRAGONAL, CrystalSystem.HEXAGONAL), sys, { it.name.lowercase() }) { sys = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("a (Å)", a, Modifier.weight(1f)) { a = it }
                if (sys != CrystalSystem.CUBIC) NumberField("c (Å)", c, Modifier.weight(1f)) { c = it }
            }
            ChipRow(Centering.entries, cen, { it.name }) { cen = it }
        }
        val lat = if (custom) when (sys) { CrystalSystem.TETRAGONAL -> Lattice.tetragonal("custom", a, c, cen); CrystalSystem.HEXAGONAL -> Lattice.hexagonal("custom", a, c, cen); else -> Lattice.cubic("custom", a, cen) } else lattice
        NumberField(tr("Tolerance (%)", "Tolerancia (%)"), tol) { tol = it }
        val refl = s.reflections.toList()
        if (refl.isEmpty() || refl.first().unit == "px") Text(tr("Requires calibrated reflections.", "Requiere reflexiones calibradas."), style = MaterialTheme.typography.bodySmall)
        else {
            refl.takeLast(4).forEach { r ->
                val dA = FftAnalysis.convert(r.d, r.unit, LengthUnit.ANGSTROM)
                val cands = CrystalMatcher.matchD(lat, dA, tol)
                Text("#${r.id} d = ${String.format(Locale.US, "%.4f", dA)} Å → " + if (cands.isEmpty()) tr("no candidate", "sin candidato")
                    else cands.take(4).joinToString(", ") { String.format(Locale.US, "{%d%d%d} %.4f Å (%+.1f%%)", abs(it.h), abs(it.k), abs(it.l), it.d, it.deviationPercent) }, style = MaterialTheme.typography.bodySmall)
            }
            if (refl.size >= 2) {
                val r1 = refl[refl.size - 2]; val r2 = refl.last()
                val ang = FftAnalysis.angleBetween(r1, r2)
                val pairs = CrystalMatcher.matchPair(lat, FftAnalysis.convert(r1.d, r1.unit, LengthUnit.ANGSTROM), FftAnalysis.convert(r2.d, r2.unit, LengthUnit.ANGSTROM), ang, tol, 2.0)
                Text(String.format(Locale.US, tr("#%d/#%d (%.1f°): ", "#%d/#%d (%.1f°): "), r1.id, r2.id, ang) + if (pairs.isEmpty()) tr("no consistent pair", "ningún par consistente")
                    else pairs.take(3).joinToString("; ") { p -> val z = CrystalMatcher.zoneAxis(p.first, p.second); "${CrystalMatcher.formatHkl(p.first.h, p.first.k, p.first.l)}/${CrystalMatcher.formatHkl(p.second.h, p.second.k, p.second.l)} ${String.format(Locale.US, "%.1f°", p.angleTheory)} [${z.first}${z.second}${z.third}]" },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (lat.note.isNotEmpty()) Text("ⓘ ${lat.note}", style = MaterialTheme.typography.bodySmall)
        Text(tr("Candidate indices are TENTATIVE. A d-spacing alone never identifies a phase: validate with additional reflections, angles, SAED, EDS or XRD.",
            "Los índices candidatos son TENTATIVOS. Una distancia interplanar por sí sola nunca identifica una fase: valide con más reflexiones, ángulos, SAED, EDS o DRX."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
    }
}
