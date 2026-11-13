/*
 * TEM Lab
 * Copyright © 2026 F. Mares-Briones. All rights reserved.
 */
package com.temlab.core

import com.temlab.core.image.LengthUnit
import com.temlab.core.image.PixelType
import com.temlab.core.io.ByteSource
import com.temlab.core.io.ScientificImageIO
import com.temlab.core.io.TiffReader
import com.temlab.core.io.TiffWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class FormatValidationTest {
    private val w = 61; private val h = 47

    private fun ramp(max: Double, signed: Boolean = false) = DoubleArray(w * h) { val v = (it * 7919L % (w * h)).toDouble() / (w * h) * max; if (signed) v - max / 2 else v }

    @Test fun dm3AndDm4AllDataTypesBothByteOrders() {
        val cases = listOf(10 to (65535.0 to PixelType.UINT16), 1 to (30000.0 to PixelType.INT16), 2 to (1234.5 to PixelType.FLOAT32),
            7 to (2e9 to PixelType.INT32), 6 to (255.0 to PixelType.UINT8), 12 to (1e5 to PixelType.FLOAT64))
        for (version in listOf(3, 4)) for (le in listOf(true, false)) for ((type, spec) in cases) {
            val (max, ptype) = spec
            val vals = ramp(max, signed = type == 1 || type == 7).map { if (type == 2 || type == 12) it else Math.rint(it) }.toDoubleArray()
            val bytes = DmTestWriter.image(version, le, w, h, type, vals, 0.0153, "nm")
            val img = ScientificImageIO.read(ByteSource.of(bytes), "test.dm$version").first()
            val tag = "DM$version le=$le type=$type"
            assertEquals(tag, w, img.width); assertEquals(tag, h, img.height)
            assertEquals(tag, ptype, img.pixelType)
            for (i in vals.indices) assertEquals("$tag px $i", vals[i], img.pixels[i].toDouble(), maxOf(1e-6, Math.ulp(vals[i].toFloat()).toDouble()))
            assertEquals(tag, LengthUnit.NM, img.calibration.unit)
            assertEquals(tag, 0.0153, img.calibration.pixelWidth, 1e-7)
            assertEquals(tag, "200000.0", img.metadata["Microscope Info.Voltage"])
            assertEquals(tag, "TestScope", img.metadata["Microscope Info.Name"])
            assertEquals(tag, "test image", img.metadata["Image name"])
        }
    }

    @Test fun dmMicronAndReciprocalUnits() {
        val vals = ramp(1000.0)
        val um = ScientificImageIO.read(ByteSource.of(DmTestWriter.image(3, true, w, h, 2, vals, 0.25, "µm")), "a.dm3").first()
        assertEquals(LengthUnit.UM, um.calibration.unit); assertFalse(um.calibration.reciprocal)
        val rec = ScientificImageIO.read(ByteSource.of(DmTestWriter.image(4, true, w, h, 2, vals, 0.0042, "1/nm")), "b.dm4").first()
        assertTrue(rec.calibration.reciprocal); assertEquals("1/nm", rec.calibration.unitLabel)
    }

    @Test fun tiffBitDepthsCompressionsAndPages() {
        val pages = listOf(
            TiffTestWriter.Page(w, h, 8, 1, ramp(255.0).map { Math.rint(it) }.toDoubleArray()),
            TiffTestWriter.Page(w, h, 16, 1, ramp(65535.0).map { Math.rint(it) }.toDoubleArray(), TiffTestWriter.Comp.PACKBITS, rowsPerStrip = 5),
            TiffTestWriter.Page(w, h, 16, 2, ramp(30000.0, true).map { Math.rint(it) }.toDoubleArray(), TiffTestWriter.Comp.DEFLATE, predictor = true),
            TiffTestWriter.Page(w, h, 32, 3, ramp(3.7, true), TiffTestWriter.Comp.NONE),
            TiffTestWriter.Page(w, h, 8, 1, ramp(255.0).map { Math.rint(it) }.toDoubleArray(), TiffTestWriter.Comp.LZW, rowsPerStrip = 13),
            TiffTestWriter.Page(w, h, 16, 1, ramp(4095.0).map { Math.rint(it) }.toDoubleArray(), TiffTestWriter.Comp.LZW, predictor = true),
        )
        for (le in listOf(true, false)) {
            val bytes = TiffTestWriter.write(pages, le)
            val imgs = TiffReader.read(ByteSource.of(bytes), "multi")
            assertEquals(pages.size, imgs.size)
            val types = listOf(PixelType.UINT8, PixelType.UINT16, PixelType.INT16, PixelType.FLOAT32, PixelType.UINT8, PixelType.UINT16)
            for ((k, p) in pages.withIndex()) {
                assertEquals("page $k type", types[k], imgs[k].pixelType)
                for (i in p.values.indices) assertEquals("le=$le page $k px $i", p.values[i], imgs[k].pixels[i].toDouble(), 1e-6)
            }
        }
    }

    @Test fun tiffRgbConvertedToLuminance() {
        val vals = ramp(255.0).map { Math.rint(it) }.toDoubleArray()
        val img = TiffReader.read(ByteSource.of(TiffTestWriter.write(listOf(TiffTestWriter.Page(w, h, 8, 1, vals, rgb = true)), true)), "rgb").first()
        assertEquals(PixelType.RGB24, img.pixelType)
        val i = 100
        val r = (vals[i] * 1 / 3).toInt(); val g = (vals[i] * 2 / 3).toInt(); val b = vals[i].toInt()
        assertEquals(0.299 * r + 0.587 * g + 0.114 * b, img.pixels[i].toDouble(), 1e-3)
    }

    @Test fun imageJAndFeiCalibration() {
        val vals = ramp(100.0)
        val ij = TiffReader.read(ByteSource.of(TiffTestWriter.write(listOf(TiffTestWriter.Page(w, h, 32, 3, vals, description = "ImageJ=1.54f\nunit=nm\n", xres = 50.0)), false)), "ij").first()
        assertEquals(LengthUnit.NM, ij.calibration.unit); assertEquals(0.02, ij.calibration.pixelWidth, 1e-9)
        val fei = TiffReader.read(ByteSource.of(TiffTestWriter.write(listOf(TiffTestWriter.Page(w, h, 16, 1, vals.map { Math.rint(it) }.toDoubleArray(),
            fei = "[Scan]\r\nPixelWidth=2.5e-009\r\nPixelHeight=2.5e-009\r\n[Beam]\r\nHV=15000\r\n")), true)), "fei").first()
        assertEquals(LengthUnit.NM, fei.calibration.unit); assertEquals(2.5, fei.calibration.pixelWidth, 1e-9)
        assertEquals("15000", fei.metadata["FEI.Beam.HV"])
        val none = TiffReader.read(ByteSource.of(TiffTestWriter.write(listOf(TiffTestWriter.Page(w, h, 8, 1, vals)), true)), "plain").first()
        assertFalse(none.calibration.isCalibrated)
    }

    @Test fun floatTiffWriterRoundTripKeepsPrecisionAndCalibration() {
        val img = Synthetic.lattice(64, 40, 0.0123, 0.0123, listOf(Synthetic.Fringe(0.2, 30.0)))
        val bos = ByteArrayOutputStream()
        TiffWriter.writeFloat(img, bos, "IFFT reconstruction")
        val back = TiffReader.read(ByteSource.of(bos.toByteArray()), "rt").first()
        assertEquals(PixelType.FLOAT32, back.pixelType)
        for (i in img.pixels.indices) assertEquals(img.pixels[i], back.pixels[i], 0f)
        assertEquals(0.0123, back.calibration.pixelWidth, 1e-9)
        assertEquals(LengthUnit.NM, back.calibration.unit)
    }

    /**
     * Optional validation with REAL instrument files: put .dm3/.dm4/.tif files in testdata/real/ together with
     * a sidecar "<file>.expected" containing lines like  width=2048 / height=2048 / pixel=0.0245 / unit=nm
     * (values taken from DigitalMicrograph/ImageJ). The test is skipped when no files are present.
     */
    @Test fun realFilesIfAvailable() {
        val dir = File(System.getProperty("temlab.realdata", "testdata/real"))
        val files = dir.listFiles()?.filter { ScientificImageIO.canRead(it.name) } ?: emptyList()
        if (files.isEmpty()) { println("No real files in $dir – skipped"); return }
        for (f in files) {
            val img = ScientificImageIO.read(f).first()
            println("${f.name}: ${img.width}x${img.height} ${img.pixelType} cal=${img.calibration} min/max=${img.minMax()}")
            val exp = File(f.path + ".expected")
            if (exp.exists()) {
                val kv = exp.readLines().mapNotNull { l -> l.split('=').takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
                kv["width"]?.let { assertEquals(f.name, it.toInt(), img.width) }
                kv["height"]?.let { assertEquals(f.name, it.toInt(), img.height) }
                kv["pixel"]?.let { assertEquals(f.name, it.toDouble(), img.calibration.pixelWidth, it.toDouble() * 1e-4) }
                kv["unit"]?.let { assertEquals(f.name, it, img.calibration.unitLabel) }
                kv["mean"]?.let { assertEquals(f.name, it.toDouble(), img.pixels.average(), kotlin.math.abs(it.toDouble()) * 1e-4) }
            }
        }
    }
}
