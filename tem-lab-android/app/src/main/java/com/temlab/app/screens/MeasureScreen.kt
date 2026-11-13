package com.temlab.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.temlab.app.data.AppViewModel
import com.temlab.app.data.Doc
import com.temlab.app.render.Render
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
import com.temlab.app.ui.measurementOverlay
import com.temlab.app.ui.poly
import com.temlab.app.ui.scaleBarOverlay
import com.temlab.core.image.Calibration
import com.temlab.core.image.LengthUnit
import com.temlab.core.measure.MeasureKind
import com.temlab.core.measure.Measure
import com.temlab.core.measure.PeriodicityResult
import com.temlab.core.measure.Profile
import com.temlab.core.measure.Profiles
import com.temlab.core.measure.Pt
import com.temlab.core.process.DisplaySettings
import com.temlab.core.project.ScaleBarStyle
import java.util.Locale

enum class MTool(val en: String, val es: String, val drag: Boolean) {
    PAN("Pan/zoom", "Mover/zoom", false), DISTANCE("Distance", "Distancia", true), DIAMETER("Diameter", "Diámetro", true),
    RADIUS("Radius", "Radio", true), ANGLE("Angle (3 taps)", "Ángulo (3 toques)", false), POLYLINE("Length (taps)", "Longitud (toques)", false),
    AREA("Area (freehand)", "Área (a mano alzada)", true), FRINGES("Fringes (N periods)", "Franjas (N periodos)", true),
    PROFILE("Profile", "Perfil", true), CALIBRATE("2-point calibration", "Calibración 2 puntos", true),
}

/** Shared image view of a document (preview + full-resolution detail on zoom). */
@Composable
fun DocImage(d: Doc, modifier: Modifier, transform: ViewTransform = remember(d.id) { ViewTransform() }, tool: ToolHandler = ToolHandler(),
             showScaleBar: Boolean = true, onTransformChanged: (() -> Unit)? = null, overlay: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit = {}) {
    val img = d.processed
    ZoomableImage(d.preview, img.width, img.height, modifier, transform, tool,
        detailRenderer = { x, y, w, h, m -> Render.region(img, d.display, x, y, w, h, m) }, onTransformChanged = onTransformChanged) { s ->
        overlay(s)
        if (showScaleBar) scaleBarOverlay(img, d.scaleBar, s)
    }
}

