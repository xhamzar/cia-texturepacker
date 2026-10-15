package com.cia.texturepacker

import android.graphics.Bitmap

/**
 * Splits a single image into separate sprites by finding connected
 * components of non-transparent pixels (iterative flood fill on the
 * alpha channel, 4-connectivity).
 *
 * Use case: one PNG contains several sprites stacked together
 * (e.g. Tower1.png 128x256 with 2 towers). Auto-Pisah detects each
 * blob and extracts it as its own sprite.
 */
object SpriteSplitter {

    /** Pixels with alpha above this count as opaque. */
    private const val ALPHA_THRESHOLD = 10

    /** Regions smaller than this are treated as noise and dropped. */
    private const val MIN_PIXELS = 64
    private const val MIN_SIZE = 8

    /** Padding (px) added around each extracted region. */
    private const val REGION_PADDING = 2

    data class Region(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Find connected opaque regions, sorted top-to-bottom then
     * left-to-right (reading order).
     */
    fun findRegions(src: Bitmap): List<Region> {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return emptyList()

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val opaque = BooleanArray(w * h)
        for (i in pixels.indices) {
            opaque[i] = (pixels[i] ushr 24) > ALPHA_THRESHOLD
        }

        val visited = BooleanArray(w * h)
        val regions = mutableListOf<Region>()
        val queue = ArrayDeque<Int>()

        for (i in opaque.indices) {
            if (!opaque[i] || visited[i]) continue

            // Iterative BFS flood fill (4-connectivity so that sprites
            // touching only diagonally stay separate).
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var count = 0
            queue.addLast(i)
            visited[i] = true
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                val x = p % w
                val y = p / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                count++
                if (x > 0) {
                    val n = p - 1
                    if (opaque[n] && !visited[n]) { visited[n] = true; queue.addLast(n) }
                }
                if (x < w - 1) {
                    val n = p + 1
                    if (opaque[n] && !visited[n]) { visited[n] = true; queue.addLast(n) }
                }
                if (y > 0) {
                    val n = p - w
                    if (opaque[n] && !visited[n]) { visited[n] = true; queue.addLast(n) }
                }
                if (y < h - 1) {
                    val n = p + w
                    if (opaque[n] && !visited[n]) { visited[n] = true; queue.addLast(n) }
                }
            }

            val rw = maxX - minX + 1
            val rh = maxY - minY + 1
            if (count >= MIN_PIXELS && rw >= MIN_SIZE && rh >= MIN_SIZE) {
                regions.add(Region(minX, minY, rw, rh))
            }
        }

        return regions.sortedWith(compareBy({ it.y }, { it.x }))
    }

    /**
     * Split [src] into separate bitmaps, one per connected region.
     * Returns a list of (name, bitmap). When 0 or 1 region is found,
     * the list contains just the original bitmap under [baseName].
     */
    fun split(src: Bitmap, baseName: String): List<Pair<String, Bitmap>> {
        val regions = findRegions(src)
        if (regions.size <= 1) return listOf(baseName to src)

        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""

        return regions.mapIndexed { idx, r ->
            val x = (r.x - REGION_PADDING).coerceAtLeast(0)
            val y = (r.y - REGION_PADDING).coerceAtLeast(0)
            val w = (r.w + REGION_PADDING * 2).coerceAtMost(src.width - x)
            val h = (r.h + REGION_PADDING * 2).coerceAtMost(src.height - y)
            "${stem}_${idx}$ext" to Bitmap.createBitmap(src, x, y, w, h)
        }
    }
}
