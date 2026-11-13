package com.temlab.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Independent writers that produce files following the published DM3/DM4 and TIFF 6.0 specifications.
 * They are used to validate the readers byte-for-byte against known content.
 */
object DmTestWriter {
    sealed class T
    class G(vararg val entries: Pair<String, T>) : T()
    class I32(val v: Int) : T()
    class F32(val v: Float) : T()
    class F64(val v: Double) : T()
    class Str(val s: String) : T()               // written as array of uint16 (DM convention)
    class Arr(val type: Int, val count: Int, val bytes: ByteArray) : T()

    fun write(version: Int, littleEndian: Boolean, root: G): ByteArray {
        val dm4 = version == 4
        val body = ByteArrayOutputStream()
        writeGroup(body, root, dm4, littleEndian)
        val out = ByteBuffer.allocate(16 + body.size() + 8).order(ByteOrder.BIG_ENDIAN)
        out.putInt(version)
        if (dm4) out.putLong(body.size().toLong()) else out.putInt(body.size())
        out.putInt(if (littleEndian) 1 else 0)
        out.put(body.toByteArray())
        out.putInt(0); out.putInt(0)
        return out.array().copyOf(out.position())
    }

    private fun be(n: Long, bytes: Int): ByteArray = ByteArray(bytes) { ((n shr (8 * (bytes - 1 - it))) and 0xFF).toByte() }

    private fun writeGroup(o: ByteArrayOutputStream, g: G, dm4: Boolean, le: Boolean) {
        o.write(0); o.write(1)
        o.write(be(g.entries.size.toLong(), if (dm4) 8 else 4))
        for ((label, t) in g.entries) {
            val lb = label.toByteArray(Charsets.ISO_8859_1)
            val content = ByteArrayOutputStream()
            if (t is G) writeGroup(content, t, dm4, le) else writeData(content, t, dm4, le)
            o.write(if (t is G) 20 else 21)
            o.write(be(lb.size.toLong(), 2)); o.write(lb)
            if (dm4) o.write(be(content.size().toLong(), 8))
            o.write(content.toByteArray())
        }
    }

    private fun writeData(o: ByteArrayOutputStream, t: T, dm4: Boolean, le: Boolean) {
        val ib = if (dm4) 8 else 4
        val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        o.write("%%%%".toByteArray())
        fun info(vararg v: Long) { o.write(be(v.size.toLong(), ib)); v.forEach { o.write(be(it, ib)) } }
        when (t) {
            is I32 -> { info(3); o.write(ByteBuffer.allocate(4).order(order).putInt(t.v).array()) }
            is F32 -> { info(6); o.write(ByteBuffer.allocate(4).order(order).putFloat(t.v).array()) }
            is F64 -> { info(7); o.write(ByteBuffer.allocate(8).order(order).putDouble(t.v).array()) }
            is Str -> {
                info(20, 4, t.s.length.toLong())
                val b = ByteBuffer.allocate(t.s.length * 2).order(order); t.s.forEach { b.putChar(it) }; o.write(b.array())
            }
            is Arr -> { info(20, t.type.toLong(), t.count.toLong()); o.write(t.bytes) }
            is G -> error("group")
        }
    }

    /** Builds a DM file with a thumbnail and one image of the given DM data type. */
    fun image(version: Int, le: Boolean, w: Int, h: Int, dmType: Int, values: DoubleArray, scale: Double, unit: String): ByteArray {
        val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val (elemType, bpp) = when (dmType) { 1 -> 2 to 2; 10 -> 4 to 2; 2 -> 6 to 4; 7 -> 3 to 4; 6 -> 10 to 1; 12 -> 7 to 8; else -> error("type") }
        val b = ByteBuffer.allocate(w * h * bpp).order(order)
        for (v in values) when (dmType) {
            1 -> b.putShort(v.toInt().toShort()); 10 -> b.putShort(v.toInt().toShort()); 2 -> b.putFloat(v.toFloat())
            7 -> b.putInt(v.toInt()); 6 -> b.put(v.toInt().toByte()); 12 -> b.putDouble(v)
        }
        val thumb = Arr(10, 64 * 64, ByteArray(64 * 64) { 7 })
        fun imageGroup(data: Arr, ww: Int, hh: Int, type: Int, sc: Double, u: String) = G(
            "ImageData" to G(
                "Calibrations" to G(
                    "Brightness" to G("Origin" to F32(0f), "Scale" to F32(1f), "Units" to Str("")),
                    "Dimension" to G(
                        "" to G("Origin" to F32(0f), "Scale" to F32(sc.toFloat()), "Units" to Str(u)),
                        "" to G("Origin" to F32(0f), "Scale" to F32(sc.toFloat()), "Units" to Str(u)),
                    ),
                ),
                "Data" to data,
                "DataType" to I32(type),
                "Dimensions" to G("" to I32(ww), "" to I32(hh)),
                "PixelDepth" to I32(data.bytes.size / (ww * hh)),
            ),
            "ImageTags" to G("Microscope Info" to G("Voltage" to F64(200000.0), "Indicated Magnification" to F64(800000.0), "Name" to Str("TestScope"))),
            "Name" to Str("test image"),
        )
        val root = G(
            "ApplicationBounds" to G("" to I32(0)),
            "ImageList" to G(
                "" to imageGroup(thumb, 64, 64, 6, 1.0, ""),
                "" to imageGroup(Arr(elemType, w * h, b.array()), w, h, dmType, scale, unit),
            ),
        )
        return write(version, le, root)
    }
}