@Composable
fun MeasureScreen(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    val transform = remember(d.id) { ViewTransform() }
    var tool by remember { mutableStateOf(MTool.PAN) }
    val pts = remember { mutableStateListOf<Offset>() }
    var periods by remember { mutableStateOf(5.0) }
    var profileWidth by remember { mutableStateOf(3f) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    var periodicity by remember { mutableStateOf<PeriodicityResult?>(null) }
    var calibLine by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var includeInDist by remember { mutableStateOf(false) }
    val cal = d.processed.calibration

    fun finish(kind: MeasureKind, p: List<Offset>) {
        if (p.size < 2) return
        val id = (d.measurements.maxOfOrNull { it.id } ?: 0) + 1
        var m = Measure.manual(id, kind, p.map { Pt(it.x.toDouble(), it.y.toDouble()) }, cal, periods.toInt())
        if (kind == MeasureKind.DIAMETER || kind == MeasureKind.RADIUS) {
            // Detect duplicates of auto-detected particles: midpoint inside an included particle.
            val mid = if (kind == MeasureKind.DIAMETER) (p[0] + p[1]) / 2f else p[0]
            val linked = d.particles?.particleAt(mid.x.toInt(), mid.y.toInt())?.takeIf { it.included }?.id
            m = m.copy(linkedParticle = linked, includeInDistribution = includeInDist && linked == null,
                value = if (kind == MeasureKind.RADIUS) m.value else m.value)
        }
        d.measurements += m
    }

    val handler = when (tool) {
        MTool.PAN -> ToolHandler()
        MTool.ANGLE, MTool.POLYLINE -> ToolHandler(onTap = { p ->
            pts += p
            if (tool == MTool.ANGLE && pts.size == 3) { finish(MeasureKind.ANGLE, pts.toList()); pts.clear() }
        })
        else -> ToolHandler(wantsDrag = true, magnifier = true,
            onDragStart = { p -> pts.clear(); pts += p; pts += p },
            onDrag = { p -> if (tool == MTool.AREA) pts += p else if (pts.size >= 2) pts[1] = p },
            onDragEnd = {
                val p = pts.toList()
                when (tool) {
                    MTool.DISTANCE -> finish(MeasureKind.DISTANCE, p)
                    MTool.DIAMETER -> finish(MeasureKind.DIAMETER, p)
                    MTool.RADIUS -> finish(MeasureKind.RADIUS, p)
                    MTool.AREA -> finish(MeasureKind.AREA, p.filterIndexed { i, _ -> i % 2 == 0 })
                    MTool.FRINGES -> finish(MeasureKind.FRINGES, p)
                    MTool.PROFILE -> if (p.size >= 2) {
                        val a = Pt(p[0].x.toDouble(), p[0].y.toDouble()); val b = Pt(p[1].x.toDouble(), p[1].y.toDouble())
                        val pr = Profiles.line(d.processed, a, b, profileWidth.toInt()); profile = pr
                        periodicity = if (pr.values.size > 16) Profiles.periodicity(pr, Measure.orientation(cal, a, b)) else null
                    }
                    MTool.CALIBRATE -> if (p.size >= 2) calibLine = p[0] to p[1]
                    else -> {}
                }
                if (tool != MTool.PROFILE) pts.clear()
            })
    }

    AdaptivePane(image = { mod ->
        Box(mod) {
            DocImage(d, Modifier.fillMaxSize(), transform, handler) { s ->
                d.measurements.forEach { measurementOverlay(it, s) }
                if (pts.isNotEmpty()) poly(pts.toList(), s, Color(0xFFFFD54F), closed = tool == MTool.AREA)
                if (tool == MTool.PROFILE && pts.size >= 2) label(tr("profile", "perfil"), pts[1].x, pts[1].y, s)
            }
            Row(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { transform.rotation -= 90f }) { Icon(Icons.AutoMirrored.Filled.RotateLeft, tr("Rotate view", "Rotar vista"), tint = Color.White) }
                IconButton(onClick = { transform.rotation += 90f }) { Icon(Icons.AutoMirrored.Filled.RotateRight, null, tint = Color.White) }
                IconButton(onClick = { transform.rotation = 0f; transform.fit(d.processed.width, d.processed.height) }) { Icon(Icons.Filled.FitScreen, null, tint = Color.White) }
            }
        }
    }) {
        Section(tr("Tool", "Herramienta")) {
            ChipRow(MTool.entries, tool, { tr(it.en, it.es) }) { tool = it; pts.clear() }
            if (tool == MTool.FRINGES) NumberField(tr("Number of periods spanned", "Número de periodos abarcados"), periods) { periods = it.coerceAtLeast(1.0) }
            if (tool == MTool.PROFILE) LabeledSlider(tr("Profile width (px)", "Ancho del perfil (px)"), profileWidth, 1f..31f, { profileWidth = it }, steps = 29, format = { "${it.toInt()}" })
            if (tool == MTool.DIAMETER || tool == MTool.RADIUS) SwitchRow(tr("Add to size distribution", "Incluir en la distribución de tamaños"), includeInDist) { includeInDist = it }
            if (tool == MTool.POLYLINE && pts.size >= 2) Button(onClick = { finish(MeasureKind.POLYLINE, pts.toList()); pts.clear() }) { Text(tr("Finish line", "Terminar línea")) }
            Text(tr("One finger draws with the selected tool; two fingers zoom and pan. A magnifier appears while drawing.",
                "Un dedo dibuja con la herramienta; dos dedos hacen zoom y desplazan. Aparece una lupa al dibujar."), style = MaterialTheme.typography.bodySmall)
        }
        profile?.let { pr ->
            Section(tr("Intensity profile", "Perfil de intensidad")) {
                LineChart(pr.positions, pr.values, markers = periodicity?.peakPositions ?: emptyList(), xLabel = pr.unit)
                periodicity?.let { r ->
                    KeyValue(tr("Period (Fourier of profile)", "Periodo (Fourier del perfil)"), String.format(Locale.US, "%.4f %s", r.periodFft, r.unit))
                    KeyValue(tr("Mean spacing between maxima", "Separación media entre máximos"), String.format(Locale.US, "%.4f ± %.4f %s (n=%d)", r.periodPeaks, r.spacingSd, r.unit, r.peakCount))
                    KeyValue(tr("Fringe orientation", "Orientación de franjas"), String.format(Locale.US, "%.1f°", r.fringeOrientationDeg))
                }
            }
        }
        Section(tr("Measurements", "Mediciones")) {
            if (d.measurements.isEmpty()) Text(tr("None yet", "Aún no hay"), style = MaterialTheme.typography.bodySmall)
            d.measurements.toList().forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(Locale.US, "#%d %s: %.5g %s%s", m.id, m.kind.name.lowercase(), m.value, m.unit,
                        when { m.linkedParticle != null -> tr(" (duplicate of particle #${m.linkedParticle})", " (duplica partícula #${m.linkedParticle})"); m.includeInDistribution -> tr(" [in distribution]", " [en distribución]"); else -> "" }),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { d.measurements.remove(m) }) { Icon(Icons.Filled.Delete, null) }
                }
            }
        }
        CalibrationSection(vm, d)
        ScaleBarSection(d)
        DisplaySection(vm, d)
        MetadataSection(d)
    }

    calibLine?.let { (a, b) ->
        var known by remember { mutableStateOf(10.0) }
        var unit by remember { mutableStateOf(LengthUnit.NM) }
        val lenPx = Math.hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
        AlertDialog(onDismissRequest = { calibLine = null }, title = { Text(tr("Two-point calibration", "Calibración de dos puntos")) }, text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(String.format(Locale.US, tr("Segment length: %.2f px", "Longitud del segmento: %.2f px"), lenPx))
                NumberField(tr("Known length", "Longitud conocida"), known) { known = it }
                ChipRow(listOf(LengthUnit.PM, LengthUnit.NM, LengthUnit.UM, LengthUnit.MM), unit, { it.symbol }) { unit = it }
                Text(tr("Sets an isotropic pixel size (X = Y). The change is logged; the original file is not modified.",
                    "Define un tamaño de píxel isotrópico (X = Y). El cambio queda registrado; el archivo original no se modifica."), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (lenPx > 0 && known > 0) {
                    // The line was drawn on the processed image; for non-geometric pipelines pixel size is identical.
                    val ps = known / lenPx
                    vm.setCalibration(d, Calibration(ps, ps, unit, false, "manual 2-point"), String.format(Locale.US, "2-point: %.3f px = %g %s", lenPx, known, unit.symbol))
                }
                calibLine = null
            }) { Text(tr("Apply", "Aplicar")) }
        }, dismissButton = { TextButton(onClick = { calibLine = null }) { Text(tr("Cancel", "Cancelar")) } })
    }
}


