/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core.io

import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.image.LengthUnit
import com.temlab.core.image.PixelType
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream

/** MRC / CCP4 (2014 standard) reader: modes 0,1,2,6,12. First section of a stack is returned per page. */
object MrcReader {
    fun isMrc(src: ByteSource, fileName: String): Boolean =
        src.size > 1024 && (fileName.lowercase().let { it.endsWith(".mrc") || it.endsWith(".mrcs") || it.endsWith(".map") || it.endsWith(".st") || it.endsWith(".rec") })

    fun read(src: ByteSource, name: String, maxSections: Int = 16): List<ImageData> {
        // Machine stamp at 212: 0x44 0x44 -> little endian, 0x11 0x11 -> big endian
        val le = src.u8(212) != 0x11
        val nx = src.i32(0, le); val ny = src.i32(4, le); val nz = src.i32(8, le); val mode = src.i32(12, le)
        if (nx <= 0 || ny <= 0 || nx > 65536 || ny > 65536) throw ImageFormatException("Invalid MRC header")
        val mx = src.i32(28, le); val my = src.i32(32, le)
        val cellA = src.f32(40, le).toDouble(); val cellB = src.f32(44, le).toDouble()
        val nsymbt = src.i32(92, le)
        val bpp = when (mode) { 0 -> 1; 1, 6, 12 -> 2; 2 -> 4; else -> throw ImageFormatException("Unsupported MRC mode $mode") }
        val ptype = when (mode) { 0 -> PixelType.INT8; 1 -> PixelType.INT16; 6 -> PixelType.UINT16; 12 -> PixelType.FLOAT16; else -> PixelType.FLOAT32 }
        val cal = if (cellA > 0 && mx > 0) Calibration(cellA / mx / 10.0, (if (cellB > 0 && my > 0) cellB / my else cellA / mx) / 10.0, LengthUnit.NM, false, "MRC header (cell/mx, Å→nm)") else Calibration.NONE
        val out = ArrayList<ImageData>()
        val frame = nx.toLong() * ny
        for (z in 0 until minOf(maxOf(nz, 1), maxSections)) {
            val off = 1024L + nsymbt + z * frame * bpp
            if (off + frame * bpp > src.size) break
            val b = src.slice(off, (frame * bpp).toInt(), le)
            val px = FloatArray(frame.toInt()) { i ->
                when (mode) {
                    0 -> b.get(i).toFloat(); 1 -> b.getShort(i * 2).toFloat()
                    6 -> (b.getShort(i * 2).toInt() and 0xFFFF).toFloat()
                    12 -> TiffReader.halfToFloat(b.getShort(i * 2).toInt())
                    else -> b.getFloat(i * 4)
                }
            }
            out += ImageData(nx, ny, px, ptype, cal, linkedMapOf("Format" to "MRC", "Mode" to "$mode", "Section" to "${z + 1}/$nz"),
                if (nz > 1) "$name [z${z + 1}]" else name)
        }
        return out
    }
}

/** Format detection + dispatch for the scientific formats handled in pure Kotlin. */
object ScientificImageIO {
    val scientificExtensions = setOf("dm3", "dm4", "tif", "tiff", "mrc", "mrcs", "map", "st", "rec")

    fun canRead(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in scientificExtensions

    fun read(file: File, displayName: String = file.name): List<ImageData> = ByteSource.of(file).use { read(it, displayName) }

    fun read(src: ByteSource, displayName: String): List<ImageData> {
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext == "emd") throw ImageFormatException("EMD (HDF5) is not supported in this version: export as DM4/TIFF")
        val images = when {
            TiffReader.isTiff(src) -> TiffReader.read(src, base)
            DmReader.isDm(src) -> DmReader.read(src, base)
            MrcReader.isMrc(src, displayName) -> MrcReader.read(src, base)
            else -> throw ImageFormatException("Unrecognised file format: $displayName")
        }
        images.forEach { it.metadata["File"] = displayName }
        return images
    }
}

/**
 * Writes a single-page 32-bit float TIFF with ImageJ-compatible calibration (ImageDescription + X/YResolution),
 * so processed data (IFFT reconstructions, filtered images) keep full precision and calibration.
 */
object TiffWriter {
    fun writeFloat(img: ImageData, out: OutputStream, description: String? = null) {
        val cal = img.calibration
        val desc = (buildString {
            append("ImageJ=1.54f\n")
            if (cal.isCalibrated && !cal.reciprocal) append("unit=${if (cal.unit == LengthUnit.UM) "micron" else cal.unit.symbol}\n")
            if (description != null) append("info=").append(description.replace('\n', ' ')).append('\n')
        }.toByteArray(Charsets.UTF_8) + 0)
        val w = img.width; val h = img.height
        val nEntries = 14
        val ifdOffset = 8
        var p = ifdOffset + 2 + nEntries * 12 + 4
        val descOff = p; p += desc.size
        if (p % 2 == 1) p++
        val xresOff = p; p += 8
        val yresOff = p; p += 8
        val dataOff = p
        val d = DataOutputStream(java.io.BufferedOutputStream(out, 1 shl 16))
        d.writeBytes("MM"); d.writeShort(42); d.writeInt(ifdOffset)
        d.writeShort(nEntries)
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            d.writeShort(tag); d.writeShort(type); d.writeInt(count)
            if (type == 3 && count == 1) { d.writeShort(value); d.writeShort(0) } else d.writeInt(value)
        }
        entry(254, 4, 1, 0); entry(256, 4, 1, w); entry(257, 4, 1, h); entry(258, 3, 1, 32)
        entry(259, 3, 1, 1); entry(262, 3, 1, 1); entry(270, 2, desc.size, descOff); entry(273, 4, 1, dataOff)
        entry(277, 3, 1, 1); entry(278, 4, 1, h); entry(279, 4, 1, w * h * 4)
        entry(282, 5, 1, xresOff); entry(283, 5, 1, yresOff); entry(339, 3, 1, 3)
        d.writeInt(0)
        d.write(desc)
        if ((descOff + desc.size) % 2 == 1) d.writeByte(0)
        fun rational(v: Double) { // maximise precision of num/den within 32-bit range
            val den = minOf(Int.MAX_VALUE.toDouble(), Int.MAX_VALUE / maxOf(v, 1.0)).toLong().coerceAtLeast(1)
            d.writeInt(Math.round(v * den).toInt()); d.writeInt(den.toInt())
        }
        rational(if (cal.isCalibrated) 1.0 / cal.pixelWidth else 1.0)
        rational(if (cal.isCalibrated) 1.0 / cal.pixelHeight else 1.0)
        for (v in img.pixels) d.writeFloat(v)
        d.flush()
    }
}
