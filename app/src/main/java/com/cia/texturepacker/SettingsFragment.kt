package com.cia.texturepacker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

/** Tab 3: pack settings matching the mockup's "Pengaturan Atlas" screen. */
class SettingsFragment : Fragment() {

    private val vm: PackViewModel by lazy {
        ViewModelProvider(requireActivity())[PackViewModel::class.java]
    }

    /** Called after Generate Atlas is tapped (pack started). */
    var onGenerate: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val s = vm.settings

        val spW = view.findViewById<Spinner>(R.id.spWidth)
        val spH = view.findViewById<Spinner>(R.id.spHeight)
        val sizes = arrayOf("512", "1024", "2048")
        val sizeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sizes)
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spW.adapter = sizeAdapter
        spH.adapter = sizeAdapter
        spW.setSelection(sizes.indexOf(s.maxWidth.toString()).coerceAtLeast(0))
        spH.setSelection(sizes.indexOf(s.maxHeight.toString()).coerceAtLeast(0))

        val spMode = view.findViewById<Spinner>(R.id.spPackMode)
        val modes = arrayOf(getString(R.string.pack_mode_auto), getString(R.string.pack_mode_grid))
        val modeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spMode.adapter = modeAdapter
        spMode.setSelection(modes.indexOf(s.packMode).coerceAtLeast(0))

        val llCell = view.findViewById<View>(R.id.llCellSize)
        val spCellW = view.findViewById<Spinner>(R.id.spCellW)
        val spCellH = view.findViewById<Spinner>(R.id.spCellH)
        val cellSizes = arrayOf("32", "64", "128", "256")
        val cellAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, cellSizes)
        cellAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spCellW.adapter = cellAdapter
        spCellH.adapter = cellAdapter
        spCellW.setSelection(cellSizes.indexOf(s.cellWidth.toString()).coerceAtLeast(1))
        spCellH.setSelection(cellSizes.indexOf(s.cellHeight.toString()).coerceAtLeast(1))

        fun refreshModeUi() {
            val isGrid = spMode.selectedItem.toString() == getString(R.string.pack_mode_grid)
            llCell.visibility = if (isGrid) View.VISIBLE else View.GONE
            view.findViewById<SwitchMaterial>(R.id.swRotation).isEnabled = !isGrid
        }
        refreshModeUi()
        spMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                s.packMode = modes[pos]
                refreshModeUi()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        val tvPadVal = view.findViewById<TextView>(R.id.tvPaddingVal)
        val slPad = view.findViewById<Slider>(R.id.slPadding)
        slPad.value = s.padding.toFloat()
        tvPadVal.text = s.padding.toString()
        slPad.addOnChangeListener { _, v, _ ->
            s.padding = v.toInt()
            tvPadVal.text = v.toInt().toString()
        }

        val tvSpVal = view.findViewById<TextView>(R.id.tvSpacingVal)
        val slSp = view.findViewById<Slider>(R.id.slSpacing)
        slSp.value = s.spacing.toFloat()
        tvSpVal.text = s.spacing.toString()
        slSp.addOnChangeListener { _, v, _ ->
            s.spacing = v.toInt()
            tvSpVal.text = v.toInt().toString()
        }

        val swTrim = view.findViewById<SwitchMaterial>(R.id.swTrim)
        swTrim.isChecked = s.trim
        swTrim.setOnCheckedChangeListener { _, b -> s.trim = b }

        val swRot = view.findViewById<SwitchMaterial>(R.id.swRotation)
        swRot.isChecked = s.allowRotation
        swRot.setOnCheckedChangeListener { _, b -> s.allowRotation = b }

        val spFmt = view.findViewById<Spinner>(R.id.spImageFormat)
        val fmts = arrayOf("PNG", "WebP")
        val fmtAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fmts)
        fmtAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spFmt.adapter = fmtAdapter
        spFmt.setSelection(fmts.indexOf(s.imageFormat).coerceAtLeast(0))

        val spFilter = view.findViewById<Spinner>(R.id.spFilter)
        val filters = arrayOf("Nearest", "Linear")
        val filterAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, filters)
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spFilter.adapter = filterAdapter
        spFilter.setSelection(filters.indexOf(s.filter).coerceAtLeast(0))

        val spBg = view.findViewById<Spinner>(R.id.spBackground)
        val bgs = arrayOf(
            getString(R.string.bg_transparent),
            getString(R.string.bg_white),
            getString(R.string.bg_black)
        )
        val bgAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, bgs)
        bgAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spBg.adapter = bgAdapter
        spBg.setSelection(bgs.indexOf(s.background).coerceAtLeast(0))

        view.findViewById<Button>(R.id.btnGenerate).setOnClickListener {
            // Push spinner selections into settings
            s.maxWidth = spW.selectedItem.toString().toInt()
            s.maxHeight = spH.selectedItem.toString().toInt()
            s.imageFormat = spFmt.selectedItem.toString()
            s.filter = spFilter.selectedItem.toString()
            s.background = spBg.selectedItem.toString()
            s.packMode = spMode.selectedItem.toString()
            s.cellWidth = spCellW.selectedItem.toString().toInt()
            s.cellHeight = spCellH.selectedItem.toString().toInt()
            onGenerate?.invoke()
        }
    }
}
