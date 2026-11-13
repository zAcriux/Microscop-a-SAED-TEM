package com.temlab.core.io

import com.temlab.core.image.Calibration
import com.temlab.core.image.ImageData
import com.temlab.core.image.LengthUnit
import com.temlab.core.image.PixelType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * TIFF / BigTIFF reader supporting 1/8/16/32/64-bit integer and floating point grayscale, RGB(A) (converted to
 * luminance), multi-page files, strips and tiles, and compression none / LZW / PackBits / Deflate with optional
 * horizontal predictor. Calibration is taken (in order of preference) from:
 *  - ImageJ ImageDescription (unit=..., with XResolution/YResolution in pixels per unit),
 *  - FEI/Thermo Fisher SEM/TEM metadata tag 34682 (PixelWidth/PixelHeight in metres),
 *  - Zeiss SEM tag 34118 ("Image Pixel Size = ..."),
 *  - standard XResolution/YResolution with ResolutionUnit (cm/inch; flagged as low-confidence).
 */
object TiffReader {

    private class Entry(val tag: Int, val type: Int, val count: Long, val valueOffset: Long)

    private class Ifd(val entries: Map<Int, Entry>)

    fun isTiff(src: ByteSource): Boolean {
        if (src.size < 8) return false
        val a = src.u8(0); val b = src.u8(1)
        if (!((a == 0x49 && b == 0x49) || (a == 0x4D && b == 0x4D))) return false
        val v = src.u16(2, a == 0x49)
        return v == 42 || v == 43
    }

    fun pageCount(src: ByteSource): Int = readIfds(src).second.size

    fun read(src: ByteSource, name: String = "tiff", maxPages: Int = 64): List<ImageData> {
        val (le, ifds, big) = readIfds(src).let { Triple(it.first, it.second, it.third) }
        val out = ArrayList<ImageData>()
        for ((i, ifd) in ifds.withIndex()) {
            if (out.size >= maxPages) break
            val sub = (ifd.entries[254]?.let { value(src, it, le, big, 0) } ?: 0L).toLong()
            if (sub and 1L != 0L && ifds.size > 1) continue // reduced-resolution thumbnails
            val img = decodePage(src, ifd, le, big) ?: continue
            img.name = if (ifds.size > 1) "$name [p${i + 1}]" else name
            img.metadata["Page"] = "${i + 1} / ${ifds.size}"
            out += img
        }
        if (out.isEmpty()) throw ImageFormatException("No decodable TIFF page")
        return out
    }

    private fun readIfds(src: ByteSource): Triple<Boolean, List<Ifd>, Boolean> {
        val le = src.u8(0) == 0x49
        val big = src.u16(2, le) == 43
        var off = if (big) src.i64(8, le) else src.u32(4, le)
        val ifds = ArrayList<Ifd>()
        val seen = HashSet<Long>()
        while (off != 0L && off < src.size && seen.add(off) && ifds.size < 10000) {
            val n = if (big) src.i64(off, le) else src.u16(off, le).toLong()
            var p = off + if (big) 8 else 2
            val map = HashMap<Int, Entry>()
            for (k in 0 until n) {
                val tag = src.u16(p, le); val type = src.u16(p + 2, le)
                val count = if (big) src.i64(p + 4, le) else src.u32(p + 4, le)
                val valPos = p + if (big) 12 else 8
                val inline = typeSize(type) * count <= (if (big) 8 else 4)
                val vo = if (inline) valPos else if (big) src.i64(valPos, le) else src.u32(valPos, le)
                map[tag] = Entry(tag, type, count, vo)
                p += if (big) 20 else 12
            }
            ifds += Ifd(map)
            off = if (big) src.i64(p, le) else src.u32(p, le)
        }
        return Triple(le, ifds, big)
    }

