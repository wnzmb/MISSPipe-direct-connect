package org.schabi.newpipe.network

import android.util.Log
import org.schabi.newpipe.extractor.services.missav.MissAvDomainManager

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLParameters
import javax.net.ssl.X509TrustManager

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate

/**
 * SSLSocketFactory that replaces the SNI hostname with [MissAvSniConfig.getReplacementHost()].
 *
 * Behavior per [MissAvSniConfig.Mode]:
 * - [MissAvSniConfig.Mode.PLAIN]: not used.
 * - [MissAvSniConfig.Mode.REPLACE]: forces SNI to the configured replacement host.
 * - [MissAvSniConfig.Mode.EMPTY]: not used here (use [MissAvEmptySniSocketFactory]).
 *
 * This factory is intended to be installed on the OkHttpClient only when
 * [MissAvSniConfig.getMode] is [MissAvSniConfig.Mode.REPLACE].
 */
class MissAvSniSocketFactory(private val trustManager: X509TrustManager) : SSLSocketFactory() {

    private val delegate: SSLSocketFactory

    init {
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())
        delegate = sslContext.socketFactory
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val ip = socket.inetAddress.hostAddress
        val sslSocket = delegate.createSocket(socket, ip, port, autoClose) as SSLSocket
        return applySni(sslSocket, MissAvSniConfig.getReplacementHost())
    }

    override fun createSocket(host: String, port: Int): Socket {
        val ip = InetAddress.getByName(host).hostAddress
        val sslSocket = delegate.createSocket(ip, port) as SSLSocket
        return applySni(sslSocket, MissAvSniConfig.getReplacementHost())
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val ip = InetAddress.getByName(host).hostAddress
        val sslSocket = delegate.createSocket(ip, port, localHost, localPort) as SSLSocket
        return applySni(sslSocket, MissAvSniConfig.getReplacementHost())
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val ip = host.hostAddress
        val sslSocket = delegate.createSocket(ip, port) as SSLSocket
        return applySni(sslSocket, MissAvSniConfig.getReplacementHost())
    }

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket {
        val ip = host.hostAddress
        val sslSocket = delegate.createSocket(ip, port, localHost, localPort) as SSLSocket
        return applySni(sslSocket, MissAvSniConfig.getReplacementHost())
    }

    private fun applySni(socket: SSLSocket, sniHost: String): SSLSocket {
        val params = socket.sslParameters
        params.serverNames = listOf(javax.net.ssl.SNIHostName(sniHost))
        socket.sslParameters = params
        return socket
    }
}
