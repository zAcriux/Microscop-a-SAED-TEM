package com.temlab.core.io

import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.image.LengthUnit
import com.temlab.core.image.PixelType
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

/**
 * Reader for Gatan DigitalMicrograph DM3 (version 3) and DM4 (version 4) files.
 *
 * File layout (all structural integers big-endian):
 *  header: version(4) | rootLength (4 in DM3, 8 in DM4) | byteOrder(4) (0 = big, 1 = little endian data)
 *  tag group: sorted(1) | open(1) | nTags (4 / 8)
 *  tag entry: kind(1: 20=group, 21=data) | labelLen(2) | label | [DM4: entrySize(8)] | group or data
 *  data tag:  "%%%%" | nInfo (4 / 8) | info[nInfo] (4 / 8 each) | payload (in data byte order)
 *
 * Large arrays (the image itself) are not materialised as tag values; their file offset is recorded and the
 * pixel buffer is converted directly to float.
 */
object DmReader {

    sealed class Tag
    class Group(val entries: MutableList<Pair<String, Tag>> = mutableListOf()) : Tag() {
        operator fun get(label: String): Tag? = entries.firstOrNull { it.first == label }?.second
        fun list(): List<Tag> = entries.map { it.second }
    }
    class Value(val value: Any?, val ref: ArrayRef? = null) : Tag()
    /** An array kept on disk: element type code, element count, offset. */
    class ArrayRef(val elemType: Int, val count: Long, val offset: Long) : Tag()

    class DmFile(val version: Int, val littleEndian: Boolean, val root: Group)

    private const val SHORT = 2; private const val LONG = 3; private const val USHORT = 4; private const val ULONG = 5
    private const val FLOAT = 6; private const val DOUBLE = 7; private const val BOOL = 8; private const val CHAR = 9
    private const val OCTET = 10; private const val INT64 = 11; private const val UINT64 = 12
    private const val STRUCT = 15; private const val STRING = 18; private const val ARRAY = 20

    private fun typeSize(t: Int): Int = when (t) {
        SHORT, USHORT -> 2; LONG, ULONG, FLOAT -> 4; DOUBLE, INT64, UINT64 -> 8; BOOL, CHAR, OCTET -> 1
        else -> throw ImageFormatException("Unknown DM data type $t")
    }

    fun isDm(src: ByteSource): Boolean = src.size > 16 && src.i32(0, false).let { it == 3 || it == 4 }

    fun parse(src: ByteSource): DmFile {
        val version = src.i32(0, false)
        if (version != 3 && version != 4) throw ImageFormatException("Not a DM3/DM4 file (version $version)")
        val dm4 = version == 4
        src.pos = 4
        if (dm4) src.readI64(false) else src.readI32(false)
        val le = src.readI32(false) == 1
        val root = readGroup(src, dm4, le, 0)
        return DmFile(version, le, root)
    }

    private fun readGroup(src: ByteSource, dm4: Boolean, le: Boolean, depth: Int): Group {
        if (depth > 64) throw ImageFormatException("DM tag tree too deep")
        src.readU8(); src.readU8()
        val n = if (dm4) src.readI64(false) else src.readI32(false).toLong()
        if (n < 0 || n > 10_000_000) throw ImageFormatException("Corrupt DM tag count $n")
        val g = Group()
        var unnamed = 0
        for (i in 0 until n) {
            val kind = src.readU8()
            if (kind == 0) break
            val labelLen = src.readI16(false) and 0xFFFF
            val label = String(src.readBytes(labelLen), StandardCharsets.ISO_8859_1).ifEmpty { "[${unnamed++}]" }
            if (dm4) src.readI64(false)
            when (kind) {
                20 -> g.entries += label to readGroup(src, dm4, le, depth + 1)
                21 -> g.entries += label to readData(src, dm4, le)
                else -> throw ImageFormatException("Unknown DM tag kind $kind at ${src.pos}")
            }
        }
        return g
    }

    private fun readData(src: ByteSource, dm4: Boolean, le: Boolean): Tag {
        val delim = String(src.readBytes(4), StandardCharsets.ISO_8859_1)
        if (delim != "%%%%") throw ImageFormatException("Missing %%%% delimiter at ${src.pos - 4}")
        val nInfo = if (dm4) src.readI64(false).toInt() else src.readI32(false)
        val info = LongArray(nInfo) { if (dm4) src.readI64(false) else src.readI32(false).toLong() }
        val t = info[0].toInt()
        return when (t) {
            STRUCT -> Value(readStruct(src, info, 1, le).first)
            STRING -> {
                val len = info[1].toInt()
                val b = src.readBytes(len * 2)
                Value(String(b, if (le) StandardCharsets.UTF_16LE else StandardCharsets.UTF_16BE))
            }
            ARRAY -> {
                val et = info[1].toInt()
                if (et == STRUCT) {
                    // info: 20, 15, nameLen, nFields, (nameLen, type)*, count
                    val nFields = info[3].toInt()
                    val types = IntArray(nFields) { info[5 + 2 * it].toInt() }
                    val count = info[4 + 2 * nFields]
                    val out = ArrayList<List<Any>>()
                    for (k in 0 until count) out += types.map { readSimple(src, it, le) }
                    Value(out)
                } else {
                    val count = info[2]
                    val bytes = count * typeSize(et)
                    val ref = ArrayRef(et, count, src.pos)
                    src.pos += bytes
                    // Small arrays of 16-bit values are usually strings (UTF-16).
                    if (et == USHORT && count in 1..2048) {
                        val b = src.bytes(ref.offset, (count * 2).toInt())
                        Value(String(b, if (le) StandardCharsets.UTF_16LE else StandardCharsets.UTF_16BE).trimEnd('\u0000'), ref)
                    } else ref
                }
            }
            else -> Value(readSimple(src, t, le))
        }
    }

