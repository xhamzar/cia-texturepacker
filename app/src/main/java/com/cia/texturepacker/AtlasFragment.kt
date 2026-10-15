package com.cia.texturepacker

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButtonToggleGroup

/** Tab 2: atlas preview with mode tabs, info bar and frame player. */
class AtlasFragment : Fragment() {

    private val vm: PackViewModel by lazy {
        ViewModelProvider(requireActivity())[PackViewModel::class.java]
    }

    private lateinit var atlasView: AtlasView
    private lateinit var tvEmpty: TextView
    private lateinit var tvInfo: TextView
    private lateinit var framePreview: ImageView
    private lateinit var tvFrame: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var playerBox: View
    private lateinit var btnExport: Button

    /** Called when Export is tapped. */
    var onExport: (() -> Unit)? = null

    private var frameIdx = 0
    private var playing = false
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!playing) return
            val sprites = vm.packResult.value?.sprites ?: return
            if (sprites.isEmpty()) return
            frameIdx = (frameIdx + 1) % sprites.size
            showFrame()
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_atlas, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        atlasView = view.findViewById(R.id.atlasView)
        tvEmpty = view.findViewById(R.id.tvEmptyAtlas)
        tvInfo = view.findViewById(R.id.tvInfo)
        framePreview = view.findViewById(R.id.ivFrame)
        tvFrame = view.findViewById(R.id.tvFrame)
        seek = view.findViewById(R.id.seekFrame)
        btnPlay = view.findViewById(R.id.btnPlay)
        playerBox = view.findViewById(R.id.playerBox)
        btnExport = view.findViewById(R.id.btnExportAtlas)

        val modeGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.modeGroup)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            atlasView.mode = when (checkedId) {
                R.id.modeTexture -> AtlasView.Mode.TEXTURE
                R.id.modeGrid -> AtlasView.Mode.GRID
                else -> AtlasView.Mode.PREVIEW
            }
        }

        btnPlay.setOnClickListener { togglePlay() }
        view.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { stepFrame(-1) }
        view.findViewById<ImageButton>(R.id.btnNext).setOnClickListener { stepFrame(1) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    frameIdx = p
                    showFrame()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnExport.setOnClickListener { onExport?.invoke() }

        vm.packResult.observe(viewLifecycleOwner) { result ->
            stopPlay()
            if (result == null) {
                atlasView.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvInfo.visibility = View.GONE
                playerBox.visibility = View.GONE
                tvFrame.visibility = View.GONE
                btnExport.isEnabled = false
            } else {
                atlasView.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                tvInfo.visibility = View.VISIBLE
                playerBox.visibility = View.VISIBLE
                tvFrame.visibility = View.VISIBLE
                btnExport.isEnabled = true

                atlasView.atlas = result.atlas
                atlasView.backgroundMode = vm.settings.background
                atlasView.filter = vm.settings.filter
                atlasView.spriteRects = result.sprites.map {
                    Rect(it.x, it.y, it.x + it.frame.width, it.y + it.frame.height)
                }
                val isGridPack = vm.settings.packMode == getString(R.string.pack_mode_grid)
                atlasView.showCellGrid = isGridPack
                atlasView.cellW = vm.settings.cellWidth
                atlasView.cellH = vm.settings.cellHeight
                atlasView.cellSpacing = vm.settings.spacing
                tvInfo.text = getString(
                    R.string.atlas_info,
                    result.width, result.height,
                    result.sprites.size, vm.settings.imageFormat
                )
                frameIdx = 0
                seek.max = (result.sprites.size - 1).coerceAtLeast(0)
                seek.progress = 0
                showFrame()
            }
        }
    }

    private fun showFrame() {
        val sprites = vm.packResult.value?.sprites ?: return
        if (sprites.isEmpty()) return
        frameIdx = frameIdx.coerceIn(sprites.indices)
        framePreview.setImageBitmap(sprites[frameIdx].frame)
        tvFrame.text = getString(R.string.frame_count, frameIdx, sprites.size)
        if (seek.progress != frameIdx) seek.progress = frameIdx
    }

    private fun stepFrame(d: Int) {
        val n = vm.packResult.value?.sprites?.size ?: return
        if (n == 0) return
        frameIdx = (frameIdx + d + n) % n
        showFrame()
    }

    private fun togglePlay() {
        val n = vm.packResult.value?.sprites?.size ?: 0
        if (n == 0) return
        playing = !playing
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (playing) handler.post(ticker) else handler.removeCallbacks(ticker)
    }

    private fun stopPlay() {
        playing = false
        handler.removeCallbacks(ticker)
        if (::btnPlay.isInitialized) btnPlay.setImageResource(R.drawable.ic_play)
    }

    override fun onPause() {
        super.onPause()
        stopPlay()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPlay()
    }
}
