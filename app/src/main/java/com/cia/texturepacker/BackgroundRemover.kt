package com.cia.texturepacker

import android.graphics.Bitmap

/**
 * Removes a solid-color background from a bitmap by making matching
 * pixels fully transparent (chroma-key style).
 *
 * Background color is auto-detected by sampling the 4 corner pixels
 * (small averaged blocks) and picking the most frequent one.
 * Works on any image; already-transparent pixels are left alone.
 * The source bitmap is never mutated — a new ARGB_8888 bitmap is returned.
 */
object BackgroundRemover {

    /** Pixels with alpha below this are treated as already transparent. */
    private const val ALPHA_OPAQUE = 128

    /** Corner sample block size (px). */
    private const val CORNER_BLOCK = 5

    /**
     * @param tolerance max per-pixel color distance to the background
     * color (0..~441 for full RGB Euclidean). Default 32 handles slight
     * gradients/JPEG noise around a flat background.
     */
    fun removeBackground(src: Bitmap, tolerance: Int = 32): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src

        val bgColor = detectBackgroundColor(src, w, h) ?: return src

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val bgR = (bgColor shr 16) and 0xFF
        val bgG = (bgColor shr 8) and 0xFF
        val bgB = bgColor and 0xFF
        // Compare squared Euclidean distances to avoid sqrt per pixel.
        val limit = tolerance * tolerance * 3

        for (i in pixels.indices) {
            val p = pixels[i]
            if ((p ushr 24) == 0) continue // already transparent
            val dr = ((p shr 16) and 0xFF) - bgR
            val dg = ((p shr 8) and 0xFF) - bgG
            val db = (p and 0xFF) - bgB
            if (dr * dr + dg * dg + db * db <= limit) {
                pixels[i] = 0 // fully transparent
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Samples a small block at each image corner (ignoring transparent
     * pixels) and returns the most frequent corner color, or null when no
     * opaque corner pixels exist (image is already transparent there).
     */
    private fun detectBackgroundColor(src: Bitmap, w: Int, h: Int): Int? {
        val bw = minOf(CORNER_BLOCK, w)
        val bh = minOf(CORNER_BLOCK, h)
        val corners = listOf(
            avgCorner(src, 0, 0, bw, bh),
            avgCorner(src, w - bw, 0, bw, bh),
            avgCorner(src, 0, h - bh, bw, bh),
            avgCorner(src, w - bw, h - bh, bw, bh)
        ).filterNotNull()
        if (corners.isEmpty()) return null
        return corners.groupBy { it }.maxByOrNull { it.value.size }?.key
    }

    /**
     * Averages the opaque pixels of the block at (bx, by).
     * Returns null when the block has no opaque pixels.
     */
    private fun avgCorner(src: Bitmap, bx: Int, by: Int, bw: Int, bh: Int): Int? {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (y in by until by + bh) {
            for (x in bx until bx + bw) {
                val p = src.getPixel(x, y)
                if ((p ushr 24) < ALPHA_OPAQUE) continue
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0) return null
        return (0xFF shl 24) or
            (((r / n).toInt() and 0xFF) shl 16) or
            (((g / n).toInt() and 0xFF) shl 8) or
            ((b / n).toInt() and 0xFF)
    }
}
