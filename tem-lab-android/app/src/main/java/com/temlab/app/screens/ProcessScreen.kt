package com.temlab.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.temlab.app.data.AppViewModel
import com.temlab.app.data.Doc
import com.temlab.app.render.Render
import com.temlab.app.ui.AdaptivePane
import com.temlab.app.ui.LocalTr
import com.temlab.app.ui.NumberField
import com.temlab.app.ui.Section
import com.temlab.app.ui.SwitchRow
import com.temlab.app.ui.ViewTransform
import com.temlab.app.ui.ZoomableImage
import com.temlab.app.ui.rememberFileSaver
import com.temlab.core.io.TiffWriter
import com.temlab.core.process.ProcessOp

private data class OpSpec(val en: String, val es: String, val params: List<Triple<String, String, Double>>, val make: (List<Double>) -> ProcessOp)

private val OPS = listOf(
    OpSpec("Gaussian", "Gaussiano", listOf(Triple("σ (px)", "σ (px)", 1.5))) { ProcessOp.Gaussian(it[0]) },
    OpSpec("Median", "Mediana", listOf(Triple("radius (px)", "radio (px)", 1.0))) { ProcessOp.Median(it[0].toInt()) },
    OpSpec("Bilateral", "Bilateral", listOf(Triple("σ spatial", "σ espacial", 2.0), Triple("σ range (0–1)", "σ rango (0–1)", 0.1))) { ProcessOp.Bilateral(it[0], it[1]) },
    OpSpec("Noise reduction (Wiener)", "Reducción de ruido (Wiener)", listOf(Triple("radius", "radio", 2.0))) { ProcessOp.Wiener(it[0].toInt()) },
    OpSpec("Low-pass", "Pasa bajas", listOf(Triple("σ (px)", "σ (px)", 2.0))) { ProcessOp.LowPass(it[0]) },
    OpSpec("High-pass", "Pasa altas", listOf(Triple("σ (px)", "σ (px)", 20.0))) { ProcessOp.HighPass(it[0]) },
    OpSpec("Unsharp mask", "Máscara de enfoque", listOf(Triple("σ (px)", "σ (px)", 2.0), Triple("amount", "cantidad", 0.8))) { ProcessOp.Unsharp(it[0], it[1]) },
    OpSpec("Laplacian", "Laplaciano", emptyList()) { ProcessOp.Laplacian },
    OpSpec("Sobel", "Sobel", emptyList()) { ProcessOp.Sobel },
    OpSpec("Edge detection (LoG)", "Detección de bordes (LoG)", listOf(Triple("σ (px)", "σ (px)", 1.5))) { ProcessOp.LogEdges(it[0]) },
    OpSpec("Normalize", "Normalizar", emptyList()) { ProcessOp.Normalize },
    OpSpec("Invert intensities", "Invertir intensidades", emptyList()) { ProcessOp.Invert },
    OpSpec("Histogram equalisation", "Ecualización del histograma", emptyList()) { ProcessOp.Equalize },
    OpSpec("CLAHE", "CLAHE", listOf(Triple("tiles", "teselas", 8.0), Triple("clip limit", "límite de recorte", 2.0))) { ProcessOp.Clahe(it[0].toInt(), it[1]) },
    OpSpec("Background (Gaussian)", "Fondo (gaussiano)", listOf(Triple("σ (px)", "σ (px)", 50.0))) { ProcessOp.Background(it[0]) },
    OpSpec("Background (plane)", "Fondo (plano)", emptyList()) { ProcessOp.PlaneFit },
    OpSpec("Rotate 90°", "Rotar 90°", listOf(Triple("× 90° (1–3)", "× 90° (1–3)", 1.0))) { ProcessOp.Rotate90(it[0].toInt()) },
    OpSpec("Rotate (free)", "Rotar (libre)", listOf(Triple("angle (°)", "ángulo (°)", 5.0))) { ProcessOp.Rotate(it[0]) },
    OpSpec("Flip horizontal", "Voltear horizontal", emptyList()) { ProcessOp.Flip(true) },
    OpSpec("Flip vertical", "Voltear vertical", emptyList()) { ProcessOp.Flip(false) },
    OpSpec("Crop", "Recortar", listOf(Triple("x", "x", 0.0), Triple("y", "y", 0.0), Triple("width", "ancho", 512.0), Triple("height", "alto", 512.0))) { ProcessOp.Crop(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt()) },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProcessScreen(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    val transform = remember(d.id) { ViewTransform() }
    var spec by remember { mutableStateOf<OpSpec?>(null) }
    var split by remember { mutableStateOf(0.5f) }
    var compare by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    val saver = rememberFileSaver { vm.notify(it) }

    AdaptivePane(image = { mod ->
        Column(mod) {
            if (compare) {
                // Before/after with a split line; both views share the same transform (synchronised zoom).
                androidx.compose.runtime.LaunchedEffect(d.pipeline, d.display) { if (d.previewOriginal == null) vm.refresh(d, rerun = false) }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    DocImage(d, Modifier.fillMaxSize(), transform)
                    Box(Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * split) { this@drawWithContent.drawContent() } }) {
                        ZoomableImage(d.previewOriginal, d.original.width, d.original.height, Modifier.fillMaxSize(), transform,
                            detailRenderer = { x, y, w, h, m -> Render.region(d.original, d.display, x, y, w, h, m) })
                    }
                    Text(tr("◀ original | processed ▶", "◀ original | procesada ▶"), color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).padding(4.dp))
                }
                Slider(split, { split = it }, modifier = Modifier.padding(horizontal = 12.dp))
            } else DocImage(d, Modifier.weight(1f).fillMaxWidth(), transform)
        }
    }) {
        Section(tr("Pipeline (non-destructive)", "Procesamiento (no destructivo)")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.undo(d) }, enabled = d.history.canUndo()) { Icon(Icons.AutoMirrored.Filled.Undo, tr("Undo", "Deshacer")) }
                IconButton(onClick = { vm.redo(d) }, enabled = d.history.canRedo()) { Icon(Icons.AutoMirrored.Filled.Redo, tr("Redo", "Rehacer")) }
                SwitchRow(tr("Before/after", "Antes/después"), compare) { compare = it }
            }
            if (d.pipeline.ops.isEmpty()) Text(tr("Original data (no operations)", "Datos originales (sin operaciones)"), style = MaterialTheme.typography.bodySmall)
            d.pipeline.ops.forEachIndexed { i, op ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${op.label}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.setPipeline(d, com.temlab.core.process.Pipeline(d.pipeline.ops.filterIndexed { j, _ -> j != i })) }) { Icon(Icons.Filled.Close, null) }
                }
            }
            if (d.pipeline.ops.isNotEmpty()) OutlinedButton(onClick = { vm.setPipeline(d, com.temlab.core.process.Pipeline()) }) { Text(tr("Reset to original", "Volver al original")) }
            Text(tr("Analyses (FFT, particles, measurements) use the processed image; calibration is preserved through crops and rotations.",
                "Los análisis (FFT, partículas, mediciones) usan la imagen procesada; la calibración se conserva en recortes y rotaciones."), style = MaterialTheme.typography.bodySmall)
        }
        Section(tr("Add operation", "Añadir operación")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OPS.forEach { s -> AssistChip(onClick = { if (s.params.isEmpty()) vm.applyOp(d, s.make(emptyList())) else spec = s }, label = { Text(tr(s.en, s.es), fontSize = 12.sp) }) }
            }
        }
        Section(tr("Reusable filter settings & batch", "Configuraciones reutilizables y lotes")) {
            OutlinedTextField(presetName, { presetName = it }, label = { Text(tr("Preset name", "Nombre de la configuración")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { if (presetName.isNotBlank()) vm.savePreset(presetName, d.pipeline) }, enabled = d.pipeline.ops.isNotEmpty()) { Text(tr("Save current pipeline", "Guardar procesamiento actual")) }
            vm.filterPresets.toList().forEach { (name, p) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$name: ${p.describe()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.setPipeline(d, p) }) { Text(tr("Apply", "Aplicar")) }
                    TextButton(onClick = { vm.batchApply(p, vm.docs.filter { it.derived == null }) }) { Text(tr("All", "Todas")) }
                    IconButton(onClick = { vm.deletePreset(name) }) { Icon(Icons.Filled.Close, null) }
                }
            }
            OutlinedButton(onClick = { vm.batchApply(d.pipeline, vm.docs.filter { it != d && it.derived == null }) }) { Text(tr("Apply this pipeline to all open images", "Aplicar este procesamiento a todas las imágenes abiertas")) }
        }
        Section(tr("Export", "Exportar")) {
            OutlinedButton(onClick = { saver.save("${d.title}_processed.tif", "image/tiff") { TiffWriter.writeFloat(d.processed, it, d.pipeline.describe()) } }) { Text(tr("Processed data as 32-bit TIFF (calibrated)", "Datos procesados como TIFF 32 bits (calibrado)")) }
        }
    }

    spec?.let { s ->
        val values = remember(s) { s.params.map { mutableStateOf(it.third) } }
        AlertDialog(onDismissRequest = { spec = null }, title = { Text(tr(s.en, s.es)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { s.params.forEachIndexed { i, p -> NumberField(tr(p.first, p.second), values[i].value) { values[i].value = it } } }
        }, confirmButton = { TextButton(onClick = { vm.applyOp(d, s.make(values.map { it.value })); spec = null }) { Text(tr("Apply", "Aplicar")) } },
            dismissButton = { TextButton(onClick = { spec = null }) { Text(tr("Cancel", "Cancelar")) } })
    }
}

