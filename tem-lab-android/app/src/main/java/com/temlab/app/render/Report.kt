package com.temlab.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.temlab.app.data.Doc
import com.temlab.core.export.CsvWriter
import com.temlab.core.export.Table
import com.temlab.core.export.Tables
import com.temlab.core.fft.FftAnalysis
import com.temlab.core.fft.SpectrumScale
import com.temlab.core.image.LengthUnit
import com.temlab.core.particles.ParticleVariable
import com.temlab.core.stats.Statistics
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ReportSection(val en: String, val es: String) {
    SAMPLE("Sample information", "Información de la muestra"),
    ACQUISITION("Acquisition data", "Datos de adquisición"),
    ORIGINAL("Original micrographs", "Micrografías originales"),
    PROCESSED("Processed micrographs (with scale bar)", "Micrografías procesadas (con barra de escala)"),
    PROCESSING("Processing parameters", "Parámetros de procesamiento"),
    FFT("FFT and crystallographic measurements", "FFT y mediciones cristalográficas"),
    IFFT("Fourier-filtered (IFFT) images", "Imágenes filtradas por IFFT"),
    PARTICLES("Particle analysis", "Análisis de partículas"),
    HISTOGRAM("Histograms and statistics", "Histogramas y estadística"),
    TABLES("Measurement tables", "Tablas de mediciones"),
}

class ReportInfo(val title: String, val sample: String, val operator: String, val description: String, val spanish: Boolean)