object TiffTestWriter {
    enum class Comp(val code: Int) { NONE(1), PACKBITS(32773), DEFLATE(8), LZW(5) }

    class Page(val w: Int, val h: Int, val bits: Int, val format: Int, val values: DoubleArray, val comp: Comp = Comp.NONE,
               val predictor: Boolean = false, val description: String? = null, val xres: Double = 0.0, val rowsPerStrip: Int = h,
               val fei: String? = null, val rgb: Boolean = false)

    fun write(pages: List<Page>, littleEndian: Boolean): ByteArray {
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val out = ByteArrayOutputStream()
        out.write(if (littleEndian) byteArrayOf(0x49, 0x49) else byteArrayOf(0x4D, 0x4D))
        out.write(ByteBuffer.allocate(6).order(order).putShort(42).putInt(8).array())
        // Layout: for each page: IFD then its data blocks.
        var pos = 8L
        val blobs = ArrayList<ByteArray>()
        for ((pi, p) in pages.withIndex()) {
            val spp = if (p.rgb) 3 else 1
            val bpp = p.bits / 8
            val strips = (p.h + p.rowsPerStrip - 1) / p.rowsPerStrip
            val stripData = (0 until strips).map { s ->
                val rows = minOf(p.rowsPerStrip, p.h - s * p.rowsPerStrip)
                val raw = ByteBuffer.allocate(rows * p.w * bpp * spp).order(order)
                for (y in 0 until rows) for (x in 0 until p.w) {
                    val v = p.values[(s * p.rowsPerStrip + y) * p.w + x]
                    repeat(spp) { c ->
                        val vv = if (p.rgb) v * (c + 1) / 3.0 else v
                        when (p.bits) {
                            8 -> raw.put(vv.toInt().toByte())
                            16 -> raw.putShort(vv.toInt().toShort())
                            32 -> if (p.format == 3) raw.putFloat(vv.toFloat()) else raw.putInt(vv.toInt())
                            64 -> raw.putDouble(vv)
                        }
                    }
                }
                var data = raw.array()
                if (p.predictor) data = predict(data, p.w * spp, rows, bpp, order)
                when (p.comp) {
                    Comp.NONE -> data
                    Comp.PACKBITS -> packBits(data)
                    Comp.DEFLATE -> deflate(data)
                    Comp.LZW -> lzw(data)
                }
            }
            val desc = (p.description ?: "").toByteArray(Charsets.ISO_8859_1) + 0
            val fei = p.fei?.toByteArray(Charsets.ISO_8859_1)?.plus(0)
            val tags = ArrayList<Triple<Int, Int, Any>>() // tag, type, value(s)
            tags += Triple(256, 4, p.w); tags += Triple(257, 4, p.h)
            tags += Triple(258, 3, IntArray(spp) { p.bits }); tags += Triple(259, 3, p.comp.code); tags += Triple(262, 3, if (p.rgb) 2 else 1)
            if (p.description != null) tags += Triple(270, 2, desc)
            tags += Triple(273, 4, LongArray(strips)); tags += Triple(277, 3, spp); tags += Triple(278, 4, p.rowsPerStrip)
            tags += Triple(279, 4, LongArray(strips) { stripData[it].size.toLong() })
            if (p.xres > 0) { tags += Triple(282, 5, p.xres); tags += Triple(283, 5, p.xres) }
            if (p.predictor) tags += Triple(317, 3, 2)
            tags += Triple(339, 3, IntArray(spp) { p.format })
            if (fei != null) tags += Triple(34682, 2, fei)
            val ifdSize = 2 + tags.size * 12 + 4
            var extra = pos + ifdSize
            val extraBuf = ByteArrayOutputStream()
            val ifd = ByteBuffer.allocate(ifdSize).order(order)
            ifd.putShort(tags.size.toShort())
            var dataStart = 0L
            // first pass: compute size of out-of-line values
            fun outOfLine(v: Any, type: Int): ByteArray? = when (v) {
                is IntArray -> if (v.size * 2 > 4) ByteBuffer.allocate(v.size * 2).order(order).also { b -> v.forEach { b.putShort(it.toShort()) } }.array() else null
                is LongArray -> if (v.size * 4 > 4) ByteBuffer.allocate(v.size * 4).order(order).array() else null
                is ByteArray -> if (v.size > 4) v else null
                is Double -> ByteBuffer.allocate(8).order(order).putInt(Math.round(v * 10000).toInt()).putInt(10000).array()
                else -> null
            }
            val oolSizes = tags.map { (outOfLine(it.third, it.second)?.size ?: 0).let { s -> s + (s and 1) } }
            dataStart = extra + oolSizes.sum()
            val stripOffsets = LongArray(strips); var sp = dataStart
            for (s in 0 until strips) { stripOffsets[s] = sp; sp += stripData[s].size }
            val nextIfd = if (pi < pages.size - 1) sp else 0L
            for ((tag, type, v0) in tags) {
                val v = if (tag == 273) stripOffsets else v0
                ifd.putShort(tag.toShort()); ifd.putShort(type.toShort())
                val count = when (v) { is IntArray -> v.size; is LongArray -> v.size; is ByteArray -> v.size; else -> 1 }
                ifd.putInt(count)
                val ool = if (tag == 273 && strips > 1) ByteBuffer.allocate(strips * 4).order(order).also { b -> stripOffsets.forEach { b.putInt(it.toInt()) } }.array()
                          else if (tag == 279 && strips > 1) ByteBuffer.allocate(strips * 4).order(order).also { b -> (v as LongArray).forEach { b.putInt(it.toInt()) } }.array()
                          else outOfLine(v, type)
                if (ool != null) {
                    ifd.putInt(extra.toInt()); extraBuf.write(ool); if (ool.size % 2 == 1) extraBuf.write(0); extra += ool.size + (ool.size and 1)
                } else {
                    val slot = ByteBuffer.allocate(4).order(order)
                    when (v) {
                        is Int -> if (type == 3) slot.putShort(v.toShort()) else slot.putInt(v)
                        is IntArray -> v.forEach { slot.putShort(it.toShort()) }
                        is LongArray -> v.forEach { slot.putInt(it.toInt()) }
                        is ByteArray -> slot.put(v)
                    }
                    ifd.put(slot.array())
                }
            }
            ifd.putInt(nextIfd.toInt())
            out.write(ifd.array()); out.write(extraBuf.toByteArray())
            while (out.size() < dataStart) out.write(0)
            stripData.forEach { out.write(it) }
            pos = out.size().toLong()
            blobs += ByteArray(0)
        }
        return out.toByteArray()
    }

