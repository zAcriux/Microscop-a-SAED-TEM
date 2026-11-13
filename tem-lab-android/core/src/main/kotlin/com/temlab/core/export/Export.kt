package com.temlab.core.export

import com.temlab.core.fft.Reflection
import com.temlab.core.particles.ParticleSet
import com.temlab.core.stats.HistogramBins
import com.temlab.core.stats.LognormalFit
import com.temlab.core.stats.Statistics
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Simple table model shared by CSV / XLSX / PDF exports. */
data class Table(val name: String, val header: List<String>, val rows: List<List<Any?>>)

object Tables {
    private fun f(v: Double) = if (v.isFinite()) v else Double.NaN

    fun particles(set: ParticleSet, includeExcluded: Boolean = true): Table {
        val u = if (set.image.calibration.isCalibrated) set.image.calibration.unitLabel else "px"
        val header = listOf("ID", "Included", "Origin", "Area ($u²)", "Perimeter ($u)", "ECD ($u)", "Feret max ($u)", "Feret min ($u)",
            "Feret angle (°)", "Major axis ($u)", "Minor axis ($u)", "Ellipse angle (°)", "Aspect ratio", "Circularity",
            "Centroid X ($u)", "Centroid Y ($u)", "Mean intensity", "SD intensity", "Pixels", "Touches border", "Exclusion reason")
        val rows = set.particles.filter { includeExcluded || it.included }.mapNotNull { p ->
            val m = p.measurement ?: return@mapNotNull null
            listOf(p.id, p.included, p.origin.name, f(m.area), f(m.perimeter), f(m.ecd), f(m.feretMax), f(m.feretMin), f(m.feretAngle),
                f(m.majorAxis), f(m.minorAxis), f(m.ellipseAngle), f(m.aspectRatio), f(m.circularity), f(m.centroidX), f(m.centroidY),
                f(m.meanIntensity), f(m.stdIntensity), m.pixelCount, m.touchesBorder,
                when { p.excluded -> "user"; p.filteredOut -> "filter"; else -> "" })
        }
        return Table("Particles", header, rows)
    }

    fun reflections(list: List<Reflection>, displayUnit: String, factor: Double): Table = Table(
        "FFT reflections",
        listOf("ID", "u (px)", "v (px)", "kx (1/${list.firstOrNull()?.unit ?: "u"})", "ky", "|g|", "d ($displayUnit)", "Direction (°)", "Sub-pixel", "Region"),
        list.map { listOf(it.id, it.u, it.v, it.kx, it.ky, it.g, it.d * factor, it.angleDeg, it.refined, it.roiLabel) },
    )

    fun statistics(values: DoubleArray, variable: String, unit: String): Table {
        val d = Statistics.describe(values)
        val ln = Statistics.lognormal(values)
        val rows = mutableListOf<List<Any?>>(
            listOf("Variable", variable), listOf("Unit", unit), listOf("N", d.n), listOf("Mean", d.mean), listOf("Median", d.median),
            listOf("SD", d.sd), listOf("SEM", d.sem), listOf("CV", d.cv), listOf("Min", d.min), listOf("Max", d.max),
            listOf("P10", d.p10), listOf("P25", d.p25), listOf("P75", d.p75), listOf("P90", d.p90),
        )
        if (ln != null) rows.addAll(listOf(
            listOf("Lognormal μ", ln.mu), listOf("Lognormal σ", ln.sigma), listOf("Lognormal median", ln.median),
            listOf("Lognormal mean", ln.mean), listOf("KS D", ln.ksStatistic), listOf("KS p", ln.ksPValue),
        ))
        return Table("Statistics", listOf("Quantity", "Value"), rows)
    }

    fun histogram(h: HistogramBins, unit: String): Table = Table("Histogram",
        listOf("Bin start ($unit)", "Bin end ($unit)", "Count", "Relative"),
        h.counts.indices.map { listOf(h.start + it * h.width, h.start + (it + 1) * h.width, h.counts[it], h.relative()[it]) })
}