    private fun typeSize(t: Int) = when (t) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11, 13 -> 4; 5, 10, 12, 16, 17, 18 -> 8; else -> 1 }

    private fun value(src: ByteSource, e: Entry, le: Boolean, big: Boolean, i: Int): Number {
        val p = e.valueOffset + i.toLong() * typeSize(e.type)
        return when (e.type) {
            1, 7 -> src.u8(p); 6 -> src.u8(p).toByte().toInt()
            3 -> src.u16(p, le); 8 -> src.i16(p, le)
            4, 13 -> src.u32(p, le); 9 -> src.i32(p, le)
            5 -> { val n = src.u32(p, le); val d = src.u32(p + 4, le); if (d == 0L) 0.0 else n.toDouble() / d }
            10 -> { val n = src.i32(p, le); val d = src.i32(p + 4, le); if (d == 0) 0.0 else n.toDouble() / d }
            11 -> src.f32(p, le); 12 -> src.f64(p, le)
            16, 17, 18 -> src.i64(p, le)
            else -> 0
        }
    }

    private fun longs(src: ByteSource, e: Entry?, le: Boolean, big: Boolean): LongArray =
        if (e == null) LongArray(0) else LongArray(e.count.toInt()) { value(src, e, le, big, it).toLong() }

    private fun ascii(src: ByteSource, e: Entry?): String? =
        e?.let { String(src.bytes(it.valueOffset, it.count.toInt()), Charsets.ISO_8859_1).trimEnd('\u0000') }

    private fun decodePage(src: ByteSource, ifd: Ifd, le: Boolean, big: Boolean): ImageData? {
        val e = ifd.entries
        fun num(tag: Int, def: Long): Long = e[tag]?.let { value(src, it, le, big, 0).toLong() } ?: def
        val w = num(256, 0).toInt(); val h = num(257, 0).toInt()
        if (w <= 0 || h <= 0) return null
        val spp = num(277, 1).toInt()
        val bps = num(258, 8).toInt()
        val compression = num(259, 1).toInt()
        val photometric = num(262, 1).toInt()
        val sampleFormat = num(339, 1).toInt() // 1 uint, 2 int, 3 float
        val predictor = num(317, 1).toInt()
        val planar = num(284, 1).toInt()
        if (compression !in setOf(1, 5, 8, 32946, 32773)) throw ImageFormatException("Unsupported TIFF compression $compression")
        if (bps !in setOf(1, 8, 16, 32, 64)) throw ImageFormatException("Unsupported bits per sample $bps")

        val tiled = e.containsKey(322)
        val tileW = if (tiled) num(322, w.toLong()).toInt() else w
        val tileH = if (tiled) num(323, h.toLong()).toInt() else num(278, h.toLong()).toInt().coerceAtMost(h)
        val offsets = longs(src, e[if (tiled) 324 else 273], le, big)
        val counts = longs(src, e[if (tiled) 325 else 279], le, big)
        val samplesPerChunk = if (planar == 2) 1 else spp
        val bytesPerSample = maxOf(1, bps / 8)
        val rowBytes = if (bps == 1) (tileW * samplesPerChunk + 7) / 8 else tileW * samplesPerChunk * bytesPerSample
        val chunkBytes = rowBytes * tileH

        val out = FloatArray(w * h)
        val tilesAcross = (w + tileW - 1) / tileW
        val tilesDown = (h + tileH - 1) / tileH
        val chunksPerPlane = tilesAcross * tilesDown
        val bb = ByteOrder.LITTLE_ENDIAN.takeIf { le } ?: ByteOrder.BIG_ENDIAN
        val lum = if (spp >= 3 && photometric == 2) FloatArray(w * h) else null
        val planes = if (planar == 2) minOf(spp, 3) else 1
        for (plane in 0 until planes) for (c in 0 until chunksPerPlane) {
            val idx = plane * chunksPerPlane + c
            if (idx >= offsets.size) break
            val rawLen = if (idx < counts.size) counts[idx].toInt() else chunkBytes
            val raw = src.bytes(offsets[idx], minOf(rawLen.toLong(), src.size - offsets[idx]).toInt())
            var data = when (compression) {
                1 -> raw
                5 -> lzwDecode(raw, chunkBytes)
                8, 32946 -> inflate(raw, chunkBytes)
                32773 -> packBits(raw, chunkBytes)
                else -> raw
            }
            if (data.size < chunkBytes) data = data.copyOf(chunkBytes)
            if (predictor == 2) undoPredictor(data, rowBytes, tileH, samplesPerChunk, bytesPerSample, le)
            val buf = ByteBuffer.wrap(data).order(bb)
            val tx = (c % tilesAcross) * tileW; val ty = (c / tilesAcross) * tileH
            for (yy in 0 until tileH) {
                val y = ty + yy; if (y >= h) break
                for (xx in 0 until tileW) {
                    val x = tx + xx; if (x >= w) break
                    val o = y * w + x
                    if (bps == 1) {
                        val bit = (data[yy * rowBytes + xx / 8].toInt() shr (7 - xx % 8)) and 1
                        out[o] = if (photometric == 0) (1 - bit).toFloat() else bit.toFloat()
                        continue
                    }
                    if (samplesPerChunk == 1) {
                        val v = sample(buf, (yy * rowBytes) + xx * bytesPerSample, bps, sampleFormat)
                        if (planar == 2 && lum != null) lum[o] += v * RGB_W[plane] else out[o] = v
                    } else {
                        val base = yy * rowBytes + xx * samplesPerChunk * bytesPerSample
                        if (lum != null) {
                            var s = 0f
                            for (k in 0 until 3) s += sample(buf, base + k * bytesPerSample, bps, sampleFormat) * RGB_W[k]
                            out[o] = s
                        } else out[o] = sample(buf, base, bps, sampleFormat)
                    }
                }
            }
        }
        if (planar == 2 && lum != null) System.arraycopy(lum, 0, out, 0, out.size)
        if (photometric == 0 && bps in setOf(8, 16) && sampleFormat == 1) { // WhiteIsZero
            val max = ((1 shl bps) - 1).toFloat(); for (i in out.indices) out[i] = max - out[i]
        }
        val ptype = when {
            spp >= 3 -> PixelType.RGB24
            bps == 1 -> PixelType.BINARY
            sampleFormat == 3 && bps == 32 -> PixelType.FLOAT32
            sampleFormat == 3 && bps == 64 -> PixelType.FLOAT64
            sampleFormat == 3 && bps == 16 -> PixelType.FLOAT16
            bps == 8 -> if (sampleFormat == 2) PixelType.INT8 else PixelType.UINT8
            bps == 16 -> if (sampleFormat == 2) PixelType.INT16 else PixelType.UINT16
            bps == 32 -> if (sampleFormat == 2) PixelType.INT32 else PixelType.UINT32
            else -> PixelType.FLOAT64
        }
        val meta = linkedMapOf("Format" to "TIFF", "Bits per sample" to "$bps", "Samples per pixel" to "$spp",
            "Compression" to compressionName(compression), "Dimensions" to "$w x $h")
        val desc = ascii(src, e[270])
        desc?.let { meta["ImageDescription"] = it.take(2000) }
        ascii(src, e[305])?.let { meta["Software"] = it }
        ascii(src, e[306])?.let { meta["DateTime"] = it }
        val fei = ascii(src, e[34682])
        val zeiss = ascii(src, e[34118])
        fei?.let { parseIni(it, meta, "FEI") }
        val cal = calibration(src, e, le, big, desc, fei, zeiss, meta)
        return ImageData(w, h, out, ptype, cal, meta)
    }

    private val RGB_W = floatArrayOf(0.299f, 0.587f, 0.114f)

    private fun compressionName(c: Int) = when (c) { 1 -> "none"; 5 -> "LZW"; 8, 32946 -> "Deflate"; 32773 -> "PackBits"; else -> "$c" }

    private fun sample(b: ByteBuffer, off: Int, bps: Int, fmt: Int): Float = when (bps) {
        8 -> if (fmt == 2) b.get(off).toFloat() else (b.get(off).toInt() and 0xFF).toFloat()
        16 -> when (fmt) { 2 -> b.getShort(off).toFloat(); 3 -> halfToFloat(b.getShort(off).toInt()); else -> (b.getShort(off).toInt() and 0xFFFF).toFloat() }
        32 -> when (fmt) { 3 -> b.getFloat(off); 2 -> b.getInt(off).toFloat(); else -> (b.getInt(off).toLong() and 0xFFFFFFFFL).toFloat() }
        64 -> if (fmt == 3) b.getDouble(off).toFloat() else b.getLong(off).toFloat()
        else -> 0f
    }

    fun halfToFloat(h: Int): Float {
        val s = (h shr 15) and 1; val e = (h shr 10) and 0x1F; val m = h and 0x3FF
        val v = when (e) {
            0 -> m / 1024.0 * Math.pow(2.0, -14.0)
            31 -> if (m == 0) Double.POSITIVE_INFINITY else Double.NaN
            else -> (1 + m / 1024.0) * Math.pow(2.0, (e - 15).toDouble())
        }
        return (if (s == 1) -v else v).toFloat()
    }

    private fun undoPredictor(d: ByteArray, rowBytes: Int, rows: Int, spp: Int, bytesPer: Int, le: Boolean) {
        val bb = ByteBuffer.wrap(d).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        for (r in 0 until rows) {
            val base = r * rowBytes
            val n = rowBytes / bytesPer
            for (i in spp until n) {
                val p = base + i * bytesPer; val q = base + (i - spp) * bytesPer
                when (bytesPer) {
                    1 -> d[p] = (d[p] + d[q]).toByte()
                    2 -> bb.putShort(p, (bb.getShort(p) + bb.getShort(q)).toShort())
                    4 -> bb.putInt(p, bb.getInt(p) + bb.getInt(q))
                }
            }
        }
    }

    private fun calibration(src: ByteSource, e: Map<Int, Entry>, le: Boolean, big: Boolean, desc: String?, fei: String?, zeiss: String?, meta: MutableMap<String, String>): Calibration {
        val xr = e[282]?.let { value(src, it, le, big, 0).toDouble() } ?: 0.0
        val yr = e[283]?.let { value(src, it, le, big, 0).toDouble() } ?: xr
        // 1. ImageJ
        if (desc != null && desc.startsWith("ImageJ")) {
            val kv = desc.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val unit = kv["unit"]?.replace("\\u00B5", "µ")
            if (unit != null && xr > 0) {
                val c = calibrationFromUnitString(1.0 / xr, 1.0 / (if (yr > 0) yr else xr), unit, "ImageJ TIFF")
                if (c.isCalibrated) return c
            }
        }
        // 2. FEI / Thermo Fisher
        if (fei != null) {
            val pw = Regex("(?m)^PixelWidth=([0-9.eE+-]+)").find(fei)?.groupValues?.get(1)?.toDoubleOrNull()
            val ph = Regex("(?m)^PixelHeight=([0-9.eE+-]+)").find(fei)?.groupValues?.get(1)?.toDoubleOrNull()
            if (pw != null && pw > 0) return Calibration.fromMetres(pw, ph ?: pw, "FEI/Thermo metadata")
        }
        // 3. Zeiss
        if (zeiss != null) {
            val m = Regex("Image Pixel Size\\s*=\\s*([0-9.]+)\\s*(pm|nm|µm|um|mm)").find(zeiss)
            if (m != null) {
                meta["Zeiss pixel size"] = m.value
                val c = calibrationFromUnitString(m.groupValues[1].toDouble(), m.groupValues[1].toDouble(), m.groupValues[2], "Zeiss metadata")
                if (c.isCalibrated) return c
            }
        }
        // 4. Standard resolution (cm / inch) - only meaningful if it implies a microscopic pixel
        val ru = e[296]?.let { value(src, it, le, big, 0).toInt() } ?: 2
        if (xr > 0 && ru == 3) {
            val pwCm = 1.0 / xr
            if (pwCm < 1e-3) return Calibration.fromMetres(pwCm / 100, (1.0 / (if (yr > 0) yr else xr)) / 100, "TIFF resolution (cm)")
        }
        return Calibration.NONE.copy(source = "TIFF (no spatial calibration found)")
    }

    private fun parseIni(s: String, meta: MutableMap<String, String>, prefix: String) {
        var section = ""
        for (line in s.lines()) {
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) { section = t.trim('[', ']'); continue }
            val kv = t.split('=', limit = 2); if (kv.size != 2) continue
            if (section in setOf("Beam", "EBeam", "Scan", "System", "Stage", "Detectors", "Image") && meta.size < 300)
                meta["$prefix.$section.${kv[0]}"] = kv[1]
        }
    }

    // ---------------------------------------------------------------- decompressors

    fun packBits(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        var i = 0
        while (i < src.size && out.size() < expected) {
            val n = src[i++].toInt()
            if (n >= 0) { val len = minOf(n + 1, src.size - i); out.write(src, i, len); i += len }
            else if (n != -128) { val b = src[i++].toInt(); repeat(-n + 1) { out.write(b) } }
        }
        return out.toByteArray()
    }

    fun inflate(src: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(src)
        val out = ByteArrayOutputStream(expected)
        val buf = ByteArray(65536)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    /** TIFF LZW (MSB-first codes, early change). */
    fun lzwDecode(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(maxOf(expected, 16))
        val dict = arrayOfNulls<ByteArray>(4096)
        for (i in 0 until 256) dict[i] = byteArrayOf(i.toByte())
        var next = 258; var codeLen = 9
        var bitPos = 0L
        val totalBits = src.size * 8L
        fun readCode(): Int {
            if (bitPos + codeLen > totalBits) return 257
            var code = 0
            for (k in 0 until codeLen) {
                val byte = src[((bitPos + k) / 8).toInt()].toInt()
                val bit = (byte shr (7 - ((bitPos + k) % 8).toInt())) and 1
                code = (code shl 1) or bit
            }
            bitPos += codeLen
            return code
        }
        var prev: ByteArray? = null
        while (true) {
            val code = readCode()
            if (code == 257) break
            if (code == 256) {
                next = 258; codeLen = 9; prev = null
                for (i in 258 until 4096) dict[i] = null
                continue
            }
            val entry: ByteArray = when {
                code < next && dict[code] != null -> dict[code]!!
                code == next && prev != null -> prev + prev[0]
                else -> break
            }
            out.write(entry)
            if (prev != null && next < 4096) { dict[next++] = prev + entry[0] }
            prev = entry
            codeLen = when { next + 1 >= 2048 -> 12; next + 1 >= 1024 -> 11; next + 1 >= 512 -> 10; else -> 9 }
        }
        return out.toByteArray()
    }
}
