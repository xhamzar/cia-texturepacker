package com.cia.texturepacker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Shows the packed atlas on a checkerboard / solid background, with optional grid. */
class AtlasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { PREVIEW, TEXTURE, GRID }

    var mode: Mode = Mode.PREVIEW
        set(v) { field = v; invalidate() }

    /** Transparan | Putih | Hitam */
    var backgroundMode: String = "Transparan"
        set(v) { field = v; invalidate() }

    /** Nearest | Linear */
    var filter: String = "Nearest"
        set(v) { field = v; invalidate() }

    var atlas: Bitmap? = null
        set(v) { field = v; invalidate() }

    var spriteRects: List<android.graphics.Rect> = emptyList()
        set(v) { field = v; invalidate() }

    /** Grid pack mode: draw cell boundaries in GRID tab. */
    var showCellGrid: Boolean = false
        set(v) { field = v; invalidate() }
    var cellW: Int = 0
        set(v) { field = v; invalidate() }
    var cellH: Int = 0
        set(v) { field = v; invalidate() }
    var cellSpacing: Int = 0
        set(v) { field = v; invalidate() }

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkerPaintA = Paint().apply { color = Color.WHITE }
    private val checkerPaintB = Paint().apply { color = 0xFFE0E0E0.toInt() }
    private val gridPaint = Paint().apply {
        color = 0xFF2196F3.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val solidPaint = Paint()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = atlas ?: return

        // Fit bitmap into view, centered
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val scale = minOf(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val dx = (vw - dw) / 2f
        val dy = (vh - dh) / 2f

        // Background
        if (mode == Mode.TEXTURE) {
            // Flat dark backdrop like the mockup's Texture tab
            solidPaint.color = 0xFF2B2B2B.toInt()
            canvas.drawRect(0f, 0f, vw, vh, solidPaint)
        } else when (backgroundMode) {
            "Putih" -> {
                solidPaint.color = Color.WHITE
                canvas.drawRect(0f, 0f, vw, vh, solidPaint)
            }
            "Hitam" -> {
                solidPaint.color = Color.BLACK
                canvas.drawRect(0f, 0f, vw, vh, solidPaint)
            }
            else -> {
                // Checkerboard behind the atlas area
                val cell = (24 * resources.displayMetrics.density).coerceAtLeast(8f)
                var row = 0
                var y = dy
                while (y < dy + dh) {
                    var col = row % 2
                    var x = dx
                    while (x < dx + dw) {
                        canvas.drawRect(
                            x, y,
                            minOf(x + cell, dx + dw), minOf(y + cell, dy + dh),
                            if (col % 2 == 0) checkerPaintA else checkerPaintB
                        )
                        x += cell
                        col++
                    }
                    y += cell
                    row++
                }
            }
        }

        bmpPaint.isFilterBitmap = filter == "Linear"
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bmp, 0f, 0f, bmpPaint)

        // Grid: sprite rects, or cell boundaries in grid pack mode
        if (mode == Mode.GRID) {
            gridPaint.strokeWidth = 2f / scale
            if (showCellGrid && cellW > 0 && cellH > 0) {
                val stepX = (cellW + cellSpacing).toFloat()
                val stepY = (cellH + cellSpacing).toFloat()
                var x = 0f
                while (x <= bmp.width) {
                    canvas.drawLine(x, 0f, x, bmp.height.toFloat(), gridPaint)
                    x += stepX
                }
                var y = 0f
                while (y <= bmp.height) {
                    canvas.drawLine(0f, y, bmp.width.toFloat(), y, gridPaint)
                    y += stepY
                }
            } else {
                for (r in spriteRects) {
                    canvas.drawRect(
                        r.left.toFloat(), r.top.toFloat(),
                        r.right.toFloat(), r.bottom.toFloat(), gridPaint
                    )
                }
            }
        }
        canvas.restore()
    }
}
