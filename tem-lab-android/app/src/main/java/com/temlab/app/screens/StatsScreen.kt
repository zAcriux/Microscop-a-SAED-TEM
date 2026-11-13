package com.temlab.app.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.temlab.app.data.AppViewModel
import com.temlab.app.data.Doc
import com.temlab.app.render.Figures
import com.temlab.app.ui.AdaptivePane
import com.temlab.app.ui.ChipRow
import com.temlab.app.ui.EmptyHint
import com.temlab.app.ui.KeyValue
import com.temlab.app.ui.LabeledSlider
import com.temlab.app.ui.LocalTr
import com.temlab.app.ui.NumberField
import com.temlab.app.ui.Section
import com.temlab.app.ui.SwitchRow
import com.temlab.app.ui.rememberFileSaver
import com.temlab.core.export.CsvWriter
import com.temlab.core.export.SvgHistogram
import com.temlab.core.export.Table
import com.temlab.core.export.Tables
import com.temlab.core.export.XlsxWriter
import com.temlab.core.measure.MeasureKind
import com.temlab.core.particles.ParticleVariable
import com.temlab.core.stats.HistogramBins
import com.temlab.core.stats.Statistics
import java.util.Locale

private val OVERLAY_COLORS = listOf(0xFFE45756.toInt(), 0xFF54A24B.toInt(), 0xFFB279A2.toInt(), 0xFFF58518.toInt())

/** Values of [v] for a document: included particles + (optionally) manual diameters/radii flagged for the distribution. */
fun distributionValues(d: Doc, v: ParticleVariable, includeManual: Boolean): Pair<DoubleArray, Int> {
    val auto = d.particles?.values(v) ?: DoubleArray(0)
    if (!includeManual || !(v == ParticleVariable.ECD || v == ParticleVariable.FERET_MAX || v == ParticleVariable.MAJOR)) return auto to 0
    val manual = d.measurements.filter { it.includeInDistribution && it.linkedParticle == null && (it.kind == MeasureKind.DIAMETER || it.kind == MeasureKind.RADIUS) }
        .map { if (it.kind == MeasureKind.RADIUS) 2 * it.value else it.value }
    return (auto + manual.toDoubleArray()) to manual.size
}

