package com.temlab.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.temlab.app.data.AppViewModel
import com.temlab.app.data.Doc
import com.temlab.app.render.Figures
import com.temlab.app.render.ParticleOverlay
import com.temlab.app.ui.AdaptivePane
import com.temlab.app.ui.ChipRow
import com.temlab.app.ui.KeyValue
import com.temlab.app.ui.LabeledSlider
import com.temlab.app.ui.LocalTr
import com.temlab.app.ui.NumberField
import com.temlab.app.ui.Section
import com.temlab.app.ui.SwitchRow
import com.temlab.app.ui.ToolHandler
import com.temlab.app.ui.ViewTransform
import com.temlab.app.ui.particlesOverlay
import com.temlab.app.ui.poly
import com.temlab.app.ui.rememberFileSaver
import com.temlab.core.export.CsvWriter
import com.temlab.core.export.Tables
import com.temlab.core.export.XlsxWriter
import com.temlab.core.particles.ParticleFilter
import com.temlab.core.particles.ParticleVariable
import com.temlab.core.segmentation.Polarity
import com.temlab.core.segmentation.Segmenter
import com.temlab.core.segmentation.ThresholdMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

enum class PTool(val en: String, val es: String) {
    SELECT("Select", "Seleccionar"), DELETE("Delete", "Eliminar"), EXCLUDE("Exclude/include", "Excluir/incluir"),
    ADD("Add (freehand)", "Añadir (mano alzada)"), EDIT("Redraw contour", "Redibujar contorno"), MERGE("Merge", "Fusionar"), SPLIT("Split (cut line)", "Dividir (línea de corte)"),
}

