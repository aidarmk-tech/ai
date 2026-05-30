package com.lampa.player.player

import com.lampa.player.data.datastore.PositionDataStore
import com.lampa.player.domain.model.CardMeta
import com.lampa.player.domain.model.ShowData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class IntroSkipManager @Inject constructor(
    private val positionDataStore: PositionDataStore,
) {
    fun markIntro(
        card: CardMeta,
        currentTimeSec: Double,
        scope: CoroutineScope,
        onSaved: (Double) -> Unit,
    ) {
        scope.launch {
            positionDataStore.saveShowData(card, ShowData(introEnd = currentTimeSec))
            onSaved(currentTimeSec)
        }
    }

    fun shouldShowSkipButton(currentTimeSec: Double, introEnd: Double?): Boolean {
        if (introEnd == null || introEnd <= 0.0) return false
        return currentTimeSec < introEnd
    }
}
