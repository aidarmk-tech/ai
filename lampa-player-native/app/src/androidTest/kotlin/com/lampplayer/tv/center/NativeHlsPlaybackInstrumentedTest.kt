package com.lampplayer.tv.center

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lampplayer.tv.player.PlayerActivity
import com.lampplayer.tv.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A public hls.js/Mux Big Buck Bunny sample, independent of any provider's regional availability. */
@RunWith(AndroidJUnit4::class)
class NativeHlsPlaybackInstrumentedTest {
    @androidx.media3.common.util.UnstableApi
    @Test fun ourPlayerActuallyAdvancesWhenOpenedWithAnOnlineHlsIntent() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val url="https://test-streams.mux.dev/x36xhzz/url_6/193039199_mp4_h264_aac_hq_7.m3u8"
        val intent=Intent(context,PlayerActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url),"video/*")
            .putExtra("lampa_data","""{"title":"Big Buck Bunny · HLS integration check","content_key":"test:movie:1","quality":"480"}""")
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            withTimeout(35000) {
                var advanced=false
                while(!advanced) {
                    scenario.onActivity {
                        val vm=ViewModelProvider(it)[PlayerViewModel::class.java]
                        val state=vm.uiState.value
                        assertFalse("Native HLS playback error: ${state.errorMessage}",state.hasError)
                        advanced=vm.positionMs()>1500&&vm.isPlayingNow()
                    }
                    if(!advanced)delay(250)
                }
            }
        }
    }
}
