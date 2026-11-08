package com.lampplayer.tv.update

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class UpdateCheckTest {
    private val json="""{"versionCode":200,"versionName":"0.1.200","url":"https://github.com/aidarmk-tech/ai/releases/download/media-center/LampMedia-200.apk","sha256":"${"a".repeat(64)}"}"""
    @Test fun transientNetworkFailureRetriesAndFindsThePublishedUpdate() = runBlocking {
        var attempts=0
        val update=UpdateManager.checkUsing(145) { if(++attempts==1)throw java.io.IOException("HTTP 504");json }
        assertEquals(2,attempts);assertEquals(200,update!!.versionCode)
        assertNull(UpdateManager.parseManifest(json,200))
    }
    @Test fun failedTransportNeverMeansTheInstalledVersionIsLatest() = runBlocking {
        var attempts=0
        val failure=runCatching { UpdateManager.checkUsing(145) { attempts++;throw java.io.IOException("Offline") } }.exceptionOrNull()
        assertTrue(failure is java.io.IOException);assertEquals(2,attempts)
    }
    @Test fun invalidManifestAndCancellationAreNotRetriedOrTreatedAsNoUpdate() = runBlocking {
        var attempts=0
        assertTrue(runCatching { UpdateManager.checkUsing(145) { attempts++;"{}" } }.isFailure)
        assertEquals(1,attempts)
        attempts=0
        val failure=runCatching { UpdateManager.checkUsing(145) { attempts++;throw kotlinx.coroutines.CancellationException() } }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException);assertEquals(1,attempts)
        assertTrue(runCatching { UpdateManager.parseManifest(json.replace("https://","http://"),145) }.isFailure)
    }
}