    private fun readStruct(src: ByteSource, info: LongArray, start: Int, le: Boolean): Pair<List<Any>, Int> {
        val nFields = info[start + 1].toInt()
        val values = ArrayList<Any>()
        for (f in 0 until nFields) values += readSimple(src, info[start + 3 + 2 * f].toInt(), le)
        return values to (start + 2 + 2 * nFields)
    }

    private fun readSimple(src: ByteSource, t: Int, le: Boolean): Any = when (t) {
        SHORT -> src.readI16(le)
        USHORT -> src.readI16(le) and 0xFFFF
        LONG -> src.readI32(le)
        ULONG -> src.readI32(le).toLong() and 0xFFFFFFFFL
        FLOAT -> java.lang.Float.intBitsToFloat(src.readI32(le))
        DOUBLE -> java.lang.Double.longBitsToDouble(src.readI64(le))
        BOOL -> src.readU8() != 0
        CHAR, OCTET -> src.readU8()
        INT64, UINT64 -> src.readI64(le)
        else -> throw ImageFormatException("Unsupported DM simple type $t")
    }

    // ---------------------------------------------------------------- image extraction

    fun read(src: ByteSource, name: String = "dm"): List<ImageData> {
        val dm = parse(src)
        val imageList = dm.root["ImageList"] as? Group ?: throw ImageFormatException("DM file has no ImageList")
        val images = imageList.list().filterIsInstance<Group>()
        // By DigitalMicrograph convention ImageList[0] is the display thumbnail when more than one image is stored.
        val data = if (images.size > 1) images.drop(1) else images
        val out = data.mapNotNull { img -> extract(src, dm, img, name) }
        if (out.isEmpty()) throw ImageFormatException("No readable image in DM file")
        if (out.size > 1) out.forEachIndexed { i, im -> im.name = "$name [${i + 1}]" }
        return out
    }

    private fun num(t: Tag?): Double? = (t as? Value)?.value.let {
        when (it) { is Number -> it.toDouble(); is Boolean -> if (it) 1.0 else 0.0; else -> null }
    }
    private fun str(t: Tag?): String? = (t as? Value)?.value as? String

    private fun extract(src: ByteSource, dm: DmFile, img: Group, baseName: String): ImageData? {
        val data = img["ImageData"] as? Group ?: return null
        val dimsG = data["Dimensions"] as? Group ?: return null
        val dims = dimsG.list().mapNotNull { num(it)?.toInt() }
        if (dims.isEmpty()) return null
        val w = dims[0]; val h = if (dims.size > 1) dims[1] else 1
        val dataType = num(data["DataType"])?.toInt() ?: return null
        val raw = data["Data"]
        val ref = when (raw) { is ArrayRef -> raw; is Value -> raw.ref; else -> null } ?: return null
        if (h <= 1) return null // 1-D spectra are not images
        val frame = w.toLong() * h
        val (ptype, px) = convert(src, ref, dataType, frame.toInt(), dm.littleEndian)
            ?: throw ImageFormatException("Unsupported DM image data type $dataType")
        val meta = linkedMapOf<String, String>()
        meta["Format"] = "DigitalMicrograph DM${dm.version}"
        meta["Dimensions"] = dims.joinToString(" x ")
        if (dims.size > 2) meta["Note"] = "3-D stack: first plane shown (${dims[2]} planes)"
        meta["DM data type"] = dataType.toString()
        val cal = readCalibration(data, meta)
        str(img["Name"])?.let { meta["Image name"] = it }
        (img["ImageTags"] as? Group)?.let { flattenTags(it, "", meta, 0) }
        return ImageData(w, h, px, ptype, cal, meta, baseName)
    }

    private fun readCalibration(data: Group, meta: MutableMap<String, String>): Calibration {
        val dimCal = (data["Calibrations"] as? Group)?.get("Dimension") as? Group ?: return Calibration.NONE
        val d = dimCal.list().filterIsInstance<Group>()
        if (d.isEmpty()) return Calibration.NONE
        val sx = num(d[0]["Scale"]) ?: 1.0
        val sy = if (d.size > 1) num(d[1]["Scale"]) ?: sx else sx
        val unitStr = str(d[0]["Units"]) ?: ""
        meta["Calibration (DM)"] = "scale=$sx,$sy unit='$unitStr'"
        return calibrationFromUnitString(sx, sy, unitStr, "DM tags")
    }

