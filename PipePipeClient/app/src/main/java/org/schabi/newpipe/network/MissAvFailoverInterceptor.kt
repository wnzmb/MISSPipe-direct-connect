package org.schabi.newpipe.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import org.schabi.newpipe.extractor.services.missav.MissAvDomainManager
import java.io.IOException

/**
 * Marks failed direct-connect endpoints so the provider rotates to the next one:
 * on a connect/IO failure the exact IP chosen for the connection (available from
 * [Interceptor.Chain.connection]) is marked unavailable, and a known MissAV main
 * domain is marked failed so URL building switches to the next backup domain.
 *
 * Must be installed as a network interceptor, otherwise the connection (and thus
 * the resolved IP) is not accessible.
 */
class MissAvFailoverInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        return try {
            val response = chain.proceed(request)
            if (response.code >= 500) {
                markDomainFailed(host, "HTTP ${response.code}")
            }
            response
        } catch (e: IOException) {
            val ip = chain.connection()?.socket()?.inetAddress?.hostAddress
            if (ip != null) {
                Log.w(TAG, "Request to $host via $ip failed: $e")
                MissAvIpProvider.markUnavailable(ip)
            }
            markDomainFailed(host, e.javaClass.simpleName)
            throw e
        }
    }

    private fun markDomainFailed(host: String, reason: String) {
        if (MissAvDomainManager.isKnownMissAvDomain(host)) {
            Log.w(TAG, "Marking MissAV domain failed ($reason): $host")
            MissAvDomainManager.markFailed(host)
        }
    }

    companion object {
        private const val TAG = "MissAvFailover"
    }
}
