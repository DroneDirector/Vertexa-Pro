package com.dronedirector.vertexapro.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.nio.ByteBuffer

/** Decodes textures (PNG/JPG/WebP/BMP/HEIF via Android, TGA via own decoder), downsampled to [maxDim]. */
object TextureDecoder {

    fun decodeUri(context: Context, uri: Uri, name: String, maxDim: Int): Bitmap? {
        val cr = context.contentResolver
        if (name.lowercase().endsWith(".tga")) {
            return cr.openInputStream(uri)?.use { decodeTga(it.readBytes(), maxDim) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) {
            // maybe a TGA without extension
            return cr.openInputStream(uri)?.use { decodeTga(it.readBytes(), maxDim) }
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return fit(bmp, maxDim)
    }

    fun decodeBytes(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return decodeTga(bytes, maxDim)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { fit(it, maxDim) }
    }

    fun fromRgba(w: Int, h: Int, rgba: ByteArray, maxDim: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        return fit(bmp, maxDim)
    }

    private fun sample(w: Int, h: Int, maxDim: Int): Int {
        var s = 1
        while (w / s > maxDim * 2 || h / s > maxDim * 2) s *= 2
        return s
    }

    private fun fit(bmp: Bitmap, maxDim: Int): Bitmap {
        val m = maxOf(bmp.width, bmp.height)
        if (m <= maxDim) return bmp
        val sc = maxDim.toFloat() / m
        val out = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * sc).toInt()), maxOf(1, (bmp.height * sc).toInt()), true)
        if (out != bmp) bmp.recycle()
        return out
    }

    /** Minimal TGA reader: true-colour / greyscale, raw or RLE, 8/24/32 bit. */
    fun decodeTga(d: ByteArray, maxDim: Int): Bitmap? = runCatching {
        if (d.size < 18) return null
        fun u8(i: Int) = d[i].toInt() and 0xFF
        val idLen = u8(0)
        val cmapType = u8(1)
        val type = u8(2)
        val cmapLen = u8(5) or (u8(6) shl 8)
        val cmapBits = u8(7)
        val w = u8(12) or (u8(13) shl 8)
        val h = u8(14) or (u8(15) shl 8)
        val bpp = u8(16)
        val desc = u8(17)
        if (w <= 0 || h <= 0 || type !in setOf(2, 3, 10, 11)) return null
        val bytesPP = bpp / 8
        if (bytesPP !in 1..4) return null
        var p = 18 + idLen + if (cmapType == 1) cmapLen * ((cmapBits + 7) / 8) else 0
        val px = IntArray(w * h)
        fun readPixel(at: Int): Int {
            return when (bytesPP) {
                1 -> { val g = u8(at); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                2 -> {
                    val v = u8(at) or (u8(at + 1) shl 8)
                    val r = ((v shr 10) and 31) * 255 / 31
                    val g = ((v shr 5) and 31) * 255 / 31
                    val b = (v and 31) * 255 / 31
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                3 -> (0xFF shl 24) or (u8(at + 2) shl 16) or (u8(at + 1) shl 8) or u8(at)
                else -> (u8(at + 3) shl 24) or (u8(at + 2) shl 16) or (u8(at + 1) shl 8) or u8(at)
            }
        }
        var i = 0
        if (type == 2 || type == 3) {
            while (i < w * h && p + bytesPP <= d.size) { px[i++] = readPixel(p); p += bytesPP }
        } else {
            while (i < w * h && p < d.size) {
                val hdr = u8(p++)
                val n = (hdr and 0x7F) + 1
                if (hdr and 0x80 != 0) {
                    val c = readPixel(p); p += bytesPP
                    repeat(n) { if (i < w * h) px[i++] = c }
                } else {
                    repeat(n) { if (i < w * h && p + bytesPP <= d.size) { px[i++] = readPixel(p); p += bytesPP } }
                }
            }
        }
        val topDown = desc and 0x20 != 0
        val out = if (topDown) px else IntArray(w * h).also { o ->
            for (y in 0 until h) System.arraycopy(px, (h - 1 - y) * w, o, y * w, w)
        }
        fit(Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888), maxDim)
    }.getOrNull()
}
