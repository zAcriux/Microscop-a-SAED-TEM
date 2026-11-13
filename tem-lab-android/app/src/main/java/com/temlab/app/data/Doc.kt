package com.temlab.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.temlab.app.render.RenderedTile
import com.temlab.core.fft.FftOptions
import com.temlab.core.fft.FftResult
import com.temlab.core.fft.FilterResult
import com.temlab.core.fft.FourierMask
import com.temlab.core.fft.Reflection
import com.temlab.core.fft.SquareRoi
import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.image.PixelType
import com.temlab.core.io.ScientificImageIO
import com.temlab.core.measure.ManualMeasurement
import com.temlab.core.particles.ParticleFilter
import com.temlab.core.particles.ParticleSet
import com.temlab.core.process.DisplaySettings
import com.temlab.core.process.History
import com.temlab.core.process.Pipeline
import com.temlab.core.project.Annotation
import com.temlab.core.project.CalibrationChange
import com.temlab.core.project.ScaleBarStyle
import com.temlab.core.segmentation.SegmentationParams
import java.io.File

class FftSession(
    val id: Int,
    var label: String,
    val roi: SquareRoi,
    val options: FftOptions,
    val result: FftResult,
) {
    val reflections = mutableStateListOf<Reflection>()
    val pairs = mutableStateListOf<com.temlab.core.fft.SymmetricPairResult>()
    val angles = mutableStateListOf<com.temlab.core.project.AngleRecord>()
    var nextId = 1
}

class IfftSession(
    val id: Int,
    val label: String,
    val roi: SquareRoi,
    val mask: FourierMask,
    val result: FilterResult,
)

/** One open micrograph with all its analysis state. The original data is never modified. */
class Doc(
    val id: Long,
    val uri: String,
    val displayName: String,
    val pageIndex: Int,
    val original: ImageData,
) {
    val fileCalibration: Calibration = original.calibration
    var calibration by mutableStateOf(original.calibration)
    val calibrationLog = mutableStateListOf<CalibrationChange>()
    val history = History()
    var pipeline by mutableStateOf(Pipeline())
    /** Result of the pipeline applied to the original with the current calibration (what analyses use). */
    var processed by mutableStateOf(original)
    var display by mutableStateOf(DisplaySettings.auto(original))
    var preview by mutableStateOf<RenderedTile?>(null)
    var previewOriginal by mutableStateOf<RenderedTile?>(null)
    var busy by mutableStateOf(false)

    val measurements = mutableStateListOf<ManualMeasurement>()
    val annotations = mutableStateListOf<Annotation>()
    var scaleBar by mutableStateOf(ScaleBarStyle())
    var notes by mutableStateOf("")

    var particles by mutableStateOf<ParticleSet?>(null)
    var particleRevision by mutableIntStateOf(0)
    var segParams by mutableStateOf(SegmentationParams())
    var particleFilter by mutableStateOf(ParticleFilter())

    var roi by mutableStateOf<SquareRoi?>(null)
    var fftOptions by mutableStateOf(FftOptions())
    val ffts = mutableStateListOf<FftSession>()
    var activeFft by mutableStateOf<FftSession?>(null)
    val iffts = mutableStateListOf<IfftSession>()
    var mask by mutableStateOf(FourierMask())

    /** True when produced inside the app (e.g. IFFT reconstruction) rather than loaded from a file. */
    var derived: String? = null

    val title: String get() = original.name
}

object Loader {
    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path ?: "image").name
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "image"
    }

    /**
     * Reads every image contained in a document. Scientific formats are copied to the cache and memory-mapped;
     * PNG/JPG/BMP are decoded by Android and converted to luminance (8-bit data, flagged in the metadata).
     */
    fun load(ctx: Context, uri: Uri): List<ImageData> {
        val name = displayName(ctx, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ScientificImageIO.canRead(name) || ext == "emd" || ext.isEmpty()) {
            val tmp = File(ctx.cacheDir, "import_${System.nanoTime()}.$ext")
            ctx.contentResolver.openInputStream(uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
            try {
                return ScientificImageIO.read(tmp, name)
            } catch (e: Exception) {
                if (ext !in setOf("tif", "tiff", "")) throw e
            } finally { tmp.delete() }
        }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("Unsupported or corrupt image: $name")
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        var grey = true
        val out = FloatArray(w * h) {
            val c = px[it]; val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (r != g || g != b) grey = false
            0.299f * r + 0.587f * g + 0.114f * b
        }
        bmp.recycle()
        val meta = linkedMapOf("Format" to ext.uppercase(), "Dimensions" to "$w x $h",
            "Note" to "8-bit display format: no spatial calibration stored; calibrate manually or use the original DM/TIFF data")
        return listOf(ImageData(w, h, out, if (grey) PixelType.UINT8 else PixelType.RGB24, Calibration.NONE.copy(source = "none (${ext.uppercase()})"), meta, name.substringBeforeLast('.')))
    }
}
