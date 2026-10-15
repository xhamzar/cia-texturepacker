package com.cia.texturepacker

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox

class SpriteGridAdapter(
    private val onRemove: (Int) -> Unit,
    private val onToggleSelect: (Int) -> Unit,
    private val onLongPress: (Int) -> Unit = {}
) : ListAdapter<SpriteItem, SpriteGridAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SpriteItem>() {
            override fun areItemsTheSame(a: SpriteItem, b: SpriteItem) = a.name == b.name && a.bitmap == b.bitmap
            override fun areContentsTheSame(a: SpriteItem, b: SpriteItem) = a == b
        }
    }

    var selectionMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    val selected = mutableSetOf<Int>()

    fun clearSelection() {
        if (selected.isNotEmpty()) {
            selected.clear()
            notifyItemRangeChanged(0, itemCount)
        }
    }

    fun selectAll() {
        selected.clear()
        for (i in 0 until itemCount) selected.add(i)
        notifyItemRangeChanged(0, itemCount)
    }

    fun toggleSelect(pos: Int) {
        if (pos == RecyclerView.NO_POSITION) return
        if (!selected.remove(pos)) selected.add(pos)
        notifyItemChanged(pos)
    }

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_sprite, parent, false)
    ) {
        val card: MaterialCardView = itemView as MaterialCardView
        val thumb: ImageView = itemView.findViewById(R.id.ivThumb)
        val name: TextView = itemView.findViewById(R.id.tvName)
        val dims: TextView = itemView.findViewById(R.id.tvDims)
        val remove: ImageButton = itemView.findViewById(R.id.btnRemove)
        val check: MaterialCheckBox = itemView.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = getItem(position)
        holder.thumb.setImageBitmap(it.thumb)
        holder.name.text = it.name
        holder.dims.text = "${it.width} × ${it.height}"

        val isSel = selected.contains(position)
        if (selectionMode) {
            holder.remove.visibility = View.GONE
            holder.check.visibility = View.VISIBLE
            holder.check.isChecked = isSel
            // blue highlight border for selected items
            if (isSel) {
                holder.card.strokeWidth = dp(holder, 3f)
                holder.card.strokeColor =
                    holder.itemView.context.getColor(R.color.blue)
            } else {
                holder.card.strokeWidth = 0
            }
            holder.itemView.setOnClickListener { onToggleSelect(holder.bindingAdapterPosition) }
            holder.itemView.setOnLongClickListener {
                onLongPress(holder.bindingAdapterPosition)
                true
            }
        } else {
            holder.remove.visibility = View.VISIBLE
            holder.check.visibility = View.GONE
            holder.card.strokeWidth = 0
            holder.remove.setOnClickListener { onRemove(holder.bindingAdapterPosition) }
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnLongClickListener {
                onLongPress(holder.bindingAdapterPosition)
                true
            }
        }
    }

    private fun dp(holder: VH, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v,
            holder.itemView.context.resources.displayMetrics
        ).toInt()
}
