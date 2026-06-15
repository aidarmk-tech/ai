package com.lampplayer.tv.player

import android.media.audiofx.DynamicsProcessing
import android.os.Build

/**
 * "Night mode" for ExoPlayer: tames loud peaks (explosions/music) and lifts
 * dialogue via Android's DynamicsProcessing effect (API 28+):
 *  - a multi-band compressor with a low threshold + makeup gain compresses loud,
 *  - a post-EQ bump around speech frequencies (~2.5 kHz) makes voices clearer,
 *  - a limiter catches the remaining peaks.
 * All calls are guarded — on any device quirk it simply does nothing.
 */
class NightModeController {

    private var dp: DynamicsProcessing? = null

    fun apply(audioSessionId: Int, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return   // DynamicsProcessing = API 28
        if (!enabled) { release(); return }
        if (audioSessionId == 0) return
        runCatching {
            release()
            val channels = 2
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channels,
                /* preEq */ false, 0,
                /* mbc */ true, 1,
                /* postEq */ true, 1,
                /* limiter */ true,
            ).build()
            val effect = DynamicsProcessing(0, audioSessionId, cfg)
            for (ch in 0 until channels) {
                // Compressor: pull loud content down, add make-up gain so quiet stays audible.
                runCatching {
                    val mbc = effect.getMbcByChannelIndex(ch)
                    val band = mbc.getBand(0)
                    band.isEnabled = true
                    band.attackTime = 5f
                    band.releaseTime = 120f
                    band.ratio = 6f
                    band.threshold = -28f
                    band.postGain = 9f
                    mbc.setBand(0, band)
                    effect.setMbcByChannelIndex(ch, mbc)
                }
                // Post-EQ: a gentle bump in the speech band for clearer dialogue.
                runCatching {
                    val eq = effect.getPostEqByChannelIndex(ch)
                    val b = eq.getBand(0)
                    b.isEnabled = true
                    b.cutoffFrequency = 2500f
                    b.gain = 4f
                    eq.setBand(0, b)
                    effect.setPostEqByChannelIndex(ch, eq)
                }
                // Limiter: hard ceiling so the make-up gain never clips.
                runCatching {
                    val lim = effect.getLimiterByChannelIndex(ch)
                    lim.isEnabled = true
                    lim.attackTime = 1f
                    lim.releaseTime = 60f
                    lim.ratio = 10f
                    lim.threshold = -2f
                    lim.postGain = 0f
                    effect.setLimiterByChannelIndex(ch, lim)
                }
            }
            effect.enabled = true
            dp = effect
        }
    }

    fun release() {
        runCatching { dp?.release() }
        dp = null
    }
}
