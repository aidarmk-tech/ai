package com.lampplayer.tv.center

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lampplayer.tv.R
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Owns the bundled engine's lifetime, private data directory and loopback-only HTTP listener. */
class CenterEngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var starting = false
    companion object {
        private val stateLock = Any()
        private var owner: CenterEngineService? = null
        @Volatile var base: String? = null; private set
        @Volatile private var failure: String? = null
        private val probe = OkHttpClient.Builder().connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS).callTimeout(800, TimeUnit.MILLISECONDS).build()
        suspend fun ensure(context: Context): String {
            synchronized(stateLock) { failure = null }
            ContextCompat.startForegroundService(context, Intent(context, CenterEngineService::class.java))
            return withContext(Dispatchers.IO) {
                withTimeout(20000) {
                    while (true) {
                        failure?.let { error(it) }
                        val endpoint = base
                        if (endpoint != null && runCatching {
                                probe.newCall(Request.Builder().url("$endpoint/echo").build()).execute().use { it.isSuccessful }
                            }.getOrDefault(false)) return@withTimeout endpoint
                        delay(250)
                    }
                    @Suppress("UNREACHABLE_CODE") error("Движок не запустился")
                }
            }
        }
        fun stop(context: Context) { context.stopService(Intent(context, CenterEngineService::class.java)) }
        fun owns(url: String) = base?.let { url.startsWith("$it/") } == true
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("center_engine", "Торрент-движок", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MediaCenterActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CenterEngineService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(401, NotificationCompat.Builder(this, "center_engine")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("LampMedia")
            .setContentText("Торрент-движок активен").setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (starting || process?.let { runCatching { it.exitValue() }.isFailure } == true) return START_NOT_STICKY
        starting = true
        synchronized(stateLock) { owner = this; failure = null; base = null }
        scope.launch {
            try {
                val executable = File(applicationInfo.nativeLibraryDir, "libtorrentengine.so")
                check(executable.isFile) { "В этой сборке отсутствует торрент-движок для устройства" }
                val port = ServerSocket(0).use { it.localPort }
                val data = File(filesDir, "torrent-engine").apply { mkdirs() }
                val log = File(cacheDir, "torrent-engine.log")
                if (log.length() > 2 * 1024 * 1024) log.delete()
                val child = ProcessBuilder(executable.absolutePath, "-p", port.toString(), "--ip", "127.0.0.1", "-d", data.absolutePath)
                    .directory(data).redirectErrorStream(true).start()
                synchronized(stateLock) {
                    if (owner !== this@CenterEngineService || !isActive) { child.destroy(); return@launch }
                    process = child; base = "http://127.0.0.1:$port"
                }
                scope.launch {
                    runCatching { child.inputStream.use { input -> log.outputStream().use { out ->
                        val buffer = ByteArray(4096); var total = 0
                        while (isActive) {
                            val count = input.read(buffer); if (count < 0) break
                            if (total < 2 * 1024 * 1024) { out.write(buffer, 0, count); total += count }
                        }
                    } } }
                }
                val exit = child.waitFor()
                synchronized(stateLock) { if (owner === this@CenterEngineService && isActive)
                    failure = "Торрент-движок завершился (код $exit). Открой раздел «Источники» и запусти его снова" }
            } catch (e: Exception) { synchronized(stateLock) {
                if (owner === this@CenterEngineService && isActive) failure = e.message ?: "Ошибка запуска движка"
            } }
            finally { starting = false; synchronized(stateLock) { if (owner === this@CenterEngineService) base = null } }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        synchronized(stateLock) {
            process?.destroy(); process = null
            if (owner === this) { owner = null; base = null; failure = null }
        }
        super.onDestroy()
    }
}