@Composable
fun StatsScreen(vm: AppViewModel, d: Doc) {
    val tr = LocalTr.current
    @Suppress("UNUSED_VARIABLE") val rev = d.particleRevision
    var variable by remember { mutableStateOf(ParticleVariable.ECD) }
    var bins by remember { mutableStateOf(0f) }
    var width by remember { mutableStateOf(Double.NaN) }
    var relative by remember { mutableStateOf(false) }
    var lognormal by remember { mutableStateOf(true) }
    var includeManual by remember { mutableStateOf(true) }
    var title by remember(d.id) { mutableStateOf(d.title) }
    var xLabelCustom by remember { mutableStateOf("") }
    val compare = remember { mutableStateListOf<Long>() }
    val saver = rememberFileSaver { vm.notify(it) }
    val img = d.processed
    val unit = when {
        variable.isLength -> if (img.calibration.isCalibrated) img.calibration.unitLabel else "px"
        variable.isArea -> if (img.calibration.isCalibrated) img.calibration.unitLabel + "²" else "px²"
        else -> ""
    }
    val (values, nManual) = distributionValues(d, variable, includeManual)
    val nb = if (bins < 1f) Statistics.suggestedBins(values) else bins.toInt()
    val hist: HistogramBins = if (!width.isNaN() && width > 0) Statistics.histogramByWidth(values, width) else Statistics.histogram(values, nb)
    val desc = Statistics.describe(values)
    val fit = if (lognormal && variable != ParticleVariable.CIRCULARITY) Statistics.lognormal(values) else null
    val xLabel = xLabelCustom.ifBlank { "${variable.label}" + if (unit.isNotEmpty()) " ($unit)" else "" }
    val others = vm.docs.filter { it != d && it.id in compare && it.particles != null }
    val overlays = others.mapIndexed { i, o ->
        val ov = distributionValues(o, variable, includeManual).first
        val h = Statistics.histogramByWidth(ov, hist.width, hist.start)
        h to OVERLAY_COLORS[i % OVERLAY_COLORS.size]
    }
    val bmp = remember(hist, relative, fit, title, xLabel, overlays.size, rev) {
        Figures.histogram(hist, title, xLabel, relative, fit?.takeIf { lognormal }, desc.mean, desc.median, desc.n, 1200, 780, overlays).asImageBitmap()
    }

    AdaptivePane(image = { mod ->
        Box(mod.padding(8.dp), contentAlignment = Alignment.Center) {
            if (values.isEmpty()) EmptyHint(tr("Detect particles first (Particles tab) or add manual diameters.", "Primero detecte partículas (pestaña Partículas) o añada diámetros manuales."))
            else Image(bmp, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
        }
    }) {
        Section(tr("Histogram", "Histograma")) {
            ChipRow(ParticleVariable.entries, variable, { it.label }) { variable = it }
            LabeledSlider(tr("Number of bins (0 = automatic Freedman–Diaconis)", "Número de intervalos (0 = automático Freedman–Diaconis)"), bins, 0f..60f, { bins = it; width = Double.NaN }, steps = 59, format = { if (it < 1) "auto ($nb)" else "${it.toInt()}" })
            NumberField(tr("Bin width ($unit) – overrides number", "Ancho de intervalo ($unit) – reemplaza al número"), width) { width = it }
            SwitchRow(tr("Relative frequency", "Frecuencia relativa"), relative) { relative = it }
            SwitchRow(tr("Lognormal fit", "Ajuste lognormal"), lognormal) { lognormal = it }
            SwitchRow(tr("Include manual diameters (non-duplicated)", "Incluir diámetros manuales (no duplicados)"), includeManual) { includeManual = it }
            OutlinedTextField(title, { title = it }, label = { Text(tr("Title", "Título")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(xLabelCustom, { xLabelCustom = it }, label = { Text(tr("X axis label (empty = automatic)", "Etiqueta eje X (vacío = automática)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Section(tr("Descriptive statistics", "Estadística descriptiva")) {
            fun f(v: Double) = String.format(Locale.US, "%.4g %s", v, unit)
            KeyValue("N", "${desc.n}" + if (nManual > 0) tr(" ($nManual manual)", " ($nManual manuales)") else "")
            KeyValue(tr("Mean ± SD", "Media ± DE"), String.format(Locale.US, "%.4g ± %.3g %s", desc.mean, desc.sd, unit))
            KeyValue(tr("Median", "Mediana"), f(desc.median))
            KeyValue("SEM · CV", String.format(Locale.US, "%.3g · %.1f %%", desc.sem, desc.cv * 100))
            KeyValue(tr("Min – Max", "Mín – Máx"), String.format(Locale.US, "%.4g – %.4g %s", desc.min, desc.max, unit))
            KeyValue("P10 · P25 · P75 · P90", String.format(Locale.US, "%.4g · %.4g · %.4g · %.4g", desc.p10, desc.p25, desc.p75, desc.p90))
            fit?.let {
                KeyValue(tr("Lognormal μ, σ", "Lognormal μ, σ"), String.format(Locale.US, "%.4f, %.4f", it.mu, it.sigma))
                KeyValue(tr("Lognormal median / mean / mode", "Mediana / media / moda lognormal"), String.format(Locale.US, "%.4g / %.4g / %.4g", it.median, it.mean, it.mode))
                KeyValue(tr("Kolmogorov–Smirnov", "Kolmogórov–Smirnov"), String.format(Locale.US, "D=%.3f, p≈%.3f %s", it.ksStatistic, it.ksPValue, if (it.acceptable) "✓" else tr("(poor fit)", "(ajuste pobre)")))
            }
        }
        val candidates = vm.docs.filter { it != d && it.particles != null }
        if (candidates.isNotEmpty()) Section(tr("Compare distributions", "Comparar distribuciones")) {
            candidates.forEach { o ->
                SwitchRow(o.title, o.id in compare) { if (it) compare += o.id else compare.remove(o.id) }
                if (o.id in compare) {
                    val ov = Statistics.describe(distributionValues(o, variable, includeManual).first)
                    Text(String.format(Locale.US, "N=%d  %.4g ± %.3g  median %.4g", ov.n, ov.mean, ov.sd, ov.median), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (others.isNotEmpty()) Text(tr("Overlaid as coloured outlines with the same bins.", "Superpuestas como contornos de color con los mismos intervalos."), style = MaterialTheme.typography.bodySmall)
        }
        Section(tr("Export", "Exportar")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { saver.save("${d.title}_histogram.png", "image/png") { Figures.histogram(hist, title, xLabel, relative, fit?.takeIf { lognormal }, desc.mean, desc.median, desc.n, 2400, 1560, overlays).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }) { Text("PNG", fontSize = 12.sp) }
                OutlinedButton(onClick = { saver.save("${d.title}_histogram.svg", "image/svg+xml") { it.write(SvgHistogram.render(hist, title, xLabel, relative, fit?.takeIf { lognormal }, desc.mean, desc.median, n = desc.n).toByteArray()) } }) { Text("SVG", fontSize = 12.sp) }
                OutlinedButton(onClick = { saver.save("${d.title}_histogram.csv", "text/csv") { CsvWriter.write(Tables.histogram(hist, unit), it) } }) { Text("CSV", fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    saver.save("${d.title}_statistics.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") { out ->
                        val sheets = mutableListOf(Tables.statistics(values, variable.label, unit), Tables.histogram(hist, unit))
                        d.particles?.let { sheets += Tables.particles(it) }
                        if (d.measurements.isNotEmpty()) sheets += Table("Manual", listOf("ID", "Type", "Value", "Unit", "In distribution", "Duplicate of particle"),
                            d.measurements.map { listOf(it.id, it.kind.name, it.value, it.unit, it.includeInDistribution, it.linkedParticle) })
                        XlsxWriter.write(sheets, out)
                    }
                }) { Text("XLSX", fontSize = 12.sp) }
            }
        }
    }
}
