package com.cia.texturepacker

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar

/** Pixel-level editor: long-press a thumbnail to clean stray pixels manually. */
class PixelEditorActivity : AppCompatActivity() {

    private lateinit var canvas: PixelCanvasView
    private lateinit var toolbar: MaterialToolbar

    private val toolButtons = mutableMapOf<PixelCanvasView.Tool, Button>()
    private val brushSizes = listOf(1, 3, 5, 9)
    private var brushSizeIdx = 1 // default 3px

    private val presetColors = listOf(
        Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE,
        Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.GRAY
    )
    private lateinit var colorRow: LinearLayout
    private lateinit var sizeRow: LinearLayout
    private lateinit var tvSize: TextView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnGrid: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pixel_editor)

        val bmp = PixelEditHolder.bitmap
        if (bmp == null || bmp.isRecycled) {
            Toast.makeText(this, R.string.need_images, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = PixelEditHolder.name.ifEmpty { getString(R.string.pixel_editor) }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setOnMenuItemClickListener { onMenu(it) }

        canvas = findViewById(R.id.pixelCanvas)
        // work on the holder bitmap directly (it's already a mutable copy)
        canvas.setBitmap(bmp)
        canvas.onUndoStateChanged = { canUndo -> btnUndo.isEnabled = canUndo }

        // tools
        toolButtons[PixelCanvasView.Tool.ERASER] = findViewById(R.id.toolEraser)
        toolButtons[PixelCanvasView.Tool.BRUSH] = findViewById(R.id.toolBrush)
        toolButtons[PixelCanvasView.Tool.MOVE] = findViewById(R.id.toolMove)
        toolButtons.forEach { (t, b) -> b.setOnClickListener { selectTool(t) } }

        // brush size
        sizeRow = findViewById(R.id.sizeRow)
        tvSize = findViewById(R.id.tvSize)
        val seek = findViewById<SeekBar>(R.id.seekSize)
        seek.max = brushSizes.size - 1
        seek.progress = brushSizeIdx
        updateSizeLabel()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                brushSizeIdx = p
                canvas.brushSize = brushSizes[p]
                updateSizeLabel()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // colors (brush only)
        colorRow = findViewById(R.id.colorRow)
        buildColorRow()

        // undo + grid
        btnUndo = findViewById(R.id.btnUndo)
        btnUndo.isEnabled = false

        // select default tool AFTER all views are initialized
        selectTool(PixelCanvasView.Tool.ERASER)
        btnUndo.setOnClickListener { canvas.undo() }
        btnGrid = findViewById(R.id.btnGrid)
        btnGrid.setOnClickListener {
            canvas.showGrid = !canvas.showGrid
            btnGrid.alpha = if (canvas.showGrid) 1f else 0.4f
        }

        // bottom action bar: always-visible save/cancel
        val btnSave: Button = findViewById(R.id.btnSave)
        val btnCancel: Button = findViewById(R.id.btnCancel)
        btnSave.setOnClickListener { doSave() }
        btnCancel.setOnClickListener { doCancel() }

        updateToolUi()
    }

    private fun updateSizeLabel() {
        tvSize.text = getString(R.string.brush_size_n, brushSizes[brushSizeIdx])
    }

    private fun buildColorRow() {
        colorRow.removeAllViews()
        val dp = resources.displayMetrics.density
        for (c in presetColors) {
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                    marginEnd = (8 * dp).toInt()
                }
                setBackgroundColor(c)
                // ring to indicate selection
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    setStroke(
                        (3 * dp).toInt(),
                        if (c == canvas.brushColor) ContextCompat.getColor(this@PixelEditorActivity, R.color.blue)
                        else Color.TRANSPARENT
                    )
                }
                setOnClickListener {
                    canvas.brushColor = c
                    buildColorRow()
                }
            }
            colorRow.addView(v)
        }
    }

    private fun selectTool(t: PixelCanvasView.Tool) {
        canvas.tool = t
        updateToolUi()
    }

    private fun updateToolUi() {
        val blue = ContextCompat.getColor(this, R.color.blue)
        val transparent = ContextCompat.getColor(this, android.R.color.transparent)
        val white = ContextCompat.getColor(this, android.R.color.white)
        val dark = ContextCompat.getColor(this, R.color.code_text)
        toolButtons.forEach { (t, b) ->
            val active = t == canvas.tool
            b.setBackgroundColor(if (active) blue else transparent)
            b.setTextColor(if (active) white else dark)
        }
        // color row only relevant for brush
        colorRow.visibility = if (canvas.tool == PixelCanvasView.Tool.BRUSH) View.VISIBLE else View.GONE
        sizeRow.visibility = if (canvas.tool == PixelCanvasView.Tool.MOVE) View.GONE else View.VISIBLE
    }

    private fun onMenu(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save -> {
                doSave()
                return true
            }
            R.id.action_cancel -> {
                doCancel()
                return true
            }
        }
        return false
    }

    private fun doSave() {
        PixelEditHolder.saved = true
        finish()
    }

    private fun doCancel() {
        PixelEditHolder.saved = false
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        PixelEditHolder.saved = false
        finish()
        return true
    }
}

/** Static holder to pass the edited bitmap to/from PixelEditorActivity. */
object PixelEditHolder {
    var bitmap: Bitmap? = null
    var name: String = ""
    var index: Int = -1
    var saved: Boolean = false

    fun clear() {
        // don't recycle: the bitmap belongs to the ViewModel list
        bitmap = null
        name = ""
        index = -1
        saved = false
    }
}
