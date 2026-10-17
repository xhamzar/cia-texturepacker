package com.cia.texturepacker

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Reads an atlas data file back into frames: Cocos2d plist (format 2 & 3),
 * Phaser/PixiJS JSON (hash), the app's own XML/TXT formats.
 *
 * The rect is cropped verbatim from the atlas image, except when [rotated]
 * is true: the pixels are then stored turned 90° in the atlas (CodeAndWeb
 * convention — the rect carries logical dims), so the caller must crop the
 * swapped rect and turn it back.
 */
data class ImportedFrame(
    val name: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val rotated: Boolean,
    /** Trimmed (logical) sprite size: v3 spriteSize, v2 sourceColorRect. */
    val trimW: Int,
    val trimH: Int
)

object AtlasImporter {

    /** Parse by file extension, falling back to content sniffing. */
    fun parse(text: String, fileName: String): List<ImportedFrame> {
        val lower = fileName.lowercase()
        val head = text.trimStart().take(500)
        return when {
            lower.endsWith(".plist") || (head.startsWith("<") && "plist" in head) ->
                parsePlist(text)
            lower.endsWith(".json") || head.startsWith("{") ->
                parseJson(text)
            lower.endsWith(".xml") || (head.startsWith("<") && "TextureAtlas" in head) ->
                parseXml(text)
            else -> parseTxt(text)
        }
    }

    // ---------------------------------------------------------------- plist

