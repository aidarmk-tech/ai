package com.lampa.player.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.lampa.player.data.datastore.PositionDataStore
import com.lampa.player.domain.model.CardMeta
import com.lampa.player.domain.model.ShowData
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
        scope.launch {
            positionDataStore.saveShowData(card, ShowData(audio = index))
        }
    }

    fun onSubtitleSelected(player: Player, card: CardMeta, index: Int, scope: CoroutineScope) {
        scope.launch {
            positionDataStore.saveShowData(card, ShowData(subtitle = index))
        }
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
            params.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            player.trackSelectionParameters = params.build()
            return
        }
        val tracks = player.currentTracks
        val subGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (savedIndex >= subGroups.size) return

        val targetGroup = subGroups[savedIndex]
        params.setOverrideForType(
            androidx.media3.common.TrackSelectionOverride(targetGroup.mediaTrackGroup, 0)
        )
        player.trackSelectionParameters = params.build()
    }

    fun getAudioTracks(tracks: Tracks): List<String> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .mapIndexed { i, group ->
                group.getTrackFormat(0).language?.let { lang ->
                    "$i: $lang"
                } ?: "$i: Audio ${i + 1}"
            }

    fun getSubtitleTracks(tracks: Tracks): List<String> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .mapIndexed { i, group ->
                group.getTrackFormat(0).language?.let { lang ->
                    "$i: $lang"
                } ?: "$i: Subtitles ${i + 1}"
            }
}
