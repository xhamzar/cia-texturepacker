package com.cia.texturepacker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Custom view for pixel-level image editing: eraser/brush tools, pan/zoom, grid overlay. */
class PixelCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Tool { ERASER, BRUSH, MOVE }

    var tool: Tool = Tool.ERASER
    var brushSize: Int = 1          // in bitmap pixels (diameter)
    var brushColor: Int = Color.BLACK
    var showGrid: Boolean = true

    private var bitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f

    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val gridPaint = Paint().apply {
        color = Color.argb(90, 0, 0, 0); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val checkerPaint1 = Paint().apply { color = Color.rgb(200, 200, 200) }
    private val checkerPaint2 = Paint().apply { color = Color.rgb(240, 240, 240) }
    private val borderPaint = Paint().apply {
        color = Color.rgb(120, 120, 120); strokeWidth = 2f; style = Paint.Style.STROKE
    }

    // Undo stack (bitmap snapshots, capped)
    private val undoStack = ArrayDeque<Bitmap>()
    private val maxUndo = 20
    var onUndoStateChanged: ((Boolean) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val bmp = bitmap ?: return false
            val focusX = detector.focusX
            val focusY = detector.focusY
            val factor = detector.scaleFactor
            val newScale = (scale * factor).coerceIn(minScale(), 64f)
            val applied = newScale / scale
            scale = newScale
            // zoom around focus point
            transX = focusX - (focusX - transX) * applied
            transY = focusY - (focusY - transY) * applied
            updateMatrix()
            invalidate()
            return true
        }
    })

    private var lastTouch = PointF()
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isPanning = false
    private var isDrawing = false
    private var strokeDirty = false

    // Reusable canvas for drawing onto the bitmap
    private val bmpCanvas = Canvas()

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        undoStack.clear()
        onUndoStateChanged?.invoke(false)
        // fit to view on next layout
        requestLayout()
        post { fitToView() }
    }

    fun getBitmap(): Bitmap? = bitmap

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun undo() {
        val bmp = bitmap ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        // restore pixels from snapshot
        bmpCanvas.setBitmap(bmp)
        bmpCanvas.drawBitmap(prev, 0f, 0f, null)
        prev.recycle()
        onUndoStateChanged?.invoke(undoStack.isNotEmpty())
        invalidate()
    }

    private fun pushUndo() {
        val bmp = bitmap ?: return
        if (undoStack.size >= maxUndo) {
            undoStack.removeFirst().recycle()
        }
        undoStack.addLast(bmp.copy(Bitmap.Config.ARGB_8888, false))
        onUndoStateChanged?.invoke(true)
    }

    private fun minScale(): Float {
        val bmp = bitmap ?: return 1f
        if (width == 0 || height == 0) return 1f
        return min(width / bmp.width.toFloat(), height / bmp.height.toFloat()).coerceAtMost(8f)
    }

    fun fitToView() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = min(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        scale = max(scale, 0.5f)
        transX = (width - bmp.width * scale) / 2f
        transY = (height - bmp.height * scale) / 2f
        updateMatrix()
        invalidate()
    }

    private fun updateMatrix() {
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(transX, transY)
        drawMatrix.invert(inverseMatrix)
    }

    private fun viewToBitmap(x: Float, y: Float): PointF {
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val bw = bmp.width
        val bh = bmp.height

        // checkerboard for transparency (in bitmap space, drawn via matrix)
        val checkSize = 8f // bitmap pixels per check
        val cols = (bw / checkSize).toInt() + 1
        val rows = (bh / checkSize).toInt() + 1
        canvas.save()
        canvas.concat(drawMatrix)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = c * checkSize
                val top = r * checkSize
                val right = min(left + checkSize, bw.toFloat())
                val bottom = min(top + checkSize, bh.toFloat())
                if (right <= 0 || bottom <= 0) continue
                canvas.drawRect(left, top, right, bottom,
                    if ((r + c) % 2 == 0) checkerPaint1 else checkerPaint2)
            }
        }
        // the bitmap itself (crisp pixels)
        canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        // pixel grid
        if (showGrid && scale >= 4f) {
            for (x in 0..bw) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), bh.toFloat(), gridPaint)
            for (y in 0..bh) canvas.drawLine(0f, y.toFloat(), bw.toFloat(), y.toFloat(), gridPaint)
        }
        // border
        canvas.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), borderPaint)
        canvas.restore()
    }

    private fun drawAt(bx: Float, by: Float) {
        val bmp = bitmap ?: return
        val cx = bx.toInt()
        val cy = by.toInt()
        val r = brushSize / 2
        var changed = false
        for (dy in -r..r) {
            for (dx in -r..r) {
                // circular brush
                if (dx * dx + dy * dy > r * r + r) continue
                val px = cx + dx
                val py = cy + dy
                if (px in 0 until bmp.width && py in 0 until bmp.height) {
                    val newColor = if (tool == Tool.ERASER) Color.TRANSPARENT else brushColor
                    if (bmp.getPixel(px, py) != newColor) {
                        bmp.setPixel(px, py, newColor)
                        changed = true
                    }
                }
            }
        }
        if (changed) {
            strokeDirty = true
            invalidate()
        }
    }

    private fun drawLine(bx0: Float, by0: Float, bx1: Float, by1: Float) {
        // interpolate between points for smooth strokes
        val dist = max(kotlin.math.abs(bx1 - bx0), kotlin.math.abs(by1 - by0))
        val steps = dist.toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            drawAt(bx0 + (bx1 - bx0) * t, by0 + (by1 - by0) * t)
        }
    }

    private var lastDrawPoint: PointF? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouch.set(event.x, event.y)
                if (tool == Tool.MOVE || event.pointerCount > 1) {
                    isPanning = true
                } else {
                    // start drawing stroke
                    pushUndo()
                    isDrawing = true
                    val p = viewToBitmap(event.x, event.y)
                    lastDrawPoint = p
                    drawAt(p.x, p.y)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // second finger: switch to pan/zoom, cancel drawing
                isPanning = true
                isDrawing = false
                lastDrawPoint = null
                // if we pushed undo but didn't draw, pop it
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    lastTouch.set(event.x, event.y)
                    return true
                }
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (isDrawing) {
                    val p = viewToBitmap(x, y)
                    val last = lastDrawPoint
                    if (last != null) drawLine(last.x, last.y, p.x, p.y)
                    lastDrawPoint = p
                } else if (isPanning) {
                    transX += x - lastTouch.x
                    transY += y - lastTouch.y
                    updateMatrix()
                    invalidate()
                }
                lastTouch.set(x, y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // If we pushed an undo snapshot but never actually drew, remove it
                if (isDrawing && !strokeDirty) {
                    undoStack.removeLastOrNull()?.recycle()
                    onUndoStateChanged?.invoke(undoStack.isNotEmpty())
                }
                isDrawing = false
                isPanning = false
                strokeDirty = false
                lastDrawPoint = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == activePointerId) {
                    val newIdx = if (idx == 0) 1 else 0
                    if (newIdx < event.pointerCount) {
                        activePointerId = event.getPointerId(newIdx)
                        lastTouch.set(event.getX(newIdx), event.getY(newIdx))
                    }
                }
                if (event.pointerCount <= 2) isPanning = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
