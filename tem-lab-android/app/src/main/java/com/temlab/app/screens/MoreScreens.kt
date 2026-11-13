package com.temlab.app.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.temlab.app.data.AppViewModel
import com.temlab.app.data.Doc
import com.temlab.app.render.Figures
import com.temlab.app.render.ParticleOverlay
import com.temlab.app.render.ReportInfo
import com.temlab.app.render.ReportSection
import com.temlab.app.render.ReportWriter
import com.temlab.app.render.RenderedTile
import com.temlab.app.render.Render
import com.temlab.app.ui.AdaptivePane
import com.temlab.app.ui.ChipRow
import com.temlab.app.ui.EmptyHint
import com.temlab.app.ui.LabeledSlider
import com.temlab.app.ui.LocalTr
import com.temlab.app.ui.Section
import com.temlab.app.ui.SwitchRow
import com.temlab.app.ui.ToolHandler
import com.temlab.app.ui.ViewTransform
import com.temlab.app.ui.ZoomableImage
import com.temlab.app.ui.annotationOverlay
import com.temlab.app.ui.measurementOverlay
import com.temlab.app.ui.particlesOverlay
import com.temlab.app.ui.rememberFileSaver
import com.temlab.core.fft.SpectrumScale
import com.temlab.core.image.LengthUnit
import com.temlab.core.measure.Pt
import com.temlab.core.particles.ParticleVariable
import com.temlab.core.project.Annotation
import com.temlab.core.project.AnnotationType
import com.temlab.core.project.ReportTemplate
import com.temlab.core.stats.Statistics
import java.util.Locale

private val COLORS = listOf(0xFFFFFF00, 0xFFFFFFFF, 0xFF000000, 0xFFFF5252, 0xFF5EC8F2, 0xFF69F0AE, 0xFFFF9800)