    /** Recursively flattens acquisition metadata (microscope, voltage, magnification...). */
    private fun flattenTags(g: Group, prefix: String, out: MutableMap<String, String>, depth: Int) {
        if (depth > 8 || out.size > 400) return
        for ((label, t) in g.entries) {
            val key = if (prefix.isEmpty()) label else "$prefix.$label"
            when (t) {
                is Group -> flattenTags(t, key, out, depth + 1)
                is Value -> when (val v = t.value) {
                    is String, is Number, is Boolean -> out[key] = v.toString()
                    is List<*> -> if (v.size <= 8) out[key] = v.toString()
                    else -> {}
                }
                is ArrayRef -> {}
            }
        }
    }

    private fun convert(src: ByteSource, ref: ArrayRef, dmType: Int, n: Int, le: Boolean): Pair<PixelType, FloatArray>? {
        val out = FloatArray(n)
        // Data element size must be consistent with the image pixel count.
        val elemBytes = when (ref.elemType) { SHORT, USHORT -> 2; LONG, ULONG, FLOAT -> 4; DOUBLE, INT64, UINT64 -> 8; else -> 1 }
        val totalBytes = ref.count * elemBytes
        val bpp = when (dmType) { 1, 10 -> 2; 2, 7, 11, 23 -> 4; 6, 9, 14 -> 1; 12, 3 -> 8; 13 -> 16; else -> return null }
        if (totalBytes < n.toLong() * bpp) throw ImageFormatException("DM data array smaller than image (${totalBytes} < ${n * bpp})")
        val b = src.slice(ref.offset, n * bpp, le)
        val type = when (dmType) {
            1 -> { for (i in 0 until n) out[i] = b.getShort(i * 2).toFloat(); PixelType.INT16 }
            10 -> { for (i in 0 until n) out[i] = (b.getShort(i * 2).toInt() and 0xFFFF).toFloat(); PixelType.UINT16 }
            2 -> { for (i in 0 until n) out[i] = b.getFloat(i * 4); PixelType.FLOAT32 }
            7 -> { for (i in 0 until n) out[i] = b.getInt(i * 4).toFloat(); PixelType.INT32 }
            11 -> { for (i in 0 until n) out[i] = (b.getInt(i * 4).toLong() and 0xFFFFFFFFL).toFloat(); PixelType.UINT32 }
            6, 14 -> { for (i in 0 until n) out[i] = (b.get(i).toInt() and 0xFF).toFloat(); if (dmType == 14) PixelType.BINARY else PixelType.UINT8 }
            9 -> { for (i in 0 until n) out[i] = b.get(i).toFloat(); PixelType.INT8 }
            12 -> { for (i in 0 until n) out[i] = b.getDouble(i * 8).toFloat(); PixelType.FLOAT64 }
            3 -> { for (i in 0 until n) { val re = b.getFloat(i * 8); val im = b.getFloat(i * 8 + 4); out[i] = sqrt(re * re + im * im) }; PixelType.COMPLEX64 }
            13 -> { for (i in 0 until n) { val re = b.getDouble(i * 16); val im = b.getDouble(i * 16 + 8); out[i] = sqrt(re * re + im * im).toFloat() }; PixelType.COMPLEX64 }
            23 -> { // RGBA packed
                for (i in 0 until n) {
                    val v = b.getInt(i * 4)
                    val r = v and 0xFF; val g = (v shr 8) and 0xFF; val bl = (v shr 16) and 0xFF
                    out[i] = (0.299f * r + 0.587f * g + 0.114f * bl)
                }; PixelType.RGB24
            }
            else -> return null
        }
        return type to out
    }
}

/** Converts a scale + unit string (as found in DM, ImageJ, vendor TIFFs) into a [Calibration]. */
fun calibrationFromUnitString(sx: Double, sy: Double, unitStr: String, source: String): Calibration {
    val u = unitStr.trim()
    if (u.isEmpty() || sx <= 0 || sx.isNaN()) return Calibration.NONE.copy(source = "$source (uncalibrated)")
    val reciprocal = u.startsWith("1/") || u.endsWith("^-1") || u.endsWith("-1")
    val base = u.removePrefix("1/").removeSuffix("^-1").removeSuffix("-1").trim()
    if (base.equals("m", true) || base.equals("meter", true) || base.equals("metre", true)) {
        return if (!reciprocal) Calibration.fromMetres(sx, sy, source)
        else Calibration(sx * 1e-9, sy * 1e-9, LengthUnit.NM, true, source) // 1/m -> 1/nm
    }
    val unit = LengthUnit.parse(base) ?: return Calibration.NONE.copy(source = "$source (unknown unit '$u')")
    if (unit == LengthUnit.PIXEL) return Calibration.NONE.copy(source = source)
    return Calibration(sx, sy, unit, reciprocal, source)
}
