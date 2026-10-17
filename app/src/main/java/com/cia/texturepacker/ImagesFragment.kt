package com.cia.texturepacker

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Tab 1: grid of imported sprites + action buttons. Supports multi-select batch delete. */
class ImagesFragment : Fragment() {

    private val vm: PackViewModel by lazy {
        ViewModelProvider(requireActivity())[PackViewModel::class.java]
    }
    private lateinit var adapter: SpriteGridAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnMake: Button
    private lateinit var btnSplit: Button
    private lateinit var btnTransparan: Button
    private lateinit var selectionBar: LinearLayout
    private lateinit var tvSelectedCount: TextView
    private lateinit var selectionActions: LinearLayout
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var rowActions1: LinearLayout
    private lateinit var rowActions2: LinearLayout
    private lateinit var rowActionsImport: LinearLayout
    private lateinit var btnImportAtlas: Button

    private var selectionMode = false
    private var suppressSelectionClear = false

    private val pixelEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // PixelEditorActivity signals via PixelEditHolder.saved
        if (PixelEditHolder.saved && PixelEditHolder.index >= 0) {
            val bmp = PixelEditHolder.bitmap
            val idx = PixelEditHolder.index
            PixelEditHolder.clear()
            if (bmp != null && !bmp.isRecycled) {
                vm.replaceBitmap(idx, bmp)
            }
        } else {
            // discarded: recycle the working copy to avoid leaking
            PixelEditHolder.bitmap?.recycle()
            PixelEditHolder.clear()
        }
    }

    /** Long-press menu: rename or open the pixel editor. */
    private fun showItemMenu(pos: Int) {
        val item = vm.sprites.value?.getOrNull(pos) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(
                arrayOf(
                    getString(R.string.action_rename),
                    getString(R.string.action_edit_pixel)
                )
            ) { _, which ->
                when (which) {
                    0 -> showRenameDialog(pos, item.name)
                    1 -> openPixelEditor(pos)
                }
            }
            .show()
    }

    private fun showRenameDialog(pos: Int, oldName: String) {
        val et = android.widget.EditText(requireContext()).apply {
            setText(oldName)
            selectAll()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(requireContext()).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(et)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rename_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ -> vm.rename(pos, et.text.toString()) }
            .show()
        et.post {
            et.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun openPixelEditor(pos: Int) {
        if (pos == RecyclerView.NO_POSITION || selectionMode) return
        val item = vm.sprites.value?.getOrNull(pos) ?: return
        // work on a mutable copy; the original stays until Save
        val working = item.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: return
        PixelEditHolder.bitmap = working
        PixelEditHolder.name = item.name
        PixelEditHolder.index = pos
        PixelEditHolder.saved = false
        pixelEditorLauncher.launch(Intent(requireContext(), PixelEditorActivity::class.java))
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectionMode()
        }
    }

    /** Import Atlas: pick the atlas image first, then its data file. */
    private var pendingAtlasImage: android.net.Uri? = null

    private val pickAtlasImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingAtlasImage = uri
            pickAtlasDataLauncher.launch("*/*")
        }
    }

    private val pickAtlasDataLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val img = pendingAtlasImage
        pendingAtlasImage = null
        if (img != null && uri != null) {
            vm.importAtlas(img, uri)
        }
    }

    /** Called by MainActivity when the toolbar "+" is tapped. */
    var onAddImages: (() -> Unit)? = null
    /** Called when "Buat Sprite Sheet" is tapped. */
    var onMakeSheet: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_images, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SpriteGridAdapter(
            onRemove = { pos ->
                if (pos != RecyclerView.NO_POSITION) vm.removeAt(pos)
            },
            onToggleSelect = { pos ->
                if (pos != RecyclerView.NO_POSITION && selectionMode) {
                    adapter.toggleSelect(pos)
                    updateSelectionUi()
                }
            },
            onLongPress = { pos ->
                if (pos != RecyclerView.NO_POSITION && !selectionMode) showItemMenu(pos)
            }
        )
        val rv = view.findViewById<RecyclerView>(R.id.rvGrid)
        rv.layoutManager = GridLayoutManager(requireContext(), 3)
        rv.adapter = adapter

        tvEmpty = view.findViewById(R.id.tvEmpty)
        tvCount = view.findViewById(R.id.tvCount)
        btnMake = view.findViewById(R.id.btnMake)
        selectionBar = view.findViewById(R.id.selectionBar)
        tvSelectedCount = view.findViewById(R.id.tvSelectedCount)
        selectionActions = view.findViewById(R.id.selectionActions)
        btnSelectAll = view.findViewById(R.id.btnSelectAll)
        btnDeleteSelected = view.findViewById(R.id.btnDeleteSelected)
        rowActions1 = view.findViewById(R.id.rowActions1)
        rowActions2 = view.findViewById(R.id.rowActions2)
        rowActionsImport = view.findViewById(R.id.rowActionsImport)
        btnImportAtlas = view.findViewById(R.id.btnImportAtlas)
        btnImportAtlas.setOnClickListener { pickAtlasImageLauncher.launch("image/*") }

        view.findViewById<Button>(R.id.btnAdd).setOnClickListener { onAddImages?.invoke() }
        btnSplit = view.findViewById(R.id.btnSplit)
        btnSplit.setOnClickListener { vm.autoSplit() }
        btnTransparan = view.findViewById(R.id.btnTransparan)
        btnTransparan.setOnClickListener { vm.autoRemoveBackground() }
        btnMake.setOnClickListener { onMakeSheet?.invoke() }

        view.findViewById<View>(R.id.btnExitSelect).setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener { toggleSelectAll() }
        btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        vm.sprites.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list.toList())
            val empty = list.isEmpty()
            tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            tvCount.visibility = if (empty) View.GONE else View.VISIBLE
            if (!empty) tvCount.text = getString(R.string.images_added, list.size)
            btnMake.isEnabled = !empty
            btnSplit.isEnabled = !empty
            btnTransparan.isEnabled = !empty
            if (selectionMode) {
                if (empty) {
                    exitSelectionMode()
                } else if (!suppressSelectionClear) {
                    // List changed from another action (add/split/transparan):
                    // drop stale selection but stay in selection mode.
                    adapter.clearSelection()
                    updateSelectionUi()
                }
            }
        }
        applySelectionUi()
    }

    fun isSelectionMode(): Boolean = selectionMode

    fun enterSelectionMode() {
        if (!isAdded) return
        if (adapter.itemCount == 0) return
        selectionMode = true
        adapter.clearSelection()
        adapter.selectionMode = true
        applySelectionUi()
        updateSelectionUi()
    }

    fun exitSelectionMode() {
        if (!selectionMode) return
        selectionMode = false
        backCallback.isEnabled = false
        if (isAdded && view != null) {
            adapter.clearSelection()
            adapter.selectionMode = false
            applySelectionUi()
        }
    }

    private fun applySelectionUi() {
        if (!isAdded || view == null) return
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        selectionActions.visibility = if (selectionMode) View.VISIBLE else View.GONE
        rowActions1.visibility = if (selectionMode) View.GONE else View.VISIBLE
        rowActions2.visibility = if (selectionMode) View.GONE else View.VISIBLE
        rowActionsImport.visibility = if (selectionMode) View.GONE else View.VISIBLE
        backCallback.isEnabled = selectionMode
    }

    private fun updateSelectionUi() {
        if (!isAdded || view == null) return
        val n = adapter.selected.size
        val total = adapter.itemCount
        tvSelectedCount.text = getString(R.string.selected_count, n)
        btnDeleteSelected.text = getString(R.string.delete_n, n)
        btnDeleteSelected.isEnabled = n > 0
        btnSelectAll.text = getString(
            if (n == total && total > 0) R.string.deselect_all else R.string.select_all
        )
    }

    private fun toggleSelectAll() {
        if (adapter.selected.size == adapter.itemCount && adapter.itemCount > 0) {
            adapter.clearSelection()
        } else {
            adapter.selectAll()
        }
        updateSelectionUi()
    }

    private fun confirmDeleteSelected() {
        val n = adapter.selected.size
        if (n == 0) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_confirm, n))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val toDelete = adapter.selected.toList()
                suppressSelectionClear = true
                vm.removePositions(toDelete)
                suppressSelectionClear = false
                exitSelectionMode()
            }
            .show()
    }
}