@Composable
fun AnnotateScreen(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    val transform = remember(d.id) { ViewTransform() }
    var type by remember { mutableStateOf(AnnotationType.ARROW) }
    var color by remember { mutableStateOf(0xFFFFFF00) }
    var width by remember { mutableStateOf(4f) }
    var font by remember { mutableStateOf(40f) }
    var text by remember { mutableStateOf("") }
    var layer by remember { mutableStateOf("Annotations") }
    var draft by remember { mutableStateOf<Annotation?>(null) }
    var showParticles by remember { mutableStateOf(false) }
    val saver = rememberFileSaver { vm.notify(it) }
    val textTypes = setOf(AnnotationType.LABEL, AnnotationType.HKL, AnnotationType.DSPACING, AnnotationType.PARTICLE_NUMBER)
    fun nextId() = (d.annotations.maxOfOrNull { it.id } ?: 0) + 1

    val handler = if (type in textTypes) ToolHandler(onTap = { p ->
        if (text.isNotBlank()) d.annotations += Annotation(nextId(), type, listOf(Pt(p.x.toDouble(), p.y.toDouble())), text, color, width, font, layer)
    }) else ToolHandler(wantsDrag = true, magnifier = true,
        onDragStart = { p -> draft = Annotation(nextId(), type, listOf(Pt(p.x.toDouble(), p.y.toDouble()), Pt(p.x.toDouble(), p.y.toDouble())), text, color, width, font, layer) },
        onDrag = { p -> draft = draft?.let { it.copy(points = listOf(it.points[0], Pt(p.x.toDouble(), p.y.toDouble()))) } },
        onDragEnd = { draft?.let { d.annotations += it }; draft = null })

    AdaptivePane(image = { mod ->
        DocImage(d, mod, transform, handler) { s ->
            if (showParticles) d.particles?.let { particlesOverlay(it, s, emptySet(), true) }
            d.measurements.forEach { measurementOverlay(it, s) }
            d.annotations.forEach { annotationOverlay(it, 1f) }
            draft?.let { annotationOverlay(it, 1f) }
        }
    }) {
        Section(tr("Element", "Elemento")) {
            ChipRow(AnnotationType.entries.filter { it != AnnotationType.SCALE_BAR && it != AnnotationType.ROI }, type, { tr(enName(it), esName(it)) }) { type = it }
            OutlinedTextField(text, { text = it }, label = { Text(if (type in textTypes) tr("Text (tap the image to place)", "Texto (toque la imagen para colocarlo)") else tr("Optional label", "Etiqueta opcional")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (type == AnnotationType.HKL) Text(tr("Use an overbar for negative indices, e.g. (1-11) → write 1̄ with a combining overline or “-1”.", "Para índices negativos escriba p. ej. (1-11)."), style = MaterialTheme.typography.bodySmall)
            ChipRow(COLORS, color, { "●" }) { color = it }
            LabeledSlider(tr("Line width (px)", "Grosor de línea (px)"), width, 1f..20f, { width = it }, format = { "${it.toInt()}" })
            LabeledSlider(tr("Font size (px)", "Tamaño de letra (px)"), font, 10f..200f, { font = it }, format = { "${it.toInt()}" })
            OutlinedTextField(layer, { layer = it }, label = { Text(tr("Layer", "Capa")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SwitchRow(tr("Show particle numbers", "Mostrar numeración de partículas"), showParticles) { showParticles = it }
        }
        Section(tr("Layers", "Capas")) {
            d.annotations.groupBy { it.layer }.forEach { (ly, items) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$ly (${items.size})", modifier = Modifier.weight(1f))
                    val vis = items.all { it.visible }
                    IconButton(onClick = { val ids = items.map { it.id }.toSet(); val upd = d.annotations.map { if (it.id in ids) it.copy(visible = !vis) else it }; d.annotations.clear(); d.annotations += upd }) { Icon(if (vis) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, null) }
                }
                items.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("  #${a.id} ${a.type.name.lowercase()} ${a.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { d.annotations.remove(a) }) { Icon(Icons.Filled.Delete, null) }
                    }
                }
            }
        }
        ScaleBarSection(d)
        Section(tr("Export figure (full resolution)", "Exportar figura (resolución completa)")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { saver.save("${d.title}_figure.png", "image/png") { Figures.annotatedImage(d, true, if (showParticles) ParticleOverlay.NUMBERED else ParticleOverlay.NONE).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }) { Text("PNG") }
                OutlinedButton(onClick = { saver.save("${d.title}_figure.jpg", "image/jpeg") { Figures.annotatedImage(d, true, if (showParticles) ParticleOverlay.NUMBERED else ParticleOverlay.NONE).compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) } }) { Text("JPG") }
            }
            Text(tr("Exported at the native pixel size of the micrograph (${d.processed.width}×${d.processed.height}), without resampling.", "Se exporta al tamaño nativo de la micrografía (${d.processed.width}×${d.processed.height}), sin remuestreo."), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun enName(t: AnnotationType) = when (t) { AnnotationType.ARROW -> "Arrow"; AnnotationType.LINE -> "Line"; AnnotationType.CIRCLE -> "Circle"; AnnotationType.RECTANGLE -> "Rectangle"; AnnotationType.SQUARE -> "Square"; AnnotationType.LABEL -> "Label"; AnnotationType.HKL -> "hkl index"; AnnotationType.DSPACING -> "d-spacing"; AnnotationType.ANGLE -> "Angle"; AnnotationType.PARTICLE_NUMBER -> "Number"; else -> t.name }
private fun esName(t: AnnotationType) = when (t) { AnnotationType.ARROW -> "Flecha"; AnnotationType.LINE -> "Línea"; AnnotationType.CIRCLE -> "Círculo"; AnnotationType.RECTANGLE -> "Rectángulo"; AnnotationType.SQUARE -> "Cuadrado"; AnnotationType.LABEL -> "Etiqueta"; AnnotationType.HKL -> "Índice hkl"; AnnotationType.DSPACING -> "Distancia d"; AnnotationType.ANGLE -> "Ángulo"; AnnotationType.PARTICLE_NUMBER -> "Número"; else -> t.name }

enum class CompareKind(val en: String, val es: String) { IMAGES("Images", "Imágenes"), FFT("FFTs", "FFT"), HISTOGRAMS("Histograms", "Histogramas") }

@Composable
fun CompareScreen(vm: AppViewModel) {
    val tr = LocalTr.current
    val chosen = remember { mutableStateListOf<Long>() }
    var sync by remember { mutableStateOf(true) }
    var kind by remember { mutableStateOf(CompareKind.IMAGES) }
    if (chosen.isEmpty()) vm.docs.take(2).forEach { chosen += it.id }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPanels = if (maxWidth >= 600.dp) 4 else 2
        val docs = vm.docs.filter { it.id in chosen }.take(maxPanels)
        val transforms = remember(docs.map { it.id }) { docs.map { ViewTransform() } }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ChipRow(CompareKind.entries, kind, { tr(it.en, it.es) }) { kind = it }
                SwitchRow(tr("Sync zoom", "Zoom sincronizado"), sync) { sync = it }
            }
            ChipRow(vm.docs.toList(), null, { (if (it.id in chosen) "✓ " else "") + it.title }) { d -> if (d.id in chosen) chosen.remove(d.id) else if (chosen.size < maxPanels) chosen += d.id }
            Text(tr("Up to $maxPanels panels on this screen; each image keeps its own calibration.", "Hasta $maxPanels paneles en esta pantalla; cada imagen conserva su calibración."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
            val rows = if (docs.size > 2) 2 else 1
            val cols = if (docs.size >= 2) 2 else 1
            Column(Modifier.weight(1f)) {
                for (r in 0 until rows) Row(Modifier.weight(1f)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        val d = docs.getOrNull(i)
                        Box(Modifier.weight(1f).fillMaxSize().padding(1.dp)) {
                            if (d != null) {
                                val t = transforms[i]
                                val onChange = { if (sync) transforms.forEach { if (it !== t) it.copyFrom(t) } }
                                when (kind) {
                                    CompareKind.IMAGES -> DocImage(d, Modifier.fillMaxSize(), t, onTransformChanged = onChange)
                                    CompareKind.FFT -> {
                                        val s = d.activeFft
                                        val tile = remember(s) { s?.let { RenderedTile(Figures.fftFigure(it, true, SpectrumScale.AMPLITUDE, 1.0, LengthUnit.NM).asImageBitmap(), 0, 0, it.result.n, it.result.n) } }
                                        if (s != null) ZoomableImage(tile, s.result.n, s.result.n, Modifier.fillMaxSize(), t, onTransformChanged = onChange)
                                        else EmptyHint(tr("No FFT", "Sin FFT"))
                                    }
                                    CompareKind.HISTOGRAMS -> {
                                        val v = d.particles?.values(ParticleVariable.ECD)
                                        if (v == null || v.isEmpty()) EmptyHint(tr("No particles", "Sin partículas"))
                                        else {
                                            val ds = Statistics.describe(v)
                                            val bmp = remember(d.particleRevision) { Figures.histogram(Statistics.histogram(v, Statistics.suggestedBins(v)), d.title, "ECD (${if (d.processed.calibration.isCalibrated) d.processed.calibration.unitLabel else "px"})", false, null, ds.mean, ds.median, ds.n, 900, 600).asImageBitmap() }
                                            Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                        }
                                    }
                                }
                                Text(d.title + if (d.processed.calibration.isCalibrated) String.format(Locale.US, " · %.4g %s/px", d.processed.calibration.pixelWidth, d.processed.calibration.unitLabel) else " · px",
                                    color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") Offset.Zero
}

@Composable
fun ReportScreen(vm: AppViewModel) {
    val tr = LocalTr.current
    val sections = remember { mutableStateListOf(*ReportSection.entries.toTypedArray()) }
    val included = remember { mutableStateListOf<Long>().also { l -> vm.docs.forEach { l += it.id } } }
    var title by remember { mutableStateOf(vm.projectName) }
    var unit by remember { mutableStateOf(LengthUnit.NM) }
    var variable by remember { mutableStateOf(ParticleVariable.ECD) }
    var bins by remember { mutableStateOf(15f) }
    var templateName by remember { mutableStateOf("") }
    val saver = rememberFileSaver { vm.notify(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section(tr("Report", "Reporte")) {
            OutlinedTextField(title, { title = it }, label = { Text(tr("Title", "Título")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.projectSample, { vm.projectSample = it }, label = { Text(tr("Sample", "Muestra")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.projectOperator, { vm.projectOperator = it }, label = { Text(tr("Operator", "Operador")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.projectDescription, { vm.projectDescription = it }, label = { Text(tr("Description", "Descripción")) }, modifier = Modifier.fillMaxWidth())
        }
        Section(tr("Content", "Contenido")) {
            ReportSection.entries.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s in sections, { if (it) sections += s else sections.remove(s) })
                    Text(tr(s.en, s.es))
                }
            }
            Text(tr("d-spacing unit", "Unidad de distancias d"), style = MaterialTheme.typography.bodySmall)
            ChipRow(listOf(LengthUnit.NM, LengthUnit.ANGSTROM), unit, { it.symbol }) { unit = it }
            Text(tr("Histogram variable", "Variable del histograma"), style = MaterialTheme.typography.bodySmall)
            ChipRow(ParticleVariable.entries, variable, { it.label }) { variable = it }
            LabeledSlider(tr("Histogram bins", "Intervalos del histograma"), bins, 3f..60f, { bins = it }, format = { "${it.toInt()}" })
        }
        Section(tr("Micrographs", "Micrografías")) {
            vm.docs.forEach { d -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(d.id in included, { if (it) included += d.id else included.remove(d.id) }); Text(d.title + (d.derived?.let { " [${tr("filtered", "filtrada")}]" } ?: "")) } }
        }
        Section(tr("Templates", "Plantillas")) {
            OutlinedTextField(templateName, { templateName = it }, label = { Text(tr("Template name", "Nombre de la plantilla")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { if (templateName.isNotBlank()) { vm.reportTemplates.removeAll { it.name == templateName }; vm.reportTemplates += ReportTemplate(templateName, sections.map { it.name }) } }) { Text(tr("Save selection as template", "Guardar selección como plantilla")) }
            vm.reportTemplates.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.name, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { sections.clear(); sections += t.sections.mapNotNull { n -> ReportSection.entries.firstOrNull { it.name == n } } }) { Text(tr("Use", "Usar")) }
                    IconButton(onClick = { vm.reportTemplates.remove(t) }) { Icon(Icons.Filled.Delete, null) }
                }
            }
            Text(tr("Templates are stored with the project.", "Las plantillas se guardan con el proyecto."), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            saver.save("${title.ifBlank { "report" }}.pdf", "application/pdf") { out ->
                val w = ReportWriter(ReportInfo(title, vm.projectSample, vm.projectOperator, vm.projectDescription, tr.spanish))
                w.build(vm.docs.filter { it.id in included }, sections.toSet(), unit, variable, bins.toInt())
                w.write(out)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(tr("Generate PDF report", "Generar reporte PDF")) }
    }
    @Suppress("UNUSED_EXPRESSION") Render
}

