package com.lampplayer.tv.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.lampplayer.tv.R
import com.lampplayer.tv.databinding.ItemCastBinding

/** Horizontal actor carousel: circular photo + name + character. */
class CastAdapter : RecyclerView.Adapter<CastAdapter.VH>() {

    private val items = mutableListOf<PlayerUiState.CastMember>()

    fun setItems(list: List<PlayerUiState.CastMember>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    inner class VH(val b: ItemCastBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemCastBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.b.tvCastName.text = c.name
        holder.b.tvCastCharacter.isVisible = c.character.isNotBlank()
        holder.b.tvCastCharacter.text = c.character
        if (c.photoUrl.isNullOrEmpty()) {
            holder.b.ivCastPhoto.setImageResource(R.drawable.ic_person)
        } else {
            Glide.with(holder.b.ivCastPhoto).load(c.photoUrl)
                .placeholder(R.drawable.ic_person)
                .transform(CircleCrop())
                .into(holder.b.ivCastPhoto)
        }
        holder.b.root.setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.12f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }

    override fun getItemCount() = items.size
}
