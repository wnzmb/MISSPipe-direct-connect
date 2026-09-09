package org.schabi.newpipe.network

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.extractor.services.missav.MissAvDomainManager

/**
 * Custom DNS resolver for MissAV that bypasses DNS pollution by:
 * 1. First trying built-in hardcoded IPs for known blocked domains
 * 2. Falling back to DoH (DNS over HTTPS) via Cloudflare
 * 3. Finally falling back to system DNS
 *
 * Built-in IPs are updated periodically and sourced from public CDN lists.
 *
 * @see <a href="https://1.1.1.1/">Cloudflare DoH</a>
 */
object MissAvDns : Dns {

    private const val TAG = "MissAvDns"
    private const val DOH_URL = "https://cloudflare-dns.com/dns-query"
    private const val DOH_TIMEOUT_SECONDS = 5L

    // Domains handled by the direct-connect IP provider
    private const val FOURHOI_HOST = "fourhoi.com"

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DOH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DOH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var dohAvailable = true

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Direct-connect domains (MissAV main domains + image CDN):
        //    rotated built-in/user-defined IPs, bypassing polluted DNS entirely
        val directIps = MissAvIpProvider.nextIps(hostname)
        if (directIps.isNotEmpty()) {
            val addresses = directIps.mapNotNull { ip ->
                try {
                    InetAddress.getByName(ip)
                } catch (e: Exception) {
                    if (MainActivity.DEBUG) {
                        Log.w(TAG, "Invalid built-in IP $ip for $hostname", e)
                    }
                    null
                }
            }
            if (addresses.isNotEmpty()) {
                Log.d(TAG, "Using direct-connect IPs for $hostname: $addresses")
                return addresses
            }
        }

        // 2. For other domains, try DoH first if available
        if (dohAvailable) {
            try {
                val dohResult = lookupByDoH(hostname)
                if (dohResult.isNotEmpty()) {
                    Log.d(TAG, "DoH resolved $hostname: ${dohResult.joinToString()}")
                    return dohResult
                }
            } catch (e: Exception) {
                if (MainActivity.DEBUG) {
                    Log.w(TAG, "DoH lookup failed for $hostname", e)
                }
                // Mark DoH as temporarily unavailable to avoid repeated failures
                dohAvailable = false
            }
        }

        // 3. Fallback to system DNS
        Log.d(TAG, "Falling back to system DNS for $hostname")
        return Dns.SYSTEM.lookup(hostname)
    }

    /**
     * Performs DNS lookup using DNS over HTTPS (Cloudflare).
     * Returns empty list on failure.
     */
    private fun lookupByDoH(hostname: String): List<InetAddress> {
        val url = "$DOH_URL?name=$hostname&type=A&do=1"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()

        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DoH request failed: ${response.code}")
            }

            val body = response.body?.string() ?: return emptyList()
            return parseDoHResponse(body)
        }
    }

    /**
     * Parse Cloudflare DoH JSON response.
     * Response format: { "Status": 0, "Answer": [{ "data": "1.2.3.4", ... }] }
     */
    private fun parseDoHResponse(body: String): List<InetAddress> {
        return try {
            // Simple JSON parsing without external dependencies
            val addresses = mutableListOf<InetAddress>()

            // Extract IP addresses from the Answer section
            val answerStart = body.indexOf("\"Answer\"")
            if (answerStart < 0) return emptyList()

            // Find all IP-like patterns in the Answer section
            val ipPattern = Regex("\"data\":\\s*\"([\\d.]+)\"")
            val matches = ipPattern.findAll(body.substring(answerStart))

            for (match in matches) {
                try {
                    addresses.add(InetAddress.getByName(match.groupValues[1]))
                } catch (e: Exception) {
                    // Skip invalid IPs
                }
            }

            addresses
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Reset DoH availability status. Called after a timeout to allow retry.
     */
    fun resetDohAvailability() {
        dohAvailable = true
    }

    /**
     * Get the direct-connect domain list (MissAV main domains + image CDN).
     */
    fun getBuiltInDomains(): Set<String> =
        MissAvDomainManager.getDomains().toSet() + FOURHOI_HOST
}