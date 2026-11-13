package com.temlab.app.data

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.temlab.app.render.Render
import com.temlab.app.ui.Lang
import com.temlab.app.ui.ThemeMode
import com.temlab.core.fft.FftAnalysis
import com.temlab.core.fft.FftOptions
import com.temlab.core.fft.FourierFilter
import com.temlab.core.fft.FourierMask
import com.temlab.core.fft.Reflection
import com.temlab.core.fft.SquareRoi
import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.particles.Particle
import com.temlab.core.particles.ParticleFilter
import com.temlab.core.particles.ParticleSet
import com.temlab.core.process.DisplaySettings
import com.temlab.core.process.Pipeline
import com.temlab.core.process.ProcessOp
import com.temlab.core.project.CalibrationChange
import com.temlab.core.project.Project
import com.temlab.core.project.ProjectIO
import com.temlab.core.project.ProjectImage
import com.temlab.core.project.ReportTemplate
import com.temlab.core.project.SavedFft
import com.temlab.core.project.SavedIfft
import com.temlab.core.project.SavedParticle
import com.temlab.core.project.SavedParticles
import com.temlab.core.segmentation.SegmentationParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class RecentFile(val uri: String, val name: String, val time: Long)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("temlab", Context.MODE_PRIVATE)

    val docs = mutableStateListOf<Doc>()
    var current by mutableStateOf<Doc?>(null)
    var message by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var themeMode by mutableStateOf(ThemeMode.valueOf(prefs.getString("theme", ThemeMode.SYSTEM.name)!!))
    var lang by mutableStateOf(Lang.valueOf(prefs.getString("lang", Lang.SYSTEM.name)!!))
    var projectName by mutableStateOf("Untitled")
    var projectSample by mutableStateOf("")
    var projectOperator by mutableStateOf("")
    var projectDescription by mutableStateOf("")
    var projectCreated = System.currentTimeMillis()
    val filterPresets = mutableStateListOf<Pair<String, Pipeline>>()
    val reportTemplates = mutableStateListOf<ReportTemplate>()
    val recents = mutableStateListOf<RecentFile>()
    private var nextDocId = 1L

    init { loadRecents(); loadPresets() }

    fun setTheme(m: ThemeMode) { themeMode = m; prefs.edit().putString("theme", m.name).apply() }
    fun setLanguage(l: Lang) { lang = l; prefs.edit().putString("lang", l.name).apply() }

    fun notify(msg: String) { message = msg }

    // ------------------------------------------------------------------ documents
    fun openUris(uris: List<Uri>, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            loading = true
            for (uri in uris) {
                try {
                    try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                    val name = Loader.displayName(ctx, uri)
                    val images = withContext(Dispatchers.IO) { Loader.load(ctx, uri) }
                    images.forEachIndexed { i, img -> addDoc(Doc(nextDocId++, uri.toString(), name, i, img)) }
                    addRecent(uri.toString(), name)
                } catch (e: Throwable) {
                    message = "Error: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            loading = false
            onDone?.invoke()
        }
    }

    fun addDoc(d: Doc) {
        docs += d; current = d
        refresh(d)
    }

    fun close(d: Doc) {
        val i = docs.indexOf(d); docs.remove(d)
        if (current == d) current = docs.getOrNull(maxOf(0, i - 1))
    }

    /** Re-runs the pipeline with the current calibration and re-renders the preview. */
    fun refresh(d: Doc, rerun: Boolean = true) {
        viewModelScope.launch {
            d.busy = true
            withContext(Dispatchers.Default) {
                if (rerun) {
                    val base = d.original.copy(pixels = d.original.pixels).also { it.calibration = d.calibration }
                    d.processed = d.pipeline.apply(base).also { it.calibration = if (d.pipeline.ops.isEmpty()) d.calibration else it.calibration }
                }
                d.preview = Render.preview(d.processed, d.display)
                if (d.pipeline.ops.isNotEmpty() && d.previewOriginal == null) d.previewOriginal = Render.preview(d.original, d.display)
            }
            d.busy = false
        }
    }

    fun setDisplay(d: Doc, ds: DisplaySettings) { d.display = ds; d.previewOriginal = null; refresh(d, rerun = false) }

    fun applyOp(d: Doc, op: ProcessOp) = setPipeline(d, Pipeline(d.pipeline.ops + op))

    fun setPipeline(d: Doc, p: Pipeline) {
        d.history.push(p); d.pipeline = p; d.previewOriginal = null
        refresh(d)
    }
    fun undo(d: Doc) { d.pipeline = d.history.undo(); refresh(d) }
    fun redo(d: Doc) { d.pipeline = d.history.redo(); refresh(d) }

    fun setCalibration(d: Doc, cal: Calibration, reason: String) {
        d.calibrationLog += CalibrationChange(System.currentTimeMillis(), d.calibration, cal, reason)
        d.calibration = cal
        refresh(d)
        // Measurements of particles depend on the calibration: re-measure.
        d.particles?.let { set -> viewModelScope.launch(Dispatchers.Default) { rebuildParticlesAfterCalibration(d, set) } }
    }

    private fun rebuildParticlesAfterCalibration(d: Doc, old: ParticleSet) {
        val set = ParticleSet(d.processed)
        set.threshold = old.threshold
        for (p in old.particles) set.particles += Particle(p.id, p.pixels, p.origin, p.excluded)
        set.particles.forEach { set.measure(it) }
        set.setFilter(old.filter)
        d.particles = set; d.particleRevision++
    }

    // ------------------------------------------------------------------ FFT
    fun computeFft(d: Doc, roi: SquareRoi, options: FftOptions) {
        viewModelScope.launch {
            d.busy = true
            val r = withContext(Dispatchers.Default) { FftAnalysis.compute(d.processed, roi, options) }
            val s = FftSession((d.ffts.maxOfOrNull { it.id } ?: 0) + 1, "FFT ${d.ffts.size + 1} (${r.roi.x},${r.roi.y} ${r.roi.size}px)", r.roi, options, r)
            d.ffts += s; d.activeFft = s
            d.busy = false
        }
    }

    fun addReflection(s: FftSession, u: Double, v: Double, radius: Int): Reflection? {
        val r = FftAnalysis.locatePeak(s.result, u, v, radius = radius, id = s.nextId, label = s.label) ?: return null
        if (s.reflections.any { kotlin.math.hypot(it.u - r.u, it.v - r.v) < 0.5 }) return null
        s.nextId++
        s.reflections += r
        return r
    }

    fun autoDetect(s: FftSession, sigma: Double) {
        val peaks = FftAnalysis.detectPeaks(s.result, sigma = sigma, minSeparation = maxOf(3, s.result.n / 128))
        for (p in peaks) if (s.reflections.none { kotlin.math.hypot(it.u - p.u, it.v - p.v) < 1.0 }) s.reflections += p.copy(id = s.nextId++, roiLabel = s.label)
    }

    // ------------------------------------------------------------------ IFFT
    fun applyFourier(d: Doc, roi: SquareRoi, mask: FourierMask, onDone: (IfftSession) -> Unit = {}) {
        viewModelScope.launch {
            d.busy = true
            val res = withContext(Dispatchers.Default) { FourierFilter.apply(d.processed, roi, mask) }
            val s = IfftSession((d.iffts.maxOfOrNull { it.id } ?: 0) + 1, "IFFT ${d.iffts.size + 1}", roi.clampedTo(d.processed.width, d.processed.height), mask, res)
            d.iffts += s
            d.busy = false
            onDone(s)
        }
    }

    /** Opens an in-app generated image (e.g. IFFT reconstruction) as an independent, clearly labelled document. */
    fun openDerived(img: ImageData, label: String, source: Doc) {
        val d = Doc(nextDocId++, source.uri, source.displayName, source.pageIndex, img)
        d.derived = label
        d.display = DisplaySettings.auto(img)
        addDoc(d)
    }

    // ------------------------------------------------------------------ particles
    fun segment(d: Doc, p: SegmentationParams, f: ParticleFilter) {
        d.segParams = p; d.particleFilter = f
        viewModelScope.launch {
            d.busy = true
            val set = withContext(Dispatchers.Default) { ParticleSet(d.processed).also { it.segment(p, f) } }
            d.particles = set; d.particleRevision++
            d.busy = false
        }
    }

    fun particlesChanged(d: Doc) { d.particleRevision++ }

    // ------------------------------------------------------------------ presets & batch
    fun savePreset(name: String, p: Pipeline) {
        filterPresets.removeAll { it.first == name }; filterPresets += name to p; persistPresets()
    }
    fun deletePreset(name: String) { filterPresets.removeAll { it.first == name }; persistPresets() }
    private fun persistPresets() {
        prefs.edit().putString("presets", ProjectIO.json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), Pipeline.serializer()), filterPresets.toMap())).apply()
    }
    private fun loadPresets() {
        val s = prefs.getString("presets", null) ?: return
        try { ProjectIO.json.decodeFromString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), Pipeline.serializer()), s).forEach { filterPresets += it.key to it.value } } catch (_: Exception) {}
    }

    fun batchApply(p: Pipeline, targets: List<Doc>) { targets.forEach { setPipeline(it, p) } }

    // ------------------------------------------------------------------ recents
    private fun addRecent(uri: String, name: String) {
        recents.removeAll { it.uri == uri }
        recents.add(0, RecentFile(uri, name, System.currentTimeMillis()))
        while (recents.size > 30) recents.removeAt(recents.size - 1)
        prefs.edit().putString("recents", recents.joinToString("\n") { "${it.uri}\t${it.name}\t${it.time}" }).apply()
    }
    private fun loadRecents() {
        prefs.getString("recents", null)?.lines()?.forEach { l ->
            val p = l.split('\t'); if (p.size == 3) recents += RecentFile(p[0], p[1], p[2].toLongOrNull() ?: 0)
        }
    }

    // ------------------------------------------------------------------ projects
    val projectsDir: File get() = File(ctx.filesDir, "projects").also { it.mkdirs() }

    fun listProjects(): List<File> = projectsDir.listFiles { f -> f.name.endsWith(".temlab.json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun toProject(): Project = Project(
        name = projectName, created = projectCreated, modified = System.currentTimeMillis(),
        sample = projectSample, operator = projectOperator, description = projectDescription,
        images = docs.filter { it.derived == null }.map { d ->
            ProjectImage(
                uri = d.uri, displayName = d.displayName, pageIndex = d.pageIndex,
                calibration = if (d.calibration != d.fileCalibration) d.calibration else null,
                calibrationLog = d.calibrationLog.toList(), pipeline = d.pipeline, display = d.display,
                ffts = d.ffts.map { SavedFft(it.id, it.label, it.roi, it.options, it.reflections.toList(), pairs = it.pairs.toList(), angles = it.angles.toList()) },
                iffts = d.iffts.map { SavedIfft(it.id, it.label, it.roi, it.mask, it.result.imaginaryResidual) },
                particles = d.particles?.let { set -> SavedParticles(d.segParams, set.filter, set.particles.map { SavedParticle(it.id, ProjectIO.encodeRuns(it.pixels), it.origin, it.excluded) }) },
                measurements = d.measurements.toList(), annotations = d.annotations.toList(), scaleBar = d.scaleBar, notes = d.notes,
            )
        },
        filterPresets = filterPresets.toMap(), reportTemplates = reportTemplates.toList(),
    )

    fun saveProject(name: String = projectName) {
        projectName = name
        val f = File(projectsDir, safe(name) + ".temlab.json")
        f.writeText(ProjectIO.encode(toProject()))
        message = "Saved: ${f.name}"
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_\\- áéíóúñÁÉÍÓÚÑ]"), "_").ifBlank { "project" }

    fun openProjectFile(f: File) = openProject(ProjectIO.decode(f.readText()))

    fun openProject(p: Project) {
        viewModelScope.launch {
            loading = true
            docs.clear(); current = null
            projectName = p.name; projectSample = p.sample; projectOperator = p.operator; projectDescription = p.description; projectCreated = p.created
            p.filterPresets.forEach { (k, v) -> if (filterPresets.none { it.first == k }) filterPresets += k to v }
            reportTemplates.clear(); reportTemplates += p.reportTemplates
            val failed = ArrayList<String>()
            for (pi in p.images) {
                try {
                    val img = withContext(Dispatchers.IO) { Loader.load(ctx, Uri.parse(pi.uri)) }.getOrNull(pi.pageIndex) ?: continue
                    val d = Doc(nextDocId++, pi.uri, pi.displayName, pi.pageIndex, img)
                    pi.calibration?.let { d.calibration = it }
                    d.calibrationLog += pi.calibrationLog
                    d.pipeline = pi.pipeline; d.history.push(pi.pipeline)
                    d.display = pi.display; d.scaleBar = pi.scaleBar; d.notes = pi.notes
                    d.measurements += pi.measurements; d.annotations += pi.annotations
                    withContext(Dispatchers.Default) {
                        val base = d.original.copy(pixels = d.original.pixels).also { it.calibration = d.calibration }
                        d.processed = d.pipeline.apply(base)
                        for (sf in pi.ffts) {
                            val r = FftAnalysis.compute(d.processed, sf.roi, sf.options)
                            val s = FftSession(sf.id, sf.label, sf.roi, sf.options, r)
                            s.reflections += sf.reflections; s.pairs += sf.pairs; s.angles += sf.angles; s.nextId = (sf.reflections.maxOfOrNull { it.id } ?: 0) + 1
                            d.ffts += s
                        }
                        d.activeFft = d.ffts.lastOrNull()
                        for (si in pi.iffts) d.iffts += IfftSession(si.id, si.label, si.roi, si.mask, FourierFilter.apply(d.processed, si.roi, si.mask))
                        pi.particles?.let { sp ->
                            val set = ParticleSet(d.processed)
                            for (q in sp.particles) set.particles += Particle(q.id, ProjectIO.decodeRuns(q.runs), q.origin, q.excluded)
                            set.particles.forEach { set.measure(it) }
                            set.setFilter(sp.filter)
                            d.segParams = sp.params; d.particleFilter = sp.filter; d.particles = set
                        }
                        d.preview = Render.preview(d.processed, d.display)
                    }
                    docs += d; current = d
                } catch (e: Throwable) { failed += "${pi.displayName}: ${e.message}" }
            }
            loading = false
            if (failed.isNotEmpty()) message = "Could not reopen: " + failed.joinToString("; ")
        }
    }

    /** Backup archive: project JSON + (optionally) copies of the original files, so it can be restored elsewhere. */
    fun exportProjectZip(out: Uri, includeOriginals: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var p = toProject()
                ctx.contentResolver.openOutputStream(out)!!.use { os ->
                    ZipOutputStream(os).use { z ->
                        if (includeOriginals) {
                            val copied = HashMap<String, String>()
                            p.images.map { it.uri }.distinct().forEachIndexed { i, u ->
                                val name = "originals/${i}_" + Loader.displayName(ctx, Uri.parse(u))
                                z.putNextEntry(ZipEntry(name))
                                ctx.contentResolver.openInputStream(Uri.parse(u))?.use { it.copyTo(z) }
                                z.closeEntry(); copied[u] = name
                            }
                            p = p.copy(images = p.images.map { it.copy(uri = "zip:" + copied[it.uri]) })
                        }
                        z.putNextEntry(ZipEntry("project.temlab.json")); z.write(ProjectIO.encode(p).toByteArray()); z.closeEntry()
                    }
                }
                message = "Project exported"
            } catch (e: Throwable) { message = "Export failed: ${e.message}" }
        }
    }

    /** Imports a .temlab.json or a backup .zip (originals are extracted into app storage). */
    fun importProject(uri: Uri) {
        viewModelScope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    val head = ctx.contentResolver.openInputStream(uri)!!.use { s -> ByteArray(2).also { b -> b[0] = s.read().toByte(); b[1] = s.read().toByte() } }
                    if (head.size == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                        val dir = File(ctx.filesDir, "imported/${System.currentTimeMillis()}").also { it.mkdirs() }
                        var json = ""
                        ZipInputStream(ctx.contentResolver.openInputStream(uri)).use { z ->
                            while (true) {
                                val e = z.nextEntry ?: break
                                if (e.name == "project.temlab.json") json = z.readBytes().decodeToString()
                                else if (e.name.startsWith("originals/") && !e.name.contains("..")) File(dir, e.name).also { it.parentFile?.mkdirs() }.outputStream().use { z.copyTo(it) }
                            }
                        }
                        val pr = ProjectIO.decode(json)
                        pr.copy(images = pr.images.map { if (it.uri.startsWith("zip:")) it.copy(uri = Uri.fromFile(File(dir, it.uri.removePrefix("zip:"))).toString()) else it })
                    } else ProjectIO.decode(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
                }
                openProject(p)
            } catch (e: Throwable) { message = "Import failed: ${e.message}" }
        }
    }

    fun newProject() {
        docs.clear(); current = null; projectName = "Untitled"; projectSample = ""; projectOperator = ""; projectDescription = ""
        projectCreated = System.currentTimeMillis()
    }

    @Suppress("unused") private val listSer = ListSerializer(String.serializer())
}
