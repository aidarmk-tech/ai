package com.lampa.player.epg

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lampa.player.databinding.ActivityEpgBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*

data class EpgChannel(val id: String, val name: String, val logoUrl: String?)
data class EpgProgram(
    val channelId: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String = "",
)

@AndroidEntryPoint
class EpgActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEpgBinding

    private val channels = listOf(
        EpgChannel("ch1", "Первый канал", null),
        EpgChannel("ch2", "Россия 1", null),
        EpgChannel("ch3", "НТВ", null),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTimeRuler()
        setupEpgGrid()
        scrollToNow()
    }

    private fun setupTimeRuler() {
        val ruler = binding.timeRuler
        ruler.removeAllViews()
        val now = Calendar.getInstance()
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        now.add(Calendar.HOUR_OF_DAY, -2)

        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (i in 0..12) {
            val tv = android.widget.TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(120), android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
                text = fmt.format(now.time)
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFA8A8B8.toInt())
                textSize = 14f
            }
            ruler.addView(tv)
            now.add(Calendar.MINUTE, 30)
        }
    }

    private fun setupEpgGrid() {
        val container = binding.epgGrid
        container.removeAllViews()
        channels.forEach { ch ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(72)
                )
            }

            val label = android.widget.TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(240), android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
                text = ch.name
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF14141C.toInt())
                textSize = 16f
                setPadding(dpToPx(16), 0, dpToPx(16), 0)
            }
            row.addView(label)

            // Sample programs
            val samplePrograms = listOf(
                EpgProgram(ch.id, "Новости", System.currentTimeMillis() - 3600_000, System.currentTimeMillis() - 1800_000),
                EpgProgram(ch.id, "Художественный фильм", System.currentTimeMillis() - 1800_000, System.currentTimeMillis() + 3600_000),
            )
            samplePrograms.forEach { prog ->
                val durationMin = ((prog.endTime - prog.startTime) / 60_000).toInt()
                val widthDp = (durationMin * 4).coerceAtLeast(80)
                val progView = android.widget.TextView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dpToPx(widthDp), android.widget.LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        setMargins(2, 4, 2, 4)
                    }
                    text = prog.title
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xFF1E1E2A.toInt())
                    textSize = 14f
                    setPadding(dpToPx(8), 0, dpToPx(8), 0)
                    isFocusable = true
                    setOnFocusChangeListener { v, focused ->
                        v.scaleX = if (focused) 1.08f else 1f
                        v.scaleY = if (focused) 1.08f else 1f
                        if (focused) binding.infoPanelTitle.text = prog.title
                    }
                }
                row.addView(progView)
            }
            container.addView(row)
        }
    }

    private fun scrollToNow() {
        binding.epgScrollView.post {
            binding.epgScrollView.scrollTo(dpToPx(240), 0)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