/** A4 PDF report generator based on android.graphics.pdf.PdfDocument. */
class ReportWriter(private val info: ReportInfo) {
    private val doc = PdfDocument()
    private val pw = 595; private val ph = 842; private val margin = 42f
    private var page: PdfDocument.Page? = null
    private var c: Canvas? = null
    private var y = 0f
    private var pageNo = 0
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = Color.BLACK }
    private val small = Paint(body).apply { textSize = 7.5f }
    private val h1 = Paint(body).apply { textSize = 16f; isFakeBoldText = true }
    private val h2 = Paint(body).apply { textSize = 12f; isFakeBoldText = true; color = Color.rgb(0, 80, 120) }
    private fun t(en: String, es: String) = if (info.spanish) es else en

    private fun newPage() {
        page?.let { finishPage(it) }
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
        page = p; c = p.canvas; y = margin
    }
    private fun finishPage(p: PdfDocument.Page) {
        p.canvas.drawText("TEM Lab – ${info.title} – ${t("page", "pág.")} $pageNo", margin, ph - 20f, small)
        doc.finishPage(p)
    }
    private fun ensure(h: Float) { if (c == null || y + h > ph - margin) newPage() }

    fun heading(s: String) { ensure(30f); y += 6f; c!!.drawText(s, margin, y + 12f, h2); y += 20f }

    fun text(s: String, p: Paint = body) {
        val maxW = pw - 2 * margin
        for (para in s.split("\n")) {
            var line = ""
            for (word in para.split(" ")) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(cand) > maxW && line.isNotEmpty()) { ensure(p.textSize + 3); c!!.drawText(line, margin, y + p.textSize, p); y += p.textSize + 3; line = word } else line = cand
            }
            ensure(p.textSize + 3); c!!.drawText(line, margin, y + p.textSize, p); y += p.textSize + 3
        }
    }

    fun image(b: Bitmap, caption: String, maxH: Float = 330f) {
        val maxW = pw - 2 * margin
        val s = minOf(maxW / b.width, maxH / b.height)
        val w = b.width * s; val h = b.height * s
        ensure(h + 24f)
        c!!.drawBitmap(b, null, RectF(margin + (maxW - w) / 2, y, margin + (maxW - w) / 2 + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        y += h + 3f
        text(caption, small); y += 4f
    }

    fun table(t: Table, maxRows: Int = 60, maxCols: Int = 9) {
        val cols = t.header.take(maxCols)
        val colW = (pw - 2 * margin) / cols.size
        val hp = Paint(small).apply { isFakeBoldText = true }
        fun row(cells: List<String>, p: Paint) {
            ensure(11f)
            cells.forEachIndexed { i, s -> var txt = s; while (p.measureText(txt) > colW - 3 && txt.length > 2) txt = txt.dropLast(1); c!!.drawText(txt, margin + i * colW, y + 8f, p) }
            y += 10.5f
        }
        row(cols, hp)
        c!!.drawLine(margin, y, pw - margin, y, small)
        t.rows.take(maxRows).forEach { r -> row(r.take(maxCols).map { CsvWriter.fmt(it) }, small) }
        if (t.rows.size > maxRows) text(t("… ${t.rows.size - maxRows} more rows in the CSV/XLSX export", "… ${t.rows.size - maxRows} filas más en la exportación CSV/XLSX"), small)
        if (t.header.size > maxCols) text(t("(${t.header.size - maxCols} additional columns in CSV/XLSX)", "(${t.header.size - maxCols} columnas adicionales en CSV/XLSX)"), small)
        y += 6f
    }

    fun build(docs: List<Doc>, sections: Set<ReportSection>, dUnit: LengthUnit, variable: ParticleVariable, bins: Int): PdfDocument {
        newPage()
        c!!.drawText(info.title, margin, y + 16f, h1); y += 26f
        text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()) + " – TEM Lab", small)
        if (ReportSection.SAMPLE in sections) {
            heading(t("Sample", "Muestra"))
            text("${t("Sample", "Muestra")}: ${info.sample}\n${t("Operator", "Operador")}: ${info.operator}\n${info.description}")
        }
        for (d in docs) {
            val img = d.processed
            heading("${d.title}${d.derived?.let { " [$it]" } ?: ""}")
            text("${t("File", "Archivo")}: ${d.displayName}  |  ${img.width}×${img.height} px  |  ${img.pixelType.label}  |  " +
                "${t("Calibration", "Calibración")}: " + if (d.calibration.isCalibrated) String.format(Locale.US, "%.5g × %.5g %s/px (%s)", d.calibration.pixelWidth, d.calibration.pixelHeight, d.calibration.unitLabel, d.calibration.source) else t("none (pixels)", "ninguna (píxeles)"), small)
            if (ReportSection.ACQUISITION in sections) {
                val keys = d.original.metadata.entries.filter { (k, _) -> listOf("Voltage", "Magnification", "Mode", "Name", "HV", "Format", "Exposure", "Microscope", "Camera", "Detector", "Spot").any { k.contains(it, true) } }.take(18)
                if (keys.isNotEmpty()) text(keys.joinToString("\n") { "${it.key}: ${it.value}" }, small)
            }
            if (ReportSection.ORIGINAL in sections) image(Render.fullBitmap(d.original, d.display).let { downscale(it) }, t("Original data (display-adjusted only)", "Datos originales (solo ajuste de visualización)"))
            if (ReportSection.PROCESSED in sections) image(downscale(Figures.annotatedImage(d, particles = if (ReportSection.PARTICLES in sections) ParticleOverlay.NUMBERED else ParticleOverlay.NONE)), t("Processed/annotated micrograph", "Micrografía procesada/anotada"))
            if (ReportSection.PROCESSING in sections) {
                text("${t("Processing pipeline", "Procesamiento")}: ${d.pipeline.describe()}", small)
                if (d.calibrationLog.isNotEmpty()) text(t("Calibration changes", "Cambios de calibración") + ":\n" + d.calibrationLog.joinToString("\n") { "${Date(it.timestamp)}: ${it.from.pixelWidth} ${it.from.unitLabel} → ${it.to.pixelWidth} ${it.to.unitLabel} (${it.reason})" }, small)
            }
            if (ReportSection.FFT in sections) for (s in d.ffts) {
                image(Figures.fftFigure(s, true, SpectrumScale.AMPLITUDE, 1.0, dUnit), "${s.label}: ROI x=${s.roi.x}, y=${s.roi.y}, ${s.roi.size} px; FFT ${s.result.n}×${s.result.n}; Hann=${s.options.hannWindow}; Δk=${String.format(Locale.US, "%.5g", s.result.dkx)} 1/${s.result.unit.symbol}/px", 260f)
                if (s.reflections.isNotEmpty()) {
                    val f = if (s.reflections.first().unit == "px") 1.0 else FftAnalysis.convert(1.0, s.reflections.first().unit, dUnit)
                    table(Tables.reflections(s.reflections, if (s.reflections.first().unit == "px") "px" else dUnit.symbol, f))
                    if (s.reflections.size >= 2) {
                        val a = s.reflections[0]; val b = s.reflections[1]
                        text(String.format(Locale.US, t("Angle between #%d and #%d: %.2f°", "Ángulo entre #%d y #%d: %.2f°"), a.id, b.id, FftAnalysis.angleBetween(a, b)), small)
                    }
                }
                text(t("Any hkl/phase assignment is tentative and requires independent validation.", "Toda asignación hkl/fase es tentativa y requiere validación independiente."), small)
            }
            if (ReportSection.IFFT in sections) for (s in d.iffts) {
                image(downscale(Render.floatBitmap(s.result.reconstruction.pixels, s.result.n, s.result.n)), "${s.label} – ${t("FILTERED IMAGE (may contain artefacts)", "IMAGEN FILTRADA (puede contener artefactos)")}. ${s.result.description}; Im/Re residual=${String.format(Locale.US, "%.2e", s.result.imaginaryResidual)}", 240f)
            }
            val set = d.particles
            if (set != null && (ReportSection.HISTOGRAM in sections || ReportSection.TABLES in sections || ReportSection.PARTICLES in sections)) {
                val unit = if (variable.isLength) (if (img.calibration.isCalibrated) img.calibration.unitLabel else "px") else if (variable.isArea) (if (img.calibration.isCalibrated) img.calibration.unitLabel + "²" else "px²") else ""
                val v = set.values(variable)
                if (ReportSection.PARTICLES in sections) text(t("Segmentation", "Segmentación") + ": ${d.segParams}\n" + t("Filter", "Filtro") + ": ${set.filter}\n" + t("Included particles", "Partículas incluidas") + ": ${set.included.size} / ${set.particles.size}", small)
                if (ReportSection.HISTOGRAM in sections && v.isNotEmpty()) {
                    val ds = Statistics.describe(v); val fit = Statistics.lognormal(v)
                    image(Figures.histogram(Statistics.histogram(v, bins), d.title, "${variable.label} ($unit)", false, fit?.takeIf { it.acceptable }, ds.mean, ds.median, ds.n), t("Size distribution", "Distribución de tamaños"), 300f)
                    table(Tables.statistics(v, variable.label, unit))
                }
                if (ReportSection.TABLES in sections) table(Tables.particles(set))
            }
            if (ReportSection.TABLES in sections && d.measurements.isNotEmpty())
                table(Table("Manual", listOf("ID", "Type", "Value", "Unit", "Linked particle"), d.measurements.map { listOf(it.id, it.kind.name, it.value, it.unit, it.linkedParticle) }))
        }
        page?.let { finishPage(it) }; page = null
        return doc
    }

    fun write(out: OutputStream) { doc.writeTo(out); doc.close() }

    private fun downscale(b: Bitmap, max: Int = 1400): Bitmap {
        val s = max.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }
}
