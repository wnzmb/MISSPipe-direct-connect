package org.schabi.newpipe.network

import android.util.Log
import org.schabi.newpipe.extractor.services.missav.MissAvDomainManager

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

/**
 * Configuration holder for MissAV SNI bypass.
 *
 * Modes:
 * - [Mode.PLAIN]   : no SNI manipulation, use system default
 * - [Mode.REPLACE] : replace SNI with [replacementHost] in TLS ClientHello
 * - [Mode.EMPTY]   : send no SNI extension at all
 *
 * The replacement host should be a domain whose certificate is shared with or
 * acceptable to the MissAV backend. Candidates are determined by pre-deployment
 * probing (see project `计划.md`).
 */
object MissAvSniConfig {

    private const val TAG = "MissAvSniConfig"

    enum class Mode { PLAIN, REPLACE, EMPTY }

    @Volatile
    private var mode: Mode = Mode.PLAIN

    @Volatile
    private var replacementHost: String = "missav.one"

    @Volatile
    private var trustAllForDirectConnect: Boolean = true

    fun setMode(mode: Mode) {
        this.mode = mode
        Log.d(TAG, "SNI mode set to $mode")
    }

    fun getMode(): Mode = mode

    fun setReplacementHost(host: String) {
        this.replacementHost = host
        Log.d(TAG, "SNI replacement host set to $host")
    }

    fun getReplacementHost(): String = replacementHost

    fun setTrustAllForDirectConnect(enabled: Boolean) {
        this.trustAllForDirectConnect = enabled
    }

    fun isTrustAllForDirectConnect(): Boolean = trustAllForDirectConnect

    fun isDirectConnectHost(host: String?): Boolean {
        if (host == null) return false
        return MissAvDomainManager.isKnownMissAvDomain(host)
                || host.equals("fourhoi.com", ignoreCase = true)
    }

    @JvmStatic
    fun createHostnameVerifier(): HostnameVerifier {
        return when (mode) {
            Mode.PLAIN -> javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
            Mode.REPLACE,
            Mode.EMPTY -> HostnameVerifier { hostname, session ->
                if (isDirectConnectHost(hostname)) {
                    if (trustAllForDirectConnect) {
                        return@HostnameVerifier true
                    }
                    val peerIp = session.peerHost
                    val expectedHost = if (mode == Mode.REPLACE) replacementHost else null
                    if (expectedHost != null && peerIp == expectedHost) {
                        return@HostnameVerifier true
                    }
                }
                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify(hostname, session)
            }
        }
    }
}
