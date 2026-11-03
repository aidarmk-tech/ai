package com.lampplayer.tv.center

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CenterEngineInstrumentedTest {
    @Test fun installedNativeExecutableStartsAndAnswersOnLoopback() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MediaCenterActivity::class.java).use {
            try {
                val base=CenterEngineService.ensure(context)
                assertTrue(base.startsWith("http://127.0.0.1:"))
                OkHttpClient().newCall(Request.Builder().url("$base/echo").build()).execute().use { response ->
                    assertTrue(response.isSuccessful)
                    assertTrue(response.body!!.string().contains("148"))
                }
            } finally { CenterEngineService.stop(context) }
        }
    }
}
