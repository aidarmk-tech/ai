package com.lampplayer.tv.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.lampplayer.tv.R
import com.lampplayer.tv.databinding.ItemInfoListBinding

class InfoListAdapter<T>(
    private val labelOf: (T) -> String,
    private val onSelected: (T) -> Unit,
    // Optional channel logo (IPTV); null → text-only row (audio/subtitle lists).
    private val logoOf: (T) -> String? = { null },
) : RecyclerView.Adapter<InfoListAdapter<T>.VH>() {

    private val items = mutableListOf<T>()
    private var selectedIndex = -1

    fun setItems(newItems: List<T>, selected: Int = -1) {
        items.clear()
        items.addAll(newItems)
        selectedIndex = selected
        notifyDataSetChanged()
    }

    inner class VH(val b: ItemInfoListBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: T, isSelected: Boolean) {
            b.tvLabel.text = labelOf(item)
            val ctx = b.root.context
            b.root.setBackgroundResource(
                if (isSelected) R.drawable.bg_button_accent else R.drawable.bg_card
            )
            b.tvLabel.setTextColor(
                ContextCompat.getColor(ctx, if (isSelected) R.color.text_primary else R.color.text_secondary)
            )
            b.ivCheck.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            val logo = logoOf(item)
            if (logo.isNullOrBlank()) {
                b.ivLogo.visibility = android.view.View.GONE
            } else {
                b.ivLogo.visibility = android.view.View.VISIBLE
                Glide.with(b.ivLogo).load(logo).into(b.ivLogo)
            }
            b.root.setOnClickListener { onSelected(item) }
            b.root.setOnFocusChangeListener { v, focused ->
                v.scaleX = if (focused) 1.05f else 1f
                v.scaleY = if (focused) 1.05f else 1f
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemInfoListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], position == selectedIndex)
}