@Composable
fun CalibrationSection(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    var pw by remember(d.calibration) { mutableStateOf(d.calibration.pixelWidth) }
    var ph by remember(d.calibration) { mutableStateOf(d.calibration.pixelHeight) }
    var unit by remember(d.calibration) { mutableStateOf(if (d.calibration.isCalibrated) d.calibration.unit else LengthUnit.NM) }
    Section(tr("Spatial calibration", "Calibración espacial")) {
        val c = d.calibration
        Text(if (c.isCalibrated) String.format(Locale.US, "X: %.6g  Y: %.6g %s/px  (%s)", c.pixelWidth, c.pixelHeight, c.unitLabel, c.source)
            else tr("Uncalibrated: results in pixels", "Sin calibrar: resultados en píxeles") + " (${c.source})", style = MaterialTheme.typography.bodySmall)
        if (d.fileCalibration.isCalibrated) Text(String.format(Locale.US, tr("From file: %.6g %s/px", "Del archivo: %.6g %s/px"), d.fileCalibration.pixelWidth, d.fileCalibration.unitLabel), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField(tr("Pixel X", "Píxel X"), pw, Modifier.weight(1f)) { pw = it }
            NumberField(tr("Pixel Y", "Píxel Y"), ph, Modifier.weight(1f)) { ph = it }
        }
        ChipRow(listOf(LengthUnit.PM, LengthUnit.ANGSTROM, LengthUnit.NM, LengthUnit.UM, LengthUnit.MM), unit, { it.symbol }) { u ->
            // Converting the unit keeps the physical pixel size.
            if (d.calibration.isCalibrated && !d.calibration.reciprocal) { pw = d.calibration.unit.convert(pw, u); ph = d.calibration.unit.convert(ph, u) }
            unit = u
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { if (pw > 0 && ph > 0) vm.setCalibration(d, Calibration(pw, ph, unit, d.calibration.reciprocal, "manual"), "manual entry") }) { Text(tr("Apply", "Aplicar")) }
            OutlinedButton(onClick = { vm.setCalibration(d, d.fileCalibration, "restore file calibration") }) { Text(tr("Restore file", "Restaurar archivo")) }
        }
        if (d.calibrationLog.isNotEmpty()) {
            Text(tr("Calibration log", "Registro de calibración"), style = MaterialTheme.typography.labelMedium)
            d.calibrationLog.forEach { Text("• ${java.text.DateFormat.getTimeInstance().format(java.util.Date(it.timestamp))}: ${trimCal(it.from)} → ${trimCal(it.to)} — ${it.reason}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun trimCal(c: Calibration) = if (c.isCalibrated) String.format(Locale.US, "%.5g %s", c.pixelWidth, c.unitLabel) else "px"

@Composable
fun ScaleBarSection(d: Doc) {
    val tr = LocalTr.current
    val s = d.scaleBar
    fun set(n: ScaleBarStyle) { d.scaleBar = n }
    Section(tr("Scale bar (visual only)", "Barra de escala (solo visual)")) {
        SwitchRow(tr("Show", "Mostrar"), s.visible) { set(s.copy(visible = it)) }
        SwitchRow(tr("Show text", "Mostrar texto"), s.showText) { set(s.copy(showText = it)) }
        SwitchRow(tr("Background", "Fondo"), s.background) { set(s.copy(background = it)) }
        NumberField(tr("Length (${d.processed.calibration.unitLabel}; empty = auto)", "Longitud (${d.processed.calibration.unitLabel}; vacío = auto)"), s.lengthPhysical) { set(s.copy(lengthPhysical = it)) }
        OutlinedTextField(s.text, { set(s.copy(text = it)) }, label = { Text(tr("Custom text", "Texto personalizado")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChipRow(listOf("BOTTOM_RIGHT", "BOTTOM_LEFT", "TOP_RIGHT", "TOP_LEFT"), s.position, { it.replace('_', ' ').lowercase() }) { set(s.copy(position = it)) }
        ChipRow(listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFD54F, 0xFF5EC8F2), s.color, { when (it) { 0xFFFFFFFF -> tr("white", "blanco"); 0xFF000000 -> tr("black", "negro"); 0xFFFFD54F -> tr("yellow", "amarillo"); else -> tr("cyan", "cian") } }) { set(s.copy(color = it)) }
        LabeledSlider(tr("Thickness", "Grosor"), (s.thicknessFraction * 100).toFloat(), 0.3f..4f, { set(s.copy(thicknessFraction = it / 100.0)) }, format = { String.format(Locale.US, "%.1f %%", it) })
        Text(tr("Changing the scale bar never changes the scientific calibration.", "Modificar la barra de escala nunca altera la calibración científica."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DisplaySection(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    val ds = d.display
    val (mn, mx) = remember(d.processed) { d.processed.minMax() }
    Section(tr("Display (does not change data)", "Visualización (no altera los datos)")) {
        LabeledSlider(tr("Minimum", "Mínimo"), (if (ds.min.isNaN()) mn.toDouble() else ds.min).toFloat(), mn..mx, { d.display = ds.copy(min = it.toDouble()) }, onDone = { vm.setDisplay(d, d.display) }, format = { String.format(Locale.US, "%.4g", it) })
        LabeledSlider(tr("Maximum", "Máximo"), (if (ds.max.isNaN()) mx.toDouble() else ds.max).toFloat(), mn..mx, { d.display = ds.copy(max = it.toDouble()) }, onDone = { vm.setDisplay(d, d.display) }, format = { String.format(Locale.US, "%.4g", it) })
        LabeledSlider(tr("Brightness", "Brillo"), ds.brightness.toFloat(), -0.5f..0.5f, { d.display = ds.copy(brightness = it.toDouble()) }, onDone = { vm.setDisplay(d, d.display) })
        LabeledSlider(tr("Contrast", "Contraste"), ds.contrast.toFloat(), 0.2f..5f, { d.display = ds.copy(contrast = it.toDouble()) }, onDone = { vm.setDisplay(d, d.display) })
        LabeledSlider("Gamma", ds.gamma.toFloat(), 0.2f..3f, { d.display = ds.copy(gamma = it.toDouble()) }, onDone = { vm.setDisplay(d, d.display) })
        SwitchRow(tr("Invert display", "Invertir visualización"), ds.invert) { vm.setDisplay(d, ds.copy(invert = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.setDisplay(d, DisplaySettings.auto(d.processed)) }) { Text(tr("Auto", "Auto")) }
            OutlinedButton(onClick = { vm.setDisplay(d, DisplaySettings()) }) { Text(tr("Full range", "Rango completo")) }
        }
    }
}

@Composable
fun MetadataSection(d: Doc) {
    val tr = LocalTr.current
    var expanded by remember { mutableStateOf(false) }
    val img = d.original
    Section(tr("File & metadata", "Archivo y metadatos")) {
        KeyValue(tr("File", "Archivo"), d.displayName)
        KeyValue(tr("Dimensions", "Dimensiones"), "${img.width} × ${img.height} px")
        KeyValue(tr("Data type", "Tipo de datos"), img.pixelType.label)
        val (mn, mx) = remember(img) { img.minMax() }
        KeyValue(tr("Intensity range", "Rango de intensidad"), String.format(Locale.US, "%.5g – %.5g", mn, mx))
        KeyValue(tr("Memory", "Memoria"), String.format(Locale.US, "%.1f MB (float32)", img.pixels.size * 4 / 1e6))
        d.derived?.let { KeyValue(tr("Derived image", "Imagen derivada"), it) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) tr("Hide metadata", "Ocultar metadatos") else tr("Show all metadata (${img.metadata.size})", "Ver todos los metadatos (${img.metadata.size})")) }
        if (expanded) img.metadata.forEach { (k, v) -> Text("$k = ${v.take(300)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp)) }
    }
}
