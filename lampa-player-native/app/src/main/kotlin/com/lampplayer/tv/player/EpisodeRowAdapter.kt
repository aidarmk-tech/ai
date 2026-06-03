package com.lampplayer.tv.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.lampplayer.tv.databinding.ItemEpisodeRowBinding

/** Rich episode list (TMDB): still thumbnail + name + synopsis. */
class EpisodeRowAdapter(
    private val onSelected: (PlayerUiState.EpisodeRow) -> Unit,
) : RecyclerView.Adapter<EpisodeRowAdapter.VH>() {

    private val items = mutableListOf<PlayerUiState.EpisodeRow>()

    fun setItems(list: List<PlayerUiState.EpisodeRow>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun currentIndex(): Int = items.indexOfFirst { it.current }.coerceAtLeast(0)

    inner class VH(val b: ItemEpisodeRowBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemEpisodeRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.b.tvEpName.text = "${e.number}. ${e.title}" + if (e.current) "  •" else ""
        holder.b.tvEpOverview.isVisible = e.overview.isNotBlank()
        holder.b.tvEpOverview.text = e.overview
        holder.b.root.isActivated = e.current
        holder.b.root.alpha = if (e.url != null) 1f else 0.45f   // dim non-playable
        if (!e.stillUrl.isNullOrEmpty()) {
            Glide.with(holder.b.epStill).load(e.stillUrl).into(holder.b.epStill)
        } else {
            holder.b.epStill.setImageDrawable(null)
        }
        holder.b.root.setOnClickListener { onSelected(e) }
    }

    override fun getItemCount(): Int = items.size
}
