/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core.project

import com.temlab.core.fft.FftOptions
import com.temlab.core.fft.FourierMask
import com.temlab.core.fft.Reflection
import com.temlab.core.fft.SquareRoi
import com.temlab.core.image.Calibration
import com.temlab.core.measure.ManualMeasurement
import com.temlab.core.measure.Pt
import com.temlab.core.particles.ParticleFilter
import com.temlab.core.particles.ParticleOrigin
import com.temlab.core.process.DisplaySettings
import com.temlab.core.process.Pipeline
import com.temlab.core.segmentation.SegmentationParams
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class AnnotationType { ARROW, LINE, CIRCLE, RECTANGLE, SQUARE, LABEL, SCALE_BAR, HKL, DSPACING, ANGLE, PARTICLE_NUMBER, ROI }

/** Editable annotation layer element. Geometry in original image pixel coordinates. */
@Serializable
data class Annotation(
    val id: Int,
    val type: AnnotationType,
    val points: List<Pt>,
    val text: String = "",
    val color: Long = 0xFFFFFF00,   // ARGB
    val strokeWidth: Float = 3f,
    val fontSize: Float = 28f,
    val layer: String = "Annotations",
    val visible: Boolean = true,
)

/** Visual scale bar. Changing it never modifies the image calibration. */
@Serializable
data class ScaleBarStyle(
    val visible: Boolean = true,
    val lengthPhysical: Double = Double.NaN, // NaN = automatic "nice" length
    val position: String = "BOTTOM_RIGHT",   // BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT, CUSTOM
    val customX: Double = 0.0, val customY: Double = 0.0,
    val color: Long = 0xFFFFFFFF,
    val thicknessFraction: Double = 0.012,   // of image height
    val text: String = "",                    // empty = automatic "<length> <unit>"
    val showText: Boolean = true,
    val background: Boolean = true,
)

@Serializable
data class CalibrationChange(val timestamp: Long, val from: Calibration, val to: Calibration, val reason: String)

@Serializable
data class AngleRecord(val a: Int, val b: Int, val angleDeg: Double)

@Serializable
data class SavedFft(
    val id: Int,
    val label: String,
    val roi: SquareRoi,
    val options: FftOptions,
    val reflections: List<Reflection>,
    val notes: String = "",
    val pairs: List<com.temlab.core.fft.SymmetricPairResult> = emptyList(),
    val angles: List<AngleRecord> = emptyList(),
)

@Serializable
data class SavedIfft(val id: Int, val label: String, val roi: SquareRoi, val mask: FourierMask, val imaginaryResidual: Double)

/** Particle stored as run-length encoded pixel indices: [start0, len0, start1, len1, ...]. */
@Serializable
data class SavedParticle(val id: Int, val runs: IntArray, val origin: ParticleOrigin, val excluded: Boolean)

@Serializable
data class SavedParticles(val params: SegmentationParams, val filter: ParticleFilter, val particles: List<SavedParticle>)

@Serializable
data class ProjectImage(
    val uri: String,                         // persistent SAF uri or file path
    val displayName: String,
    val pageIndex: Int = 0,
    val calibration: Calibration? = null,   // null = use the calibration read from the file
    val calibrationLog: List<CalibrationChange> = emptyList(),
    val pipeline: Pipeline = Pipeline(),
    val display: DisplaySettings = DisplaySettings(),
    val ffts: List<SavedFft> = emptyList(),
    val iffts: List<SavedIfft> = emptyList(),
    val particles: SavedParticles? = null,
    val measurements: List<ManualMeasurement> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    val scaleBar: ScaleBarStyle = ScaleBarStyle(),
    val notes: String = "",
)

@Serializable
data class ReportTemplate(
    val name: String,
    val sections: List<String>, // keys of ReportSection
)

@Serializable
data class Project(
    val name: String,
    val created: Long,
    val modified: Long,
    val sample: String = "",
    val operator: String = "",
    val description: String = "",
    val images: List<ProjectImage> = emptyList(),
    val filterPresets: Map<String, Pipeline> = emptyMap(),
    val reportTemplates: List<ReportTemplate> = emptyList(),
    val formatVersion: Int = 1,
)

object ProjectIO {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }
    fun encode(p: Project): String = json.encodeToString(p)
    fun decode(s: String): Project = json.decodeFromString(Project.serializer(), s)

    fun encodeRuns(sortedPixels: IntArray): IntArray {
        if (sortedPixels.isEmpty()) return IntArray(0)
        val out = ArrayList<Int>()
        var start = sortedPixels[0]; var len = 1
        for (i in 1 until sortedPixels.size) {
            if (sortedPixels[i] == start + len) len++ else { out += start; out += len; start = sortedPixels[i]; len = 1 }
        }
        out += start; out += len
        return out.toIntArray()
    }

    fun decodeRuns(runs: IntArray): IntArray {
        var n = 0; for (i in 1 until runs.size step 2) n += runs[i]
        val out = IntArray(n); var k = 0
        for (i in 0 until runs.size step 2) for (j in 0 until runs[i + 1]) out[k++] = runs[i] + j
        return out
    }
}

/** Automatic "nice" scale bar length (1, 2, 5 × 10ⁿ) close to [fraction] of the image width. */
fun niceScaleLength(imageWidthPhysical: Double, fraction: Double = 0.2): Double {
    val target = imageWidthPhysical * fraction
    if (target <= 0) return 1.0
    val e = Math.pow(10.0, Math.floor(Math.log10(target)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * e }.last { it <= target * 1.0001 }
}
