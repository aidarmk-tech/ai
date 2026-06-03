package com.lampplayer.tv.iptv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.lampplayer.tv.databinding.ActivityIptvBinding
import com.lampplayer.tv.epg.EpgActivity
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*

data class ChannelGroup(val name: String)
data class Channel(val number: Int, val name: String, val logoUrl: String?, val streamUrl: String, val currentShow: String = "")

@AndroidEntryPoint
class IptvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIptvBinding
    private var selectedGroupIndex = 0
    private var selectedChannelIndex = 0

    private val groups = listOf(
        ChannelGroup("Все"), ChannelGroup("Новости"), ChannelGroup("Кино"), ChannelGroup("Спорт"),
    )

    private val channels = listOf(
        Channel(1, "Первый канал", null, "http://example.com/ch1.m3u8", "Новости"),
        Channel(2, "Россия 1", null, "http://example.com/ch2.m3u8", "Вечерний Ургант"),
        Channel(3, "НТВ", null, "http://example.com/ch3.m3u8", "Сегодня"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIptvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGroupList()
        setupChannelList()
        updateClock()
        binding.btnEpg.setOnClickListener { openEpg() }
    }

    private fun setupGroupList() {
        binding.groupList.adapter = GroupAdapter(groups, selectedGroupIndex) { index ->
            selectedGroupIndex = index
            setupChannelList()
        }
    }

    private fun setupChannelList() {
        binding.channelList.adapter = ChannelAdapter(channels) { channel ->
            openPlayer(channel)
        }
    }

    private fun updateClock() {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        binding.tvClock.text = fmt.format(Date())
        binding.tvClock.postDelayed(::updateClock, 30_000)
    }

    private fun openPlayer(channel: Channel) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(packageName, "com.lampplayer.tv.player.PlayerActivity")
            data = android.net.Uri.parse(channel.streamUrl)
            putExtra("title", channel.name)
        }
        startActivity(intent)
    }

    private fun openEpg() {
        startActivity(Intent(this, EpgActivity::class.java))
    }
}

class GroupAdapter(
    private val groups: List<ChannelGroup>,
    private var selectedIndex: Int,
    private val onSelected: (Int) -> Unit,
) : androidx.recyclerview.widget.RecyclerView.Adapter<GroupAdapter.VH>() {

    inner class VH(val view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvName: android.widget.TextView = view.findViewById(com.lampplayer.tv.R.id.tv_group_name)
        val indicator: android.view.View = view.findViewById(com.lampplayer.tv.R.id.group_indicator)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context).inflate(com.lampplayer.tv.R.layout.item_channel_group, parent, false)
        return VH(v)
    }

    override fun getItemCount() = groups.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tvName.text = groups[position].name
        holder.indicator.visibility = if (position == selectedIndex) android.view.View.VISIBLE else android.view.View.INVISIBLE
        holder.itemView.setOnClickListener {
            val prev = selectedIndex
            selectedIndex = position
            notifyItemChanged(prev)
            notifyItemChanged(position)
            onSelected(position)
        }
    }
}

class ChannelAdapter(
    private val channels: List<Channel>,
    private val onSelected: (Channel) -> Unit,
) : androidx.recyclerview.widget.RecyclerView.Adapter<ChannelAdapter.VH>() {

    inner class VH(val view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvNumber: android.widget.TextView = view.findViewById(com.lampplayer.tv.R.id.tv_channel_number)
        val tvName: android.widget.TextView = view.findViewById(com.lampplayer.tv.R.id.tv_channel_name)
        val tvShow: android.widget.TextView = view.findViewById(com.lampplayer.tv.R.id.tv_current_show)
        val ivLogo: android.widget.ImageView = view.findViewById(com.lampplayer.tv.R.id.iv_channel_logo)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context).inflate(com.lampplayer.tv.R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = channels[position]
        holder.tvNumber.text = ch.number.toString()
        holder.tvName.text = ch.name
        holder.tvShow.text = ch.currentShow
        holder.itemView.setOnClickListener { onSelected(ch) }
        holder.itemView.setOnFocusChangeListener { _, focused ->
            holder.itemView.scaleX = if (focused) 1.08f else 1f
            holder.itemView.scaleY = if (focused) 1.08f else 1f
        }
    }
}
