package com.lampplayer.tv.center

import androidx.core.view.doOnLayout
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lampplayer.tv.BuildConfig
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.player.PlayerActivity
import com.lampplayer.tv.player.TorrentEpisodeResolver
import com.lampplayer.tv.settings.SettingsActivity
import com.lampplayer.tv.update.UpdateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.ArrayDeque

/* Экран «История» и действия с записями «Продолжить просмотр». */

internal fun MediaCenterActivity.historyScreen() {
    currentScreen = { historyScreen() }; page = "history"; clear("История просмотра")
    val history = store.history()
    if (history.isEmpty()) content.addView(text("Здесь появятся фильмы и сериалы после начала просмотра", 17, muted))
    else {
        content.addView(text("Удерживай ОК на карточке: продолжить, открыть карточку или убрать из истории", 14, muted)); space(content, 16)
        historyCards(history)
    }
}
internal fun MediaCenterActivity.historyCards(history: List<CenterHistory>) {
    rail(object : RecyclerView.Adapter<HistoryHolder>() {
        override fun getItemCount() = history.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
            val view = column().apply {
                isFocusable = true; isClickable = true; setPadding(dp(7), dp(7), dp(7), dp(7)); background = bg(panel)
                layoutParams = RecyclerView.LayoutParams(dp(220), dp(200)).apply { rightMargin = dp(14) }
            }
            val image = FrameLayout(this@historyCards); view.addView(image, LinearLayout.LayoutParams(-1, dp(110)))
            val title = text("", 15).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) }
            val time = text("", 12, muted).apply { setPadding(0, dp(6), 0, dp(7)) }
            val progress = ProgressBar(this@historyCards, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            view.addView(title); view.addView(time); view.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
            return HistoryHolder(view, image, title, time, progress)
        }
        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            val saved = history[position]; holder.url = saved.url; val card = saved.card
            holder.title.text = card.title
            val episode = card.episodeNumber?.let { number -> (card.seasonNumber?.let { "S$it · " } ?: "") + "Серия $number" } ?: if (card.contentKey?.contains(":tv:") == true) "Сериал" else "Фильм"
            holder.time.text = episode; holder.progress.progress = 0
            holder.art.removeAllViews(); holder.art.addView(artFrame(card.title, 220, 110, card.backdropUrl ?: card.posterUrl), FrameLayout.LayoutParams(-1, -1))
            holder.itemView.tag = historyTag(card)
            holder.itemView.setOnClickListener { enter { resume(saved) } }
            holder.itemView.setOnLongClickListener { historyActions(saved, history, holder.bindingAdapterPosition); true }
            holder.itemView.setOnFocusChangeListener { v, focus -> paintCard(v, focus) }
            lifecycleScope.launch {
                val progress = PositionDataStore(applicationContext).getPosition(saved.url, card)
                if (holder.url == saved.url && progress != null) {
                    holder.time.text = "$episode · ${progress.time.toInt() / 60}:${(progress.time.toInt() % 60).toString().padStart(2, '0')}"
                    holder.progress.progress = progress.percent.coerceIn(0, 100)
                }
            }
        }
    }, 220)
}
/** Меню записи «Продолжить»: ничего не меняется без явного выбора, удаление можно отменить. */
internal fun MediaCenterActivity.historyActions(saved: CenterHistory, history: List<CenterHistory>, position: Int) {
    val card = saved.card
    val actions = listOf<Pair<String, () -> Unit>>(
        "Продолжить" to { enter { resume(saved) } },
        "Открыть карточку" to { enter { detail(historyTitle(card)) } },
        "Убрать из истории" to { removeFromHistory(saved, history, position) })
    AlertDialog.Builder(this).setTitle(card.title).setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
        .setNegativeButton("Отмена", null).show()
}
internal fun MediaCenterActivity.removeFromHistory(saved: CenterHistory, history: List<CenterHistory>, position: Int) {
    // Фокус переходит на соседнюю карточку, а не в начало экрана.
    val neighbour = history.getOrNull(position + 1) ?: history.getOrNull(position - 1)
    store.removeHistory(saved.card); currentScreen?.invoke()
    content.post {
        val target = neighbour?.let { n -> findTag(content, historyTag(n.card)) }
        (target ?: content.getFocusables(View.FOCUS_FORWARD).firstOrNull())?.requestFocus()
    }
    AlertDialog.Builder(this).setMessage("«${saved.card.title}» убран из истории. Таймкод сохранён.")
        .setPositiveButton("Готово", null)
        .setNegativeButton("Отменить") { _, _ ->
            store.restoreHistory(saved); currentScreen?.invoke()
            content.post { findTag(content, historyTag(saved.card))?.requestFocus() }
        }.show()
}
internal fun MediaCenterActivity.historyTag(card: CardMeta) = "history:${card.contentKey ?: card.tmdbId}:${card.seasonNumber}:${card.title}"
/** Восстанавливает карточку каталога из записи истории (TMDB или сайт). */
internal fun MediaCenterActivity.historyTitle(card: CardMeta): CenterTitle {
    val key = card.contentKey?.split(':').orEmpty()
    val provider = key.firstOrNull()?.takeIf { CenterCatalog.isSiteIdentity(it) }
    return CenterTitle(key.getOrNull(2)?.toIntOrNull() ?: card.tmdbId ?: 0,
        if (card.seasonNumber != null || key.getOrNull(1) == "tv") "tv" else "movie",
        card.title, original = card.originalTitle.orEmpty(), overview = card.overview.orEmpty(),
        poster = card.posterUrl, year=card.releaseYear?.toString().orEmpty(), provider = provider, sourceUrl = card.sourcePage)
}
private class HistoryHolder(view: View, val art: FrameLayout, val title: TextView, val time: TextView, val progress: ProgressBar) : RecyclerView.ViewHolder(view) { var url = "" }

