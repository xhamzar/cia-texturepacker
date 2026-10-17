package com.cia.texturepacker

import android.graphics.Bitmap
import android.graphics.Matrix
import org.json.JSONObject

/** One imported image. */
data class SpriteInput(
    val name: String,
    val bitmap: Bitmap
)

/** A sprite placed inside the atlas. */
data class PackedSprite(
    val name: String,
    val sourceW: Int,
    val sourceH: Int,
    val frame: Bitmap,   // bitmap actually drawn into the atlas (rotated if [rotated])
    val trimX: Int,      // trimmed rect origin inside the original image
    val trimY: Int,
    val trimW: Int,      // trimmed rect size in original orientation
    val trimH: Int,
    val rotated: Boolean,
    val x: Int,          // position of the drawn frame inside the atlas
    val y: Int
)

data class PackResult(
    val atlas: Bitmap,
    val width: Int,
    val height: Int,
    val sprites: List<PackedSprite>,
    /** True when at least one sprite was scaled down (grid mode). */
    val scaledDown: Boolean = false
)

object SpritePacker {

    /** Cut fully-transparent borders. Returns (bitmap, offsetX, offsetY). */
    fun trimWhitespace(src: Bitmap): Triple<Bitmap, Int, Int> {
        val w = src.width
        val h = src.height
        if (w == 0 || h == 0) return Triple(src, 0, 0)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((pixels[y * w + x] ushr 24) != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return Triple(src, 0, 0) // fully transparent, keep as-is
        val tw = maxX - minX + 1
        val th = maxY - minY + 1
        if (tw == w && th == h) return Triple(src, 0, 0)
        return Triple(Bitmap.createBitmap(src, minX, minY, tw, th), minX, minY)
    }

    private fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private data class Shelf(var y: Int, var height: Int, var cursorX: Int)

    /**
     * Shelf packing. Sprites sorted by height (desc).
     * [padding]: transparent border around each sprite (all sides).
     * [spacing]: extra gap between sprite cells.
     * [allowRotation]: try 90° rotation when a sprite doesn't fit unrotated.
     * Returns null when sprites don't fit inside maxW x maxH.
     */
    fun pack(
        inputs: List<SpriteInput>,
        maxW: Int,
        maxH: Int,
        padding: Int,
        spacing: Int,
        trim: Boolean,
        allowRotation: Boolean
    ): PackResult? {
        if (inputs.isEmpty()) return null

        data class Work(
            val input: SpriteInput,
            val bmp: Bitmap,   // trimmed (or original) bitmap, unrotated
            val trimX: Int,
            val trimY: Int
        )

        val works = inputs.map { inp ->
            if (trim) {
                val (t, ox, oy) = trimWhitespace(inp.bitmap)
                Work(inp, t, ox, oy)
            } else {
                Work(inp, inp.bitmap, 0, 0)
            }
        }.sortedByDescending { it.bmp.height }

        data class Placement(
            val work: Work,
            val frame: Bitmap,  // bitmap to draw (rotated when rotated)
            val rotated: Boolean,
            val cellW: Int,
            val cellH: Int
        )

        val shelves = mutableListOf<Shelf>()
        var usedW = 0
        var usedH = 0
        val placed = mutableListOf<PackedSprite>()

        fun cellOf(bw: Int, bh: Int): Pair<Int, Int> =
            (bw + padding * 2 + spacing) to (bh + padding * 2 + spacing)

        for (wk in works) {
            val bw = wk.bmp.width
            val bh = wk.bmp.height
            val (cw, ch) = cellOf(bw, bh)
            if (cw > maxW || ch > maxH) return null // single sprite too big

            var rotated = false
            var frame = wk.bmp
            var pcw = cw
            var pch = ch

            var shelf = shelves.firstOrNull { it.cursorX + pcw <= maxW && pch <= it.height }
            if (shelf == null && allowRotation && bw != bh) {
                // Try rotated 90°
                val (rcw, rch) = cellOf(bh, bw)
                if (rcw <= maxW && rch <= maxH) {
                    val rs = shelves.firstOrNull { it.cursorX + rcw <= maxW && rch <= it.height }
                    if (rs != null) {
                        shelf = rs
                        rotated = true
                        frame = rotate90(wk.bmp)
                        pcw = rcw
                        pch = rch
                    }
                }
            }
            if (shelf == null) {
                // New shelf: prefer unrotated, else rotated if allowed and it fits
                var useRotated = false
                var scw = pcw
                var sch = pch
                if (usedH + sch > maxH && allowRotation && bw != bh) {
                    val (rcw, rch) = cellOf(bh, bw)
                    if (rcw <= maxW && usedH + rch <= maxH) {
                        useRotated = true
                        scw = rcw
                        sch = rch
                    }
                }
                if (usedH + sch > maxH) return null
                shelf = Shelf(usedH, sch, 0)
                shelves.add(shelf)
                usedH += sch
                if (useRotated) {
                    rotated = true
                    frame = rotate90(wk.bmp)
                    pcw = scw
                    pch = sch
                }
            }

            val px = shelf.cursorX + padding
            val py = shelf.y + padding
            shelf.cursorX += pcw
            val right = shelf.cursorX - spacing
            if (right > usedW) usedW = right

            placed.add(
                PackedSprite(
                    name = wk.input.name,
                    sourceW = wk.input.bitmap.width,
                    sourceH = wk.input.bitmap.height,
                    frame = frame,
                    trimX = wk.trimX,
                    trimY = wk.trimY,
                    trimW = bw,
                    trimH = bh,
                    rotated = rotated,
                    x = px,
                    y = py
                )
            )
        }

        val atlas = Bitmap.createBitmap(usedW.coerceAtLeast(1), usedH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(atlas)
        for (p in placed) {
            canvas.drawBitmap(p.frame, p.x.toFloat(), p.y.toFloat(), null)
        }
        return PackResult(atlas, atlas.width, atlas.height, placed)
    }

    /**
     * Grid packing. Every sprite goes into a uniform [cellW] x [cellH] cell,
     * cells laid out left-to-right / top-to-bottom with [spacing] between them.
     * Each sprite is centered inside its cell (after [padding] inset).
     * Sprites bigger than the cell are scaled down to fit (aspect kept);
     * the result reports [PackResult.scaledDown] in that case.
     * [allowRotation] is ignored in grid mode. Returns null when the grid
     * doesn't fit inside maxW x maxH.
     */
    fun packGrid(
        inputs: List<SpriteInput>,
        maxW: Int,
        maxH: Int,
        padding: Int,
        spacing: Int,
        trim: Boolean,
        cellW: Int,
        cellH: Int
    ): PackResult? {
        if (inputs.isEmpty()) return null
        val cw = cellW.coerceAtLeast(1)
        val ch = cellH.coerceAtLeast(1)
        val strideX = cw + spacing
        val strideY = ch + spacing

        val maxCols = ((maxW + spacing) / strideX).coerceAtLeast(1)
        val cols = minOf(maxCols, inputs.size).coerceAtLeast(1)
        val rows = (inputs.size + cols - 1) / cols
        val usedW = cols * strideX - spacing
        val usedH = rows * strideY - spacing
        if (usedW > maxW || usedH > maxH) return null

        val innerW = (cw - padding * 2).coerceAtLeast(1)
        val innerH = (ch - padding * 2).coerceAtLeast(1)

        val placed = mutableListOf<PackedSprite>()
        var anyScaled = false

        for ((i, inp) in inputs.withIndex()) {
            val src = inp.bitmap
            val (content, ox, oy) = if (trim) trimWhitespace(src) else Triple(src, 0, 0)
            var tw = content.width
            var th = content.height
            var frame = content
            var sx = ox.toDouble()
            var sy = oy.toDouble()

            // Scale down to fit the cell inner area, keeping aspect ratio
            val scale = minOf(1.0, innerW.toDouble() / tw, innerH.toDouble() / th)
            // Effective source size for the export data: when scaled down,
            // the packed frame corresponds to the SCALED image, so sourceSize
            // must be scaled too (else the engine maps the trim rect wrongly).
            val esw = (src.width * scale).toInt().coerceAtLeast(1)
            val esh = (src.height * scale).toInt().coerceAtLeast(1)
            if (scale < 1.0) {
                anyScaled = true
                val nw = (tw * scale).toInt().coerceAtLeast(1)
                val nh = (th * scale).toInt().coerceAtLeast(1)
                frame = Bitmap.createScaledBitmap(content, nw, nh, true)
                tw = nw
                th = nh
                sx *= scale
                sy *= scale
            }

            val col = i % cols
            val row = i / cols
            val cellX = col * strideX
            val cellY = row * strideY
            // Center the frame inside the padded cell area
            val px = cellX + padding + (innerW - tw) / 2
            val py = cellY + padding + (innerH - th) / 2

            placed.add(
                PackedSprite(
                    name = inp.name,
                    sourceW = esw,
                    sourceH = esh,
                    frame = frame,
                    trimX = sx.toInt(),
                    trimY = sy.toInt(),
                    trimW = tw,
                    trimH = th,
                    rotated = false,
                    x = px,
                    y = py
                )
            )
        }

        val atlas = Bitmap.createBitmap(usedW.coerceAtLeast(1), usedH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(atlas)
        for (p in placed) {
            canvas.drawBitmap(p.frame, p.x.toFloat(), p.y.toFloat(), null)
        }
        return PackResult(atlas, atlas.width, atlas.height, placed, anyScaled)
    }

    /** Phaser / PixiJS compatible JSON (hash format). */
    fun toJson(result: PackResult, imageName: String): String {
        val frames = JSONObject()
        for (p in result.sprites) {
            val frame = JSONObject()
                .put("x", p.x).put("y", p.y)
                .put("w", p.frame.width).put("h", p.frame.height)
            val trimmed = p.trimW != p.sourceW || p.trimH != p.sourceH
            frames.put(p.name, JSONObject()
                .put("frame", frame)
                .put("rotated", p.rotated)
                .put("trimmed", trimmed)
                .put("spriteSourceSize", JSONObject()
                    .put("x", p.trimX).put("y", p.trimY)
                    .put("w", p.trimW).put("h", p.trimH))
                .put("sourceSize", JSONObject()
                    .put("w", p.sourceW).put("h", p.sourceH)))
        }
        return JSONObject()
            .put("frames", frames)
            .put("meta", JSONObject()
                .put("app", "Cia TexturePacker")
                .put("version", "1.0")
                .put("image", imageName)
                .put("format", "RGBA8888")
                .put("size", JSONObject().put("w", result.width).put("h", result.height))
                .put("scale", "1"))
            .toString(2)
    }

    /** Generic XML atlas format. */
    fun toXml(result: PackResult, imageName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<TextureAtlas imagePath=\"${escXml(imageName)}\" width=\"${result.width}\" height=\"${result.height}\">\n")
        for (p in result.sprites) {
            sb.append("    <sprite name=\"${escXml(p.name)}\" x=\"${p.x}\" y=\"${p.y}\" w=\"${p.frame.width}\" h=\"${p.frame.height}\" rotated=\"${p.rotated}\"/>\n")
        }
        sb.append("</TextureAtlas>\n")
        return sb.toString()
    }

    /** Cocos2d plist (format 2) for Cocos2d-x / Cocos Creator. */
    fun toPlist(result: PackResult, imageName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple Computer//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        sb.append("<plist version=\"1.0\">\n<dict>\n")
        sb.append("    <key>frames</key>\n    <dict>\n")
        for (p in result.sprites) {
            // Cocos2d convention (matches TexturePacker, verified against
            // cocos2d-x CCSprite::setTextureCoords): when rotated=true the
            // engine swaps w/h itself, so frame must carry the UNROTATED
            // trimmed size. p.frame is the rotated bitmap actually drawn.
            val fw = if (p.rotated) p.trimW else p.frame.width
            val fh = if (p.rotated) p.trimH else p.frame.height
            val ox = (p.trimX + p.trimW / 2.0) - (p.sourceW / 2.0)
            val oy = (p.sourceH / 2.0) - (p.trimY + p.trimH / 2.0)
            sb.append("        <key>${escXml(p.name)}</key>\n")
            sb.append("        <dict>\n")
            sb.append("            <key>frame</key><string>{{${p.x},${p.y}},{${fw},${fh}}}</string>\n")
            sb.append("            <key>offset</key><string>{${fmtNum(ox)},${fmtNum(oy)}}</string>\n")
            sb.append("            <key>rotated</key>${if (p.rotated) "<true/>" else "<false/>"}\n")
            sb.append("            <key>sourceColorRect</key><string>{{${p.trimX},${p.trimY}},{${p.trimW},${p.trimH}}}</string>\n")
            sb.append("            <key>sourceSize</key><string>{${p.sourceW},${p.sourceH}}</string>\n")
            sb.append("        </dict>\n")
        }
        sb.append("    </dict>\n")
        sb.append("    <key>metadata</key>\n    <dict>\n")
        sb.append("        <key>format</key><integer>2</integer>\n")
        sb.append("        <key>size</key><string>{${result.width},${result.height}}</string>\n")
        sb.append("        <key>textureFileName</key><string>${escXml(imageName)}</string>\n")
        sb.append("    </dict>\n</dict>\n</plist>\n")
        return sb.toString()
    }

    /** Cocos2d plist (format 3) — matches current TexturePacker / Cocos Creator
     *  output (aliases, anchor, spriteOffset, spriteSize, spriteSourceSize,
     *  textureRect, textureRotated + full metadata block). */
    fun toPlistV3(result: PackResult, imageName: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        sb.append("<plist version=\"1.0\">\n<dict>\n")
        sb.append("\t<key>frames</key>\n\t<dict>\n")
        for (p in result.sprites) {
            // Same convention as format 2 (verified against cocos2d-x):
            // when rotated=true the engine swaps w/h itself, so textureRect
            // carries the UNROTATED trimmed size.
            val fw = if (p.rotated) p.trimW else p.frame.width
            val fh = if (p.rotated) p.trimH else p.frame.height
            val ox = (p.trimX + p.trimW / 2.0) - (p.sourceW / 2.0)
            val oy = (p.sourceH / 2.0) - (p.trimY + p.trimH / 2.0)
            sb.append("\t\t<key>${escXml(p.name)}</key>\n")
            sb.append("\t\t<dict>\n")
            sb.append("\t\t\t<key>aliases</key>\n\t\t\t<array/>\n")
            sb.append("\t\t\t<key>anchor</key>\n\t\t\t<string>{0.5,0.5}</string>\n")
            sb.append("\t\t\t<key>spriteOffset</key>\n\t\t\t<string>{${fmtNum(ox)},${fmtNum(oy)}}</string>\n")
            sb.append("\t\t\t<key>spriteSize</key>\n\t\t\t<string>{${p.trimW},${p.trimH}}</string>\n")
            sb.append("\t\t\t<key>spriteSourceSize</key>\n\t\t\t<string>{${p.sourceW},${p.sourceH}}</string>\n")
            sb.append("\t\t\t<key>textureRect</key>\n\t\t\t<string>{{${p.x},${p.y}},{${fw},${fh}}}</string>\n")
            sb.append("\t\t\t<key>textureRotated</key>\n\t\t\t${if (p.rotated) "<true/>" else "<false/>"}\n")
            sb.append("\t\t</dict>\n")
        }
        sb.append("\t</dict>\n")
        sb.append("\t<key>metadata</key>\n\t<dict>\n")
        sb.append("\t\t<key>format</key>\n\t\t<integer>3</integer>\n")
        sb.append("\t\t<key>pixelFormat</key>\n\t\t<string>RGBA8888</string>\n")
        sb.append("\t\t<key>premultiplyAlpha</key>\n\t\t<false/>\n")
        sb.append("\t\t<key>realTextureFileName</key>\n\t\t<string>${escXml(imageName)}</string>\n")
        sb.append("\t\t<key>size</key>\n\t\t<string>{${result.width},${result.height}}</string>\n")
        sb.append("\t\t<key>textureFileName</key>\n\t\t<string>${escXml(imageName)}</string>\n")
        sb.append("\t</dict>\n</dict>\n</plist>\n")
        return sb.toString()
    }

    /** Simple text list: one sprite per line as "name x y w h". */
    fun toTxt(result: PackResult, imageName: String): String {
        val sb = StringBuilder()
        sb.append("# Cia TexturePacker\n")
        sb.append("# image: $imageName\n")
        sb.append("# size: ${result.width}x${result.height}\n")
        for (p in result.sprites) {
            val rot = if (p.rotated) " R" else ""
            sb.append("${p.name} ${p.x} ${p.y} ${p.frame.width} ${p.frame.height}$rot\n")
        }
        return sb.toString()
    }

    private fun escXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun fmtNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
