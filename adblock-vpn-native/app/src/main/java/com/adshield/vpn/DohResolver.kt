package com.adshield.vpn

import android.net.VpnService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Resolves DNS queries over DNS-over-HTTPS (RFC 8484).
 *
 * The [url] must use an IP literal (e.g. https://1.1.1.1/dns-query) so OkHttp
 * never needs a plaintext DNS lookup to reach it — which would otherwise loop
 * straight back into our own VPN.
 */
class DohResolver(vpn: VpnService, private val url: String) {

    private val mediaType = "application/dns-message".toMediaType()
    private val client = OkHttpClient.Builder()
        .socketFactory(ProtectingSocketFactory(vpn))
        .callTimeout(6, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Returns the raw DNS response bytes, or null on failure. */
    fun resolve(query: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-message")
            .post(query.toRequestBody(mediaType))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.bytes()
        }
    }
}

/** A SocketFactory that excludes every socket it creates from the VPN tunnel. */
private class ProtectingSocketFactory(private val vpn: VpnService) : SocketFactory() {

    private fun protectedSocket(): Socket = Socket().also { vpn.protect(it) }

    override fun createSocket(): Socket = protectedSocket()

    override fun createSocket(host: String?, port: Int): Socket =
        protectedSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        protectedSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        protectedSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        protectedSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}