    private fun predict(d: ByteArray, samplesPerRow: Int, rows: Int, bpp: Int, order: ByteOrder): ByteArray {
        val b = ByteBuffer.wrap(d.copyOf()).order(order)
        val src = ByteBuffer.wrap(d).order(order)
        for (r in 0 until rows) for (i in samplesPerRow - 1 downTo 1) {
            val p = (r * samplesPerRow + i) * bpp; val q = p - bpp
            when (bpp) { 1 -> b.put(p, (d[p] - d[q]).toByte()); 2 -> b.putShort(p, (src.getShort(p) - src.getShort(q)).toShort()) }
        }
        return b.array()
    }

    fun packBits(d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); var i = 0
        while (i < d.size) {
            var run = 1
            while (i + run < d.size && run < 128 && d[i + run] == d[i]) run++
            if (run >= 2) { o.write(-(run - 1)); o.write(d[i].toInt()); i += run }
            else {
                var lit = 1
                while (i + lit < d.size && lit < 128 && !(i + lit + 1 < d.size && d[i + lit] == d[i + lit + 1])) lit++
                o.write(lit - 1); o.write(d, i, lit); i += lit
            }
        }
        return o.toByteArray()
    }

    fun deflate(d: ByteArray): ByteArray {
        val def = Deflater(); def.setInput(d); def.finish()
        val o = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!def.finished()) { val n = def.deflate(buf); o.write(buf, 0, n) }
        def.end(); return o.toByteArray()
    }

    /** TIFF LZW encoder (MSB-first, early change), independent from the decoder under test. */
    fun lzw(d: ByteArray): ByteArray {
        val o = BitWriter()
        var dict = HashMap<String, Int>(); var next = 258; var width = 9
        fun reset() { dict = HashMap(); for (i in 0 until 256) dict[i.toChar().toString()] = i; next = 258; width = 9 }
        reset(); o.write(256, width)
        var w = ""
        for (b in d) {
            val c = (b.toInt() and 0xFF).toChar()
            val wc = w + c
            if (dict.containsKey(wc)) { w = wc; continue }
            o.write(dict[w]!!, width)
            dict[wc] = next++
            if (next + 1 >= 4094) { o.write(256, width); reset() } // write clear before width overflows
            else width = when { next + 1 > 2048 -> 12; next + 1 > 1024 -> 11; next + 1 > 512 -> 10; else -> 9 }
            w = c.toString()
        }
        if (w.isNotEmpty()) o.write(dict[w]!!, width)
        // after emitting the last code a new entry would have been added
        next++
        width = when { next + 1 > 2048 -> 12; next + 1 > 1024 -> 11; next + 1 > 512 -> 10; else -> 9 }
        o.write(257, width)
        return o.bytes()
    }

    private class BitWriter {
        private val o = ByteArrayOutputStream(); private var acc = 0L; private var n = 0
        fun write(code: Int, width: Int) { acc = (acc shl width) or code.toLong(); n += width; while (n >= 8) { o.write(((acc shr (n - 8)) and 0xFF).toInt()); n -= 8 } }
        fun bytes(): ByteArray { if (n > 0) o.write(((acc shl (8 - n)) and 0xFF).toInt()); n = 0; return o.toByteArray() }
    }
}