@Composable
fun ParticlesScreen(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    val img = d.processed
    val transform = remember(d.id) { ViewTransform() }
    var tool by remember { mutableStateOf(PTool.SELECT) }
    val selected = remember { mutableStateListOf<Int>() }
    val stroke = remember { mutableStateListOf<Offset>() }
    var numbers by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf(false) }
    var maskBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var p by remember(d.id) { mutableStateOf(d.segParams) }
    var f by remember(d.id) { mutableStateOf(d.particleFilter) }
    val (mn, mx) = remember(img) { img.minMax() }
    val saver = rememberFileSaver { vm.notify(it) }
    val set = d.particles
    @Suppress("UNUSED_VARIABLE") val rev = d.particleRevision // recomposition trigger

    LaunchedEffect(p, preview, img) {
        if (!preview) { maskBmp = null; return@LaunchedEffect }
        delay(200)
        maskBmp = withContext(Dispatchers.Default) {
            val (m, _) = Segmenter.mask(img, p)
            val px = IntArray(m.bits.size) { if (m.bits[it]) 0x66FF3030 else 0 }
            android.graphics.Bitmap.createBitmap(px, img.width, img.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    fun changed() { vm.particlesChanged(d) }
    val handler = when (tool) {
        PTool.ADD, PTool.EDIT, PTool.SPLIT -> ToolHandler(wantsDrag = true, magnifier = true,
            onDragStart = { o -> stroke.clear(); stroke += o; if (tool == PTool.SPLIT) stroke += o },
            onDrag = { o -> if (tool == PTool.SPLIT) stroke[1] = o else stroke += o },
            onDragEnd = {
                val s = set
                if (s != null && stroke.size >= 2) {
                    val pts = stroke.map { it.x.toDouble() to it.y.toDouble() }
                    when (tool) {
                        PTool.ADD -> s.addPolygon(pts)?.let { selected.clear(); selected += it.id }
                        PTool.EDIT -> selected.firstOrNull()?.let { s.replaceContour(it, pts) }
                        PTool.SPLIT -> {
                            val mid = (stroke[0] + stroke[1]) / 2f
                            val target = selected.firstOrNull() ?: s.particleAt(mid.x.toInt(), mid.y.toInt())?.id
                            target?.let { s.split(it, pts[0].first, pts[0].second, pts[1].first, pts[1].second) }
                        }
                        else -> {}
                    }
                    changed()
                }
                stroke.clear()
            })
        else -> ToolHandler(onTap = { o ->
            val s = set ?: return@ToolHandler
            val hit = s.particleAt(o.x.toInt(), o.y.toInt())
            when (tool) {
                PTool.SELECT -> { selected.clear(); hit?.let { selected += it.id } }
                PTool.DELETE -> hit?.let { s.delete(it.id); selected.remove(it.id); changed() }
                PTool.EXCLUDE -> hit?.let { s.setExcluded(it.id, !it.excluded); changed() }
                PTool.MERGE -> hit?.let { if (it.id in selected) selected.remove(it.id) else selected += it.id }
                else -> {}
            }
        })
    }

    AdaptivePane(image = { mod ->
        DocImage(d, mod, transform, handler) { s ->
            maskBmp?.let { drawImage(it, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero, IntSize(img.width, img.height)) }
            set?.let { particlesOverlay(it, s, selected.toSet(), numbers) }
            if (stroke.isNotEmpty()) poly(stroke.toList(), s, Color(0xFFFFD54F), closed = tool != PTool.SPLIT)
        }
    }) {
        Section(tr("Segmentation", "Segmentación")) {
            ChipRow(ThresholdMethod.entries, p.method, { when (it) {
                ThresholdMethod.MANUAL -> tr("Manual", "Manual"); ThresholdMethod.OTSU -> "Otsu"; ThresholdMethod.ADAPTIVE -> tr("Adaptive", "Adaptativa")
                ThresholdMethod.EDGES -> tr("Contours", "Contornos"); ThresholdMethod.INTENSITY_RANGE -> tr("Intensity range", "Rango de intensidad") } }) { p = p.copy(method = it) }
            ChipRow(Polarity.entries, p.polarity, { if (it == Polarity.DARK_OBJECTS) tr("Dark particles (TEM BF)", "Partículas oscuras (TEM CC)") else tr("Bright particles (HAADF/SEM)", "Partículas claras (HAADF/SEM)") }) { p = p.copy(polarity = it) }
            when (p.method) {
                ThresholdMethod.MANUAL -> LabeledSlider(tr("Threshold", "Umbral"), (if (p.manualThreshold.isNaN()) (mn + mx) / 2.0 else p.manualThreshold).toFloat(), mn..mx, { p = p.copy(manualThreshold = it.toDouble()) }, format = { String.format(Locale.US, "%.4g", it) })
                ThresholdMethod.ADAPTIVE -> {
                    LabeledSlider(tr("Window radius (px)", "Radio de ventana (px)"), p.adaptiveRadius.toFloat(), 3f..200f, { p = p.copy(adaptiveRadius = it.toInt()) }, format = { "${it.toInt()}" })
                    LabeledSlider(tr("Offset (× global σ)", "Desplazamiento (× σ global)"), p.adaptiveOffset.toFloat(), 0f..2f, { p = p.copy(adaptiveOffset = it.toDouble()) })
                }
                ThresholdMethod.INTENSITY_RANGE -> {
                    LabeledSlider(tr("Low", "Inferior"), (if (p.rangeLow.isNaN()) mn.toDouble() else p.rangeLow).toFloat(), mn..mx, { p = p.copy(rangeLow = it.toDouble()) }, format = { String.format(Locale.US, "%.4g", it) })
                    LabeledSlider(tr("High", "Superior"), (if (p.rangeHigh.isNaN()) mx.toDouble() else p.rangeHigh).toFloat(), mn..mx, { p = p.copy(rangeHigh = it.toDouble()) }, format = { String.format(Locale.US, "%.4g", it) })
                }
                else -> {}
            }
            LabeledSlider(tr("Pre-smoothing σ (px)", "Suavizado previo σ (px)"), p.preSmoothSigma.toFloat(), 0f..5f, { p = p.copy(preSmoothSigma = it.toDouble()) })
            LabeledSlider(tr("Opening radius (px)", "Radio de apertura (px)"), p.openRadius.toFloat(), 0f..6f, { p = p.copy(openRadius = it.toInt()) }, steps = 5, format = { "${it.toInt()}" })
            LabeledSlider(tr("Closing radius (px)", "Radio de cierre (px)"), p.closeRadius.toFloat(), 0f..6f, { p = p.copy(closeRadius = it.toInt()) }, steps = 5, format = { "${it.toInt()}" })
            SwitchRow(tr("Fill holes", "Rellenar huecos"), p.fillHoles) { p = p.copy(fillHoles = it) }
            SwitchRow(tr("Watershed (separate touching particles)", "Watershed (separar partículas en contacto)"), p.watershed) { p = p.copy(watershed = it) }
            if (p.watershed) LabeledSlider(tr("Minimum particle radius for seeds (px)", "Radio mínimo de semilla (px)"), p.watershedMinDistance.toFloat(), 1f..30f, { p = p.copy(watershedMinDistance = it.toDouble()) }, format = { String.format(Locale.US, "%.1f", it) })
            SwitchRow(tr("Preview mask", "Vista previa de la máscara"), preview) { preview = it }
            Button(onClick = { vm.segment(d, p, f) }) { Text(tr("Detect particles", "Detectar partículas")) }
        }
        val u = if (img.calibration.isCalibrated) img.calibration.unitLabel else "px"
        Section(tr("Filters", "Filtros")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(tr("Min area ($u²)", "Área mín. ($u²)"), f.minArea, Modifier.weight(1f)) { f = f.copy(minArea = it) }
                NumberField(tr("Max area ($u²)", "Área máx. ($u²)"), if (f.maxArea == Double.MAX_VALUE) Double.NaN else f.maxArea, Modifier.weight(1f)) { f = f.copy(maxArea = if (it <= 0) Double.MAX_VALUE else it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField(tr("Min circularity", "Circularidad mín."), f.minCircularity, Modifier.weight(1f)) { f = f.copy(minCircularity = it) }
                NumberField(tr("Max aspect ratio", "Relación de aspecto máx."), if (f.maxAspect == Double.MAX_VALUE) Double.NaN else f.maxAspect, Modifier.weight(1f)) { f = f.copy(maxAspect = if (it <= 0) Double.MAX_VALUE else it) }
            }
            SwitchRow(tr("Exclude particles touching the border", "Excluir partículas en los bordes"), f.excludeBorder) { f = f.copy(excludeBorder = it) }
            OutlinedButton(onClick = { set?.setFilter(f); d.particleFilter = f; changed() }, enabled = set != null) { Text(tr("Apply filters", "Aplicar filtros")) }
        }
        if (set != null) {
            Section(tr("Manual correction", "Corrección manual")) {
                ChipRow(PTool.entries, tool, { tr(it.en, it.es) }) { tool = it; if (it != PTool.EDIT && it != PTool.SPLIT) selected.clear() }
                when (tool) {
                    PTool.MERGE -> Button(onClick = { set.merge(selected.toList())?.let { selected.clear(); selected += it.id }; changed() }, enabled = selected.size >= 2) { Text(tr("Merge ${selected.size} selected", "Fusionar ${selected.size} seleccionadas")) }
                    PTool.EDIT -> Text(tr("Select a particle with “Select”, then switch to this tool and redraw its outline with the finger or stylus.", "Seleccione una partícula con “Seleccionar” y luego redibuje su contorno con el dedo o lápiz."), style = MaterialTheme.typography.bodySmall)
                    PTool.SPLIT -> Text(tr("Draw a straight cut line across the particle.", "Dibuje una línea de corte recta a través de la partícula."), style = MaterialTheme.typography.bodySmall)
                    else -> {}
                }
                SwitchRow(tr("Show ID numbers", "Mostrar números"), numbers) { numbers = it }
                KeyValue(tr("Included / total", "Incluidas / total"), "${set.included.size} / ${set.particles.size}")
                if (!set.threshold.isNaN()) KeyValue(tr("Threshold used", "Umbral usado"), String.format(Locale.US, "%.5g", set.threshold))
            }
            selected.firstOrNull()?.let { id -> set.byId(id)?.measurement?.let { m ->
                Section(tr("Particle #$id", "Partícula #$id")) {
                    KeyValue(tr("Area", "Área"), String.format(Locale.US, "%.5g %s²", m.area, u))
                    KeyValue(tr("Perimeter", "Perímetro"), String.format(Locale.US, "%.5g %s", m.perimeter, u))
                    KeyValue(tr("Equivalent circular diameter", "Diámetro circular equivalente"), String.format(Locale.US, "%.5g %s", m.ecd, u))
                    KeyValue("Feret max / min", String.format(Locale.US, "%.5g / %.5g %s", m.feretMax, m.feretMin, u))
                    KeyValue(tr("Major / minor axis", "Eje mayor / menor"), String.format(Locale.US, "%.5g / %.5g %s", m.majorAxis, m.minorAxis, u))
                    KeyValue(tr("Aspect ratio · circularity", "Relación de aspecto · circularidad"), String.format(Locale.US, "%.3f · %.3f", m.aspectRatio, m.circularity))
                    KeyValue(tr("Centroid", "Centroide"), String.format(Locale.US, "(%.4g, %.4g) %s", m.centroidX, m.centroidY, u))
                    KeyValue(tr("Intensity mean ± SD", "Intensidad media ± DE"), String.format(Locale.US, "%.5g ± %.4g", m.meanIntensity, m.stdIntensity))
                    Row { TextButton(onClick = { set.setExcluded(id, !(set.byId(id)?.excluded ?: false)); changed() }) { Text(tr("Toggle exclusion", "Alternar exclusión")) }
                        TextButton(onClick = { set.delete(id); selected.clear(); changed() }) { Text(tr("Delete", "Eliminar")) } }
                }
            } }
            Section(tr("Results table", "Tabla de resultados")) {
                Text(String.format(Locale.US, "%-5s %-9s %-9s %-9s %-6s", "ID", "ECD", "Feret", tr("Area", "Área"), "Circ."), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                set.particles.take(300).forEach { q -> q.measurement?.let { m ->
                    TextButton(onClick = { selected.clear(); selected += q.id }, modifier = Modifier.fillMaxWidth()) {
                        Text(String.format(Locale.US, "%-5d %-9.4g %-9.4g %-9.4g %-6.3f%s", q.id, m.ecd, m.feretMax, m.area, m.circularity, if (!q.included) " ✗" else ""),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (q.included) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                    }
                } }
                if (set.particles.size > 300) Text(tr("… ${set.particles.size - 300} more in the export", "… ${set.particles.size - 300} más en la exportación"), style = MaterialTheme.typography.bodySmall)
            }
            Section(tr("Export", "Exportar")) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { saver.save("${d.title}_particles.csv", "text/csv") { CsvWriter.write(Tables.particles(set), it) } }) { Text("CSV") }
                    OutlinedButton(onClick = {
                        saver.save("${d.title}_particles.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") {
                            XlsxWriter.write(listOf(Tables.particles(set), Tables.statistics(set.values(ParticleVariable.ECD), "ECD", u)), it)
                        }
                    }) { Text("XLSX") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { saver.save("${d.title}_numbered.png", "image/png") { Figures.annotatedImage(d, annotations = false, particles = ParticleOverlay.NUMBERED, measurements = false).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }) { Text(tr("Numbered PNG", "PNG numerado"), fontSize = 12.sp) }
                    OutlinedButton(onClick = { saver.save("${d.title}_contours.png", "image/png") { Figures.annotatedImage(d, annotations = false, particles = ParticleOverlay.CONTOURS, measurements = false).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }) { Text(tr("Contours PNG", "PNG contornos"), fontSize = 12.sp) }
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") ParticleFilter()
}