object CsvWriter {
    fun write(t: Table, out: OutputStream, sep: Char = ',') {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("﻿") // BOM so spreadsheet software detects UTF-8 (µ, Å)
        w.write(t.header.joinToString(sep.toString()) { esc(it, sep) }); w.write("\r\n")
        for (r in t.rows) { w.write(r.joinToString(sep.toString()) { esc(fmt(it), sep) }); w.write("\r\n") }
        w.flush()
    }

    fun fmt(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v.isNaN()) "" else String.format(Locale.US, "%.6g", v)
        is Float -> String.format(Locale.US, "%.6g", v)
        else -> v.toString()
    }

    private fun esc(s: String, sep: Char) = if (s.any { it == sep || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

/** Minimal Office Open XML (XLSX) writer: one sheet per table, numbers stored as numbers, inline strings. */
object XlsxWriter {
    fun write(tables: List<Table>, out: OutputStream) {
        val z = ZipOutputStream(out)
        fun put(name: String, content: String) { z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            tables.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
            tables.mapIndexed { i, t -> """<sheet name="${xml(sheetName(t.name, i))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            tables.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } + "</Relationships>")
        for ((i, t) in tables.withIndex()) {
            val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            fun row(r: Int, cells: List<Any?>) {
                sb.append("<row r=\"$r\">")
                cells.forEachIndexed { c, v ->
                    val ref = col(c) + r
                    when (v) {
                        null -> {}
                        is Number -> if (v.toDouble().isFinite()) sb.append("<c r=\"$ref\"><v>${v}</v></c>")
                        is Boolean -> sb.append("<c r=\"$ref\" t=\"b\"><v>${if (v) 1 else 0}</v></c>")
                        else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${xml(v.toString())}</t></is></c>")
                    }
                }
                sb.append("</row>")
            }
            row(1, t.header)
            t.rows.forEachIndexed { r, cells -> row(r + 2, cells) }
            sb.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
        }
        z.finish(); z.flush()
    }

    private fun sheetName(n: String, i: Int) = n.replace(Regex("[\\\\/?*\\[\\]:]"), "_").take(28).ifEmpty { "Sheet${i + 1}" }
    fun col(c: Int): String { var n = c + 1; val s = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; s.insert(0, ('A' + r)); n = (n - 1) / 26 }; return s.toString() }
    fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** Vector (SVG) histogram for publication; optional lognormal curve scaled to counts. */
object SvgHistogram {
    fun render(
        h: HistogramBins, title: String, xLabel: String, relative: Boolean, fit: LognormalFit?,
        mean: Double? = null, median: Double? = null, width: Int = 800, height: Int = 520, n: Int = h.total,
    ): String {
        val ml = 80.0; val mr = 30.0; val mt = 50.0; val mb = 70.0
        val pw = width - ml - mr; val ph = height - mt - mb
        val vals = if (relative) h.relative() else h.counts.map { it.toDouble() }.toDoubleArray()
        val fitScale = if (relative) h.width else h.width * n
        val xs0 = h.start; val xs1 = h.start + h.width * h.counts.size
        var ymax = vals.maxOrNull() ?: 1.0
        if (fit != null) { var x = xs0; while (x <= xs1) { ymax = maxOf(ymax, fit.pdf(x) * fitScale); x += (xs1 - xs0) / 200 } }
        ymax = niceCeil(ymax * 1.05)
        fun X(v: Double) = ml + (v - xs0) / (xs1 - xs0) * pw
        fun Y(v: Double) = mt + ph - v / ymax * ph
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height" font-family="Helvetica, Arial, sans-serif">""")
        sb.append("""<rect width="100%" height="100%" fill="white"/>""")
        sb.append("""<text x="${width / 2}" y="30" text-anchor="middle" font-size="18" font-weight="bold">${XlsxWriter.xml(title)}</text>""")
        for (i in vals.indices) {
            val x0 = X(h.start + i * h.width); val x1 = X(h.start + (i + 1) * h.width)
            sb.append(String.format(Locale.US, """<rect x="%.2f" y="%.2f" width="%.2f" height="%.2f" fill="#4C78A8" stroke="#1f3b57" stroke-width="0.8"/>""", x0, Y(vals[i]), x1 - x0, Y(0.0) - Y(vals[i])))
        }
        if (fit != null) {
            val pts = (0..200).map { val x = xs0 + (xs1 - xs0) * it / 200; String.format(Locale.US, "%.2f,%.2f", X(x), Y(fit.pdf(x) * fitScale)) }
            sb.append("""<polyline fill="none" stroke="#E45756" stroke-width="2.2" points="${pts.joinToString(" ")}"/>""")
        }
        fun vline(v: Double?, color: String, dash: String) { if (v != null && v in xs0..xs1) sb.append(String.format(Locale.US, """<line x1="%.2f" x2="%.2f" y1="%.2f" y2="%.2f" stroke="$color" stroke-width="1.6" stroke-dasharray="$dash"/>""", X(v), X(v), Y(0.0), Y(ymax))) }
        vline(mean, "#222222", "6,4"); vline(median, "#54A24B", "2,3")
        // axes
        sb.append(String.format(Locale.US, """<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="black"/>""", ml, Y(0.0), ml + pw, Y(0.0)))
        sb.append(String.format(Locale.US, """<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="black"/>""", ml, mt, ml, Y(0.0)))
        for (t in ticks(xs0, xs1, 6)) sb.append(String.format(Locale.US, """<line x1="%.2f" x2="%.2f" y1="%.2f" y2="%.2f" stroke="black"/><text x="%.2f" y="%.2f" text-anchor="middle" font-size="13">%s</text>""", X(t), X(t), Y(0.0), Y(0.0) + 5, X(t), Y(0.0) + 22, fmt(t)))
        for (t in ticks(0.0, ymax, 5)) sb.append(String.format(Locale.US, """<line x1="%.2f" x2="%.2f" y1="%.2f" y2="%.2f" stroke="black"/><text x="%.2f" y="%.2f" text-anchor="end" font-size="13">%s</text>""", ml - 5, ml, Y(t), Y(t), ml - 8, Y(t) + 4, fmt(t)))
        sb.append("""<text x="${ml + pw / 2}" y="${height - 20}" text-anchor="middle" font-size="15">${XlsxWriter.xml(xLabel)}</text>""")
        sb.append("""<text transform="translate(22 ${mt + ph / 2}) rotate(-90)" text-anchor="middle" font-size="15">${if (relative) "Relative frequency" else "Count"}</text>""")
        val legend = buildString {
            append("N = $n")
            if (mean != null) append(String.format(Locale.US, "   mean = %.3g", mean))
            if (median != null) append(String.format(Locale.US, "   median = %.3g", median))
            if (fit != null) append(String.format(Locale.US, "   lognormal μ=%.3f σ=%.3f", fit.mu, fit.sigma))
        }
        sb.append("""<text x="${width - mr}" y="${mt + 4}" text-anchor="end" font-size="12">${XlsxWriter.xml(legend)}</text>""")
        sb.append("</svg>")
        return sb.toString()
    }

    fun niceCeil(v: Double): Double {
        if (v <= 0) return 1.0
        val e = Math.pow(10.0, Math.floor(Math.log10(v)))
        for (m in doubleArrayOf(1.0, 1.2, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)) if (m * e >= v) return m * e
        return 10 * e
    }

    fun ticks(a: Double, b: Double, n: Int): List<Double> {
        if (b <= a) return listOf(a)
        val raw = (b - a) / n
        val e = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * e }.first { it >= raw }
        val out = ArrayList<Double>(); var t = Math.ceil(a / step) * step
        while (t <= b + 1e-9 * step) { out += t; t += step }
        return out
    }

    fun fmt(v: Double): String = if (v == Math.rint(v) && Math.abs(v) < 1e6) v.toLong().toString() else String.format(Locale.US, "%.3g", v)
}
