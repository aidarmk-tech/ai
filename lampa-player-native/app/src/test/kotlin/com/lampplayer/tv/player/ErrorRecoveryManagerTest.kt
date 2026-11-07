package com.lampplayer.tv.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class ErrorRecoveryManagerTest {
    private val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
        error("HTTP recovery must not change player parameters: ${method.name}")
    } as Player
    private fun http(code: Int): PlaybackException {
        val failure = HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(),
            DataSpec(Uri.parse("https://cdn.test/video/master.m3u8?token=private")), byteArrayOf())
        return PlaybackException("HTTP", IOException("wrapped", failure), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }
    @Test fun missingVideoStopsAfterThreeRetriesAndNeverClaimsConnectivityLoss() {
        val manager=ErrorRecoveryManager()
        repeat(3) {
            assertEquals(ErrorRecoveryManager.Action.RETRY,manager.onError(http(404),player))
            assertFalse(manager.lastWasNetwork)
        }
        repeat(5) { assertEquals(ErrorRecoveryManager.Action.SHOW_FATAL,manager.onError(http(404),player)) }
        assertTrue(ErrorRecoveryManager.httpMessage(http(404))!!.contains("HTTP 404"))
        assertFalse(ErrorRecoveryManager.httpMessage(http(404))!!.contains("private"))
        manager.reset()
        assertEquals(ErrorRecoveryManager.Action.RETRY,manager.onError(http(404),player))
    }
    @Test fun accessDeniedAndGoneStopImmediately() {
        for(code in listOf(401,403,410)) {
            val manager=ErrorRecoveryManager()
            assertEquals(ErrorRecoveryManager.Action.SHOW_FATAL,manager.onError(http(code),player))
            assertFalse(manager.lastWasNetwork)
            assertTrue(ErrorRecoveryManager.httpMessage(http(code))!!.contains("HTTP $code"))
        }
    }
    @Test fun temporaryHttpErrorsStillReconnect() {
        for(code in listOf(408,429,500,502,503)) {
            val manager=ErrorRecoveryManager()
            repeat(10) {
                assertEquals(ErrorRecoveryManager.Action.RETRY,manager.onError(http(code),player))
                assertTrue(manager.lastWasNetwork)
            }
        }
    }
    @Test fun connectionLossStillRecoversButPermissionErrorsDoNotLoop() {
        val manager=ErrorRecoveryManager()
        val offline=PlaybackException("offline",IOException(),PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        repeat(10) { assertEquals(ErrorRecoveryManager.Action.RETRY,manager.onError(offline,player)) }
        assertTrue(manager.lastWasNetwork)
        val denied=PlaybackException("denied",null,PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED)
        assertEquals(ErrorRecoveryManager.Action.SHOW_FATAL,manager.onError(denied,player))
        assertFalse(manager.lastWasNetwork)
    }
    @Test fun nestedFailureIdentifiesTheActualRequestedResource() {
        val failure=ErrorRecoveryManager.httpFailure(http(404))!!
        assertEquals(404,failure.responseCode)
        assertEquals("cdn.test",failure.dataSpec.uri.host)
        assertEquals("master.m3u8",failure.dataSpec.uri.lastPathSegment)
        assertNull(ErrorRecoveryManager.httpFailure(IOException("no http response")))
    }
}