    /**
     * Parses both Cocos2d plist format 2 (frame/rotated/sourceSize) and
     * format 3 (textureRect/textureRotated/spriteSize).
     */
    fun parsePlist(text: String): List<ImportedFrame> {
        val out = mutableListOf<ImportedFrame>()
        val parser = Xml.newPullParser()
        parser.setInput(text.reader())

        var depth = 0                 // open <dict> count
        var keyText: String? = null   // text of current <key>
        var textBuf: String? = null   // text of current <string>/<integer>/<key>
        var lastKey: String? = null   // last closed <key> (to match the next <dict>)
        var framesDepth = -1
        var spriteDepth = -1
        var pendingSprite: String? = null
        var fieldKey: String? = null
        val fields = mutableMapOf<String, String>()

        fun closeSprite() {
            buildFrame(pendingSprite, fields)?.let { out.add(it) }
            pendingSprite = null
            spriteDepth = -1
            fields.clear()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "dict" -> {
                            depth++
                            when {
                                lastKey == "frames" -> framesDepth = depth
                                framesDepth != -1 && depth == framesDepth + 1 && pendingSprite != null -> {
                                    spriteDepth = depth
                                    fields.clear()
                                }
                            }
                            lastKey = null
                        }
                        "key" -> { keyText = null; textBuf = null }
                        "string", "integer" -> textBuf = null
                        "true", "false" -> {
                            if (fieldKey != null) {
                                fields[fieldKey!!] = if (parser.name == "true") "true" else "false"
                                fieldKey = null
                            }
                        }
                        "array" -> fieldKey = null // e.g. <key>aliases</key><array/>
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text
                    if (!t.isNullOrBlank()) textBuf = t.trim()
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "key" -> {
                            val k = textBuf
                            lastKey = k
                            when {
                                // direct child of the frames dict = sprite name
                                framesDepth != -1 && spriteDepth == -1 && depth == framesDepth ->
                                    pendingSprite = k
                                // direct child of a sprite dict = field name
                                spriteDepth != -1 && depth == spriteDepth ->
                                    fieldKey = k
                            }
                            textBuf = null
                        }
                        "string", "integer" -> {
                            if (fieldKey != null) {
                                fields[fieldKey!!] = textBuf ?: ""
                                fieldKey = null
                            }
                            textBuf = null
                        }
                        "dict" -> {
                            when {
                                spriteDepth != -1 && depth == spriteDepth -> closeSprite()
                                framesDepth != -1 && depth == framesDepth -> framesDepth = -1
                            }
                            depth--
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun buildFrame(name: String?, f: Map<String, String>): ImportedFrame? {
        if (name.isNullOrEmpty()) return null
        // Format 3 keys first, then format 2.
        val rectStr = f["textureRect"] ?: f["frame"] ?: return null
        val rotated = (f["textureRotated"] ?: f["rotated"] ?: "false") == "true"
        val rect = parseRect(rectStr) ?: return null
        // Trimmed size: v3 spriteSize, v2 sourceColorRect; fallback to rect.
        val trimStr = f["spriteSize"] ?: f["sourceColorRect"]
        val (tw, th) = trimStr?.let { parseSize(it) } ?: (rect.w to rect.h)
        return ImportedFrame(name, rect.x, rect.y, rect.w, rect.h, rotated, tw, th)
    }

    /**
     * Atlas pixel size from the plist metadata ("size" {w,h}), or null when
     * absent. Used to warn when the image doesn't match the data file.
     */
    fun parsePlistSize(text: String): Pair<Int, Int>? {
        val m = Regex("""<key>size</key>\s*<string>\{\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\}""")
            .find(text) ?: return null
        val (w, h) = m.destructured
        return w.toDouble().toInt() to h.toDouble().toInt()
    }

    private data class R(val x: Int, val y: Int, val w: Int, val h: Int)

    private val rectRe =
        Regex("""\{\{\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\},\{\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\}\}""")
    private val sizeRe = Regex("""\{\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\}""")

    private fun parseRect(s: String): R? {
        val m = rectRe.find(s) ?: return null
        val (x, y, w, h) = m.destructured
        return R(x.toDouble().toInt(), y.toDouble().toInt(), w.toDouble().toInt(), h.toDouble().toInt())
    }

    private fun parseSize(s: String): Pair<Int, Int>? {
        val m = sizeRe.find(s) ?: return null
        val (w, h) = m.destructured
        return w.toDouble().toInt() to h.toDouble().toInt()
    }

    // ----------------------------------------------------------------- JSON

    /** Phaser/PixiJS hash JSON + the app's own export. */
    fun parseJson(text: String): List<ImportedFrame> {
        val out = mutableListOf<ImportedFrame>()
        val root = JSONObject(text)
        val frames = root.optJSONObject("frames") ?: return out
        val keys = frames.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val o = frames.optJSONObject(name) ?: continue
            val fr = o.optJSONObject("frame") ?: continue
            val rotated = o.optBoolean("rotated", false)
            val w = fr.optInt("w", 0)
            val h = fr.optInt("h", 0)
            if (w <= 0 || h <= 0) continue
            val src = o.optJSONObject("spriteSourceSize")
            val tw = src?.optInt("w", w) ?: w
            val th = src?.optInt("h", h) ?: h
            out.add(
                ImportedFrame(
                    name,
                    fr.optInt("x", 0), fr.optInt("y", 0), w, h,
                    rotated, tw, th
                )
            )
        }
        return out
    }

    // ------------------------------------------------------------------ XML

    /** The app's own &lt;TextureAtlas&gt; export. */
    fun parseXml(text: String): List<ImportedFrame> {
        val out = mutableListOf<ImportedFrame>()
        val parser = Xml.newPullParser()
        parser.setInput(text.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sprite") {
                val name = parser.getAttributeValue(null, "name") ?: ""
                val x = parser.getAttributeValue(null, "x")?.toIntOrNull() ?: 0
                val y = parser.getAttributeValue(null, "y")?.toIntOrNull() ?: 0
                val w = parser.getAttributeValue(null, "w")?.toIntOrNull() ?: 0
                val h = parser.getAttributeValue(null, "h")?.toIntOrNull() ?: 0
                val rotated = parser.getAttributeValue(null, "rotated") == "true"
                if (name.isNotEmpty() && w > 0 && h > 0) {
                    out.add(ImportedFrame(name, x, y, w, h, rotated, w, h))
                }
            }
            event = parser.next()
        }
        return out
    }

    // ------------------------------------------------------------------ TXT

    /** The app's own TXT: "name x y w h [R]". */
    fun parseTxt(text: String): List<ImportedFrame> {
        val out = mutableListOf<ImportedFrame>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 5) continue
            val rotated = parts.size > 5 && parts[5].equals("R", ignoreCase = true)
            // name may contain spaces: everything but the last 4/5 tokens
            val numCount = if (rotated) 5 else 4
            val nums = parts.takeLast(numCount).mapNotNull { it.toIntOrNull() }
            if (nums.size != numCount) continue
            val name = parts.dropLast(numCount).joinToString(" ")
            val (x, y, w, h) = nums
            if (name.isNotEmpty() && w > 0 && h > 0) {
                out.add(ImportedFrame(name, x, y, w, h, rotated, w, h))
            }
        }
        return out
    }
}
