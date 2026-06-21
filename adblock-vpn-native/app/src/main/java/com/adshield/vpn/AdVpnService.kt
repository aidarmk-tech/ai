package com.adshield.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A no-root, DNS-only filtering VPN.
 *
 * It only routes the virtual DNS server address through the tunnel, so the
 * device's real traffic is untouched — we just get to inspect every DNS query.
 * Blocked domains are answered locally with 0.0.0.0; everything else is
 * forwarded to a real upstream resolver over a protected socket.
 */
class AdVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var isRunning = false

    private lateinit var blockList: BlockList
    private lateinit var upstream: String
    private var doh: DohResolver? = null
    private val forwarders: ExecutorService = Executors.newFixedThreadPool(8)
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        blockList = BlockList.load(this)
        upstream = Prefs.upstreamDns(this)
        doh = if (upstream.startsWith("https://")) DohResolver(this, upstream) else null
        VpnState.blockListSize.value = blockList.size
        VpnState.resetCounters()

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            // Interface address and the virtual DNS server MUST differ: if they
            // are equal the kernel treats DNS packets as local delivery and they
            // never reach the tunnel, so nothing resolves at all.
            .addAddress(IFACE_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            // Route ONLY our virtual DNS server through the tunnel.
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)
            .setMtu(VPN_MTU)

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }
        if (pfd == null) {
            stopSelf()
            return
        }
        vpnInterface = pfd

        startForeground(NOTIF_ID, buildNotification())
        isRunning = true
        VpnState.running.value = true
        requestTileUpdate(this)

        worker = Thread({ runPacketLoop(pfd) }, "AdVpn-worker").also { it.start() }
    }

    private fun runPacketLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(MTU)
        try {
            while (isRunning) {
                val len = input.read(packet)
                if (len <= 0) continue
                val req = PacketUtil.parseDnsRequest(packet, len) ?: continue
                handleRequest(req, output)
            }
        } catch (e: Exception) {
            if (isRunning) Log.w(TAG, "packet loop ended", e)
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun handleRequest(req: PacketUtil.DnsRequest, output: FileOutputStream) {
        VpnState.totalCount.incrementAndGet()
        val question = PacketUtil.parseQuestion(req.dnsPayload)
        if (question != null && blockList.isBlocked(question.name)) {
            VpnState.blockedCount.incrementAndGet()
            VpnState.addBlocked(question.name)
            val dnsResponse = PacketUtil.buildBlockedResponse(req.dnsPayload, question)
            writePacket(output, PacketUtil.buildResponsePacket(req, dnsResponse))
            return
        }
        // Allowed: forward to the real resolver without blocking the read loop.
        forwarders.execute { forwardUpstream(req, output) }
    }

    private fun forwardUpstream(req: PacketUtil.DnsRequest, output: FileOutputStream) {
        val resolver = doh
        if (resolver != null) {
            val response = runCatching { resolver.resolve(req.dnsPayload) }.getOrNull()
            if (response != null) {
                writePacket(output, PacketUtil.buildResponsePacket(req, response))
                return
            }
            // DoH failed → fall back to plain UDP so resolution never fully breaks.
            forwardUdp(req, output, DOH_FALLBACK_DNS)
            return
        }
        forwardUdp(req, output, upstream)
    }

    private fun forwardUdp(req: PacketUtil.DnsRequest, output: FileOutputStream, server: String) {
        try {
            DatagramSocket().use { socket ->
                // Critical: keep this socket OUT of our own VPN, or we'd loop.
                if (!protect(socket)) {
                    Log.w(TAG, "protect() failed for upstream socket")
                    return
                }
                socket.soTimeout = UPSTREAM_TIMEOUT_MS
                val address = InetAddress.getByName(server)
                socket.send(DatagramPacket(req.dnsPayload, req.dnsPayload.size, address, 53))

                val buf = ByteArray(MTU)
                val reply = DatagramPacket(buf, buf.size)
                socket.receive(reply)
                val response = buf.copyOf(reply.length)
                writePacket(output, PacketUtil.buildResponsePacket(req, response))
            }
        } catch (e: Exception) {
            // Timeout / network error → just drop; the client will retry.
            Log.v(TAG, "upstream forward failed: ${e.message}")
        }
    }

    private fun writePacket(output: FileOutputStream, data: ByteArray) {
        synchronized(writeLock) {
            try {
                output.write(data)
            } catch (e: Exception) {
                Log.v(TAG, "write failed: ${e.message}")
            }
        }
    }

    private fun stopVpn() {
        if (!isRunning && vpnInterface == null) {
            stopSelf()
            return
        }
        isRunning = false
        VpnState.running.value = false
        doh = null
        worker?.interrupt()
        worker = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        requestTileUpdate(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // The user (or another VPN app) tore down our tunnel.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        isRunning = false
        forwarders.shutdownNow()
        runCatching { vpnInterface?.close() }
        VpnState.running.value = false
        requestTileUpdate(this)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AdVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                @Suppress("DEPRECATION")
                Notification.Action.Builder(
                    0, getString(R.string.action_stop), stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "AdVpnService"
        const val ACTION_START = "com.adshield.vpn.START"
        const val ACTION_STOP = "com.adshield.vpn.STOP"

        private const val IFACE_ADDRESS = "10.111.222.1" // our end of the tunnel
        private const val DNS_ADDRESS = "10.111.222.2"   // virtual DNS server (must differ)
        private const val VPN_MTU = 4096
        private const val MTU = 32767 // read/receive buffer size
        private const val UPSTREAM_TIMEOUT_MS = 5000
        private const val DOH_FALLBACK_DNS = "1.1.1.1"
        private const val CHANNEL_ID = "adshield_vpn"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val intent = Intent(ctx, AdVpnService::class.java).setAction(ACTION_START)
            ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, AdVpnService::class.java).setAction(ACTION_STOP)
            ctx.startService(intent)
        }

        fun requestTileUpdate(ctx: Context) {
            runCatching {
                TileService.requestListeningState(
                    ctx, ComponentName(ctx, AdBlockTileService::class.java),
                )
            }
        }
    }
}
