package com.temlab.core.io

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Random-access view on a file. Files are memory-mapped so multi-hundred-MB micrographs are not copied
 * into the Java heap; only the decoded float image is allocated.
 */
class ByteSource private constructor(private val buf: ByteBuffer, private val closer: (() -> Unit)?) : AutoCloseable {
    val size: Long get() = buf.capacity().toLong()
    var pos: Long = 0

    fun u8(at: Long): Int = buf.get(at.toInt()).toInt() and 0xFF
    fun bytes(at: Long, n: Int): ByteArray { val b = ByteArray(n); for (i in 0 until n) b[i] = buf.get((at + i).toInt()); return b }

    fun i16(at: Long, le: Boolean): Int { buf.order(order(le)); return buf.getShort(at.toInt()).toInt() }
    fun u16(at: Long, le: Boolean): Int = i16(at, le) and 0xFFFF
    fun i32(at: Long, le: Boolean): Int { buf.order(order(le)); return buf.getInt(at.toInt()) }
    fun u32(at: Long, le: Boolean): Long = i32(at, le).toLong() and 0xFFFFFFFFL
    fun i64(at: Long, le: Boolean): Long { buf.order(order(le)); return buf.getLong(at.toInt()) }
    fun f32(at: Long, le: Boolean): Float { buf.order(order(le)); return buf.getFloat(at.toInt()) }
    fun f64(at: Long, le: Boolean): Double { buf.order(order(le)); return buf.getDouble(at.toInt()) }

    // Sequential helpers
    fun readU8(): Int = u8(pos).also { pos += 1 }
    fun readI16(le: Boolean): Int = i16(pos, le).also { pos += 2 }
    fun readI32(le: Boolean): Int = i32(pos, le).also { pos += 4 }
    fun readI64(le: Boolean): Long = i64(pos, le).also { pos += 8 }
    fun readBytes(n: Int): ByteArray = bytes(pos, n).also { pos += n }

    /** A duplicate view for bulk conversion. */
    fun slice(at: Long, len: Int, le: Boolean): ByteBuffer {
        val d = buf.duplicate()
        d.position(at.toInt()); d.limit((at + len).toInt())
        return d.slice().order(order(le))
    }

    override fun close() { closer?.invoke() }

    private fun order(le: Boolean) = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    companion object {
        fun of(bytes: ByteArray) = ByteSource(ByteBuffer.wrap(bytes), null)
        fun of(file: File): ByteSource {
            val raf = RandomAccessFile(file, "r")
            require(raf.length() < Int.MAX_VALUE) { "Files larger than 2 GB are not supported" }
            val mapped = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            return ByteSource(mapped) { raf.close() }
        }
    }
}

class ImageFormatException(msg: String) : Exception(msg)
