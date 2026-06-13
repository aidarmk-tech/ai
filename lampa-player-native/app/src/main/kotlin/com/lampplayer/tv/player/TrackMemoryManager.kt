package com.lampplayer.tv.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.ShowData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

class TrackMemoryManager @Inject constructor(
    private val positionDataStore: PositionDataStore,
) {
    private val HLS_TRACK_WAIT_MS = 1200L

    fun applyTracks(
        player: Player,
        card: CardMeta,
        showData: ShowData,
        scope: CoroutineScope,
        isHls: Boolean,
    ) {
        scope.launch {
            if (isHls) delay(HLS_TRACK_WAIT_MS)
            applyAudioTrack(player, showData.audio)
            applySubtitleTrack(player, showData.subtitle)
        }
    }

    fun onAudioSelected(player: Player, card: CardMeta, index: Int, scope: CoroutineScope) {
        applyAudioTrack(player, index)                                   // switch now…
        scope.launch { positionDataStore.saveShowData(card, ShowData(audio = index)) }   // …and remember
    }

    fun onSubtitleSelected(player: Player, card: CardMeta, index: Int, scope: CoroutineScope) {
        applySubtitleTrack(player, index)                               // switch now…
        scope.launch { positionDataStore.saveShowData(card, ShowData(subtitle = index)) }   // …and remember
    }

    private fun applyAudioTrack(player: Player, savedIndex: Int?) {
        if (savedIndex == null) return
        val tracks = player.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (savedIndex >= audioGroups.size) return

        val params = player.trackSelectionParameters.buildUpon()
        val targetGroup = audioGroups[savedIndex]
        params.setOverrideForType(
            androidx.media3.common.TrackSelectionOverride(targetGroup.mediaTrackGroup, 0)
        )
        player.trackSelectionParameters = params.build()
    }

    private fun applySubtitleTrack(player: Player, savedIndex: Int?) {
        val params = player.trackSelectionParameters.buildUpon()
        if (savedIndex == null || savedIndex < 0) {
            // Fully off: disable the whole text track type and drop any override.
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            player.trackSelectionParameters = params.build()
            return
        }
        val tracks = player.currentTracks
        val subGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (savedIndex >= subGroups.size) return

        val targetGroup = subGroups[savedIndex]
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        params.setOverrideForType(
            androidx.media3.common.TrackSelectionOverride(targetGroup.mediaTrackGroup, 0)
        )
        player.trackSelectionParameters = params.build()
    }

    fun getAudioTracks(tracks: Tracks): List<String> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .mapIndexed { i, group -> "${i + 1}. " + langName(group.getTrackFormat(0).language) }

    fun getSubtitleTracks(tracks: Tracks): List<String> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .mapIndexed { i, group -> "${i + 1}. " + langName(group.getTrackFormat(0).language) }

    /** Friendly language label from an ISO code (best-effort, falls back to the code). */
    private fun langName(code: String?): String {
        val c = code?.lowercase()?.take(3) ?: return "Дорожка"
        return mapOf(
            "rus" to "Русский", "ru" to "Русский",
            "eng" to "English", "en" to "English",
            "ukr" to "Українська", "fra" to "Français", "fre" to "Français",
            "deu" to "Deutsch", "ger" to "Deutsch", "spa" to "Español",
            "ita" to "Italiano", "jpn" to "日本語", "kor" to "한국어",
            "chi" to "中文", "zho" to "中文", "tur" to "Türkçe",
            "pol" to "Polski", "kaz" to "Қазақша",
        )[c] ?: code.uppercase()
    }
}
