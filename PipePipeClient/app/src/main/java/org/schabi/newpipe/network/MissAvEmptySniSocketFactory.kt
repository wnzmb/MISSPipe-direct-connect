package org.schabi.newpipe.network

import android.util.Log

import javax.net.ssl.X509TrustManager

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * SSLSocketFactory that removes the SNI extension from TLS ClientHello entirely.
 *
 * Behavior per [MissAvSniConfig.Mode]:
 * - [MissAvSniConfig.Mode.PLAIN]: not used.
 * - [MissAvSniConfig.Mode.REPLACE]: not used here (use [MissAvSniSocketFactory]).
 * - [MissAvSniConfig.Mode.EMPTY]: forces an empty SNI list.
 *
 * This factory is intended to be installed on the OkHttpClient only when
 * [MissAvSniConfig.getMode] is [MissAvSniConfig.Mode.EMPTY].
 */
class MissAvEmptySniSocketFactory(private val trustManager: X509TrustManager) : SSLSocketFactory() {

    private val delegate: SSLSocketFactory

    init {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        delegate = sslContext.socketFactory
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val ip = socket.inetAddress.hostAddress
        val sslSocket = delegate.createSocket(socket, ip, port, autoClose) as SSLSocket
        return applyEmptySni(sslSocket)
    }

    override fun createSocket(host: String, port: Int): Socket {
        val ip = InetAddress.getByName(host).hostAddress
        val sslSocket = delegate.createSocket(ip, port) as SSLSocket
        return applyEmptySni(sslSocket)
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val ip = InetAddress.getByName(host).hostAddress
        val sslSocket = delegate.createSocket(ip, port, localHost, localPort) as SSLSocket
        return applyEmptySni(sslSocket)
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val ip = host.hostAddress
        val sslSocket = delegate.createSocket(ip, port) as SSLSocket
        return applyEmptySni(sslSocket)
    }

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket {
        val ip = host.hostAddress
        val sslSocket = delegate.createSocket(ip, port, localHost, localPort) as SSLSocket
        return applyEmptySni(sslSocket)
    }

    private fun applyEmptySni(socket: SSLSocket): SSLSocket {
        val params = socket.sslParameters
        params.serverNames = emptyList()
        socket.sslParameters = params
        return socket
    }
}
