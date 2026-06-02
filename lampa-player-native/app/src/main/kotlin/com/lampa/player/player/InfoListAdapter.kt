package com.lampa.player.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.lampa.player.R
import com.lampa.player.databinding.ItemInfoListBinding

class InfoListAdapter<T>(
    private val labelOf: (T) -> String,
    private val onSelected: (T) -> Unit,
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

            // Selected item: blue-tinted background with left accent stripe.
            // Unfocused/unselected: transparent background (focus stripe via bg_item_info_list selector).
            b.root.setBackgroundResource(
                if (isSelected) R.drawable.bg_list_item_selected else R.drawable.bg_item_info_list
            )

            b.tvLabel.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (isSelected) R.color.text_primary else R.color.text_secondary
                )
            )

            // The inline accent bar View is visible only for selected items.
            // The drawable handles the focus-state bar via the state selector.
            b.accentBar.isVisible = isSelected

            b.root.setOnClickListener { onSelected(item) }

            // Slight scale-up on D-pad focus for depth feel
            b.root.setOnFocusChangeListener { v, focused ->
                v.animate()
                    .scaleX(if (focused) 1.03f else 1f)
                    .scaleY(if (focused) 1.03f else 1f)
                    .setDuration(120)
                    .start()
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
