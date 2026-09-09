package org.schabi.newpipe.network

import android.util.Log
import org.schabi.newpipe.extractor.services.missav.MissAvDomainManager

/**
 * Round-robin IP provider for MissAV direct connect.
 *
 * Candidate IPs per host = user-defined custom IPs (highest priority) + built-in
 * Cloudflare Anycast IPs. Each call to [nextIps] rotates the returned order so
 * consecutive requests use different endpoints; IPs marked via [markUnavailable]
 * (connect/IO failures) are pushed to the back for the next 5 minutes and only
 * retried as a last resort when everything else is unavailable.
 */
object MissAvIpProvider {

    private const val TAG = "MissAvIpProvider"
    private const val FAILURE_EXPIRY_MS = 5 * 60 * 1000L

    /**
     * Built-in Cloudflare Anycast IPs for MissAV main domains and the image CDN.
     * Updated: 2025-01-15, sourced from Cloudflare Anycast ranges; refresh periodically.
     */
    private val BUILT_IN_MISSAV_IPS = listOf(
        "104.20.18.168",
        "104.20.19.168",
        "172.64.229.154",
        "162.159.0.1"
    )

    private val BUILT_IN_FOURHOI_IPS = listOf(
        "104.18.32.163",
        "104.18.33.163",
        "172.64.229.154"
    )

    private const val FOURHOI_HOST = "fourhoi.com"

    @Volatile
    private var customIps: List<String> = emptyList()

    private val unavailableIps = HashMap<String, Long>()
    private val cursors = HashMap<String, Int>()

    /** Replaces the user-defined IP list (applied to all direct-connect domains). */
    fun setCustomIps(ips: List<String>) {
        customIps = ips.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getCustomIps(): List<String> = customIps.toList()

    /**
     * Returns the candidate IPs for [hostname] in rotated order, or an empty list
     * when the host is not a direct-connect domain (caller should fall back to
     * normal DNS).
     */
    @Synchronized
    fun nextIps(hostname: String): List<String> {
        val pool = candidateIps(hostname)
        if (pool.isEmpty()) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        unavailableIps.entries.removeIf { now - it.value > FAILURE_EXPIRY_MS }

        val available = pool.filter { it !in unavailableIps }
        val fallback = pool.filter { it in unavailableIps }
        // All IPs unavailable: still return the full list so a retry gets a chance.
        val healthy = available.ifEmpty { fallback }

        val cursor = cursors.getOrDefault(hostname, 0) % healthy.size
        cursors[hostname] = cursor + 1
        val rotated = healthy.drop(cursor) + healthy.take(cursor)
        Log.d(TAG, "IPs for $hostname (rotated from #$cursor): $rotated")
        return rotated
    }

    /** Marks an IP as unavailable for the next 5 minutes. */
    @Synchronized
    fun markUnavailable(ip: String) {
        unavailableIps[ip] = System.currentTimeMillis()
        Log.w(TAG, "IP marked unavailable for ${FAILURE_EXPIRY_MS / 1000}s: $ip")
    }

    /** Clears all failure marks (e.g. when the user changes settings). */
    @Synchronized
    fun resetFailures() {
        unavailableIps.clear()
    }

    private fun candidateIps(hostname: String): List<String> {
        val builtIn = when {
            hostname.equals(FOURHOI_HOST, ignoreCase = true) -> BUILT_IN_FOURHOI_IPS
            MissAvDomainManager.isKnownMissAvDomain(hostname) -> BUILT_IN_MISSAV_IPS
            else -> emptyList()
        }
        if (builtIn.isEmpty() && customIps.isEmpty()) {
            return emptyList()
        }
        // Custom IPs first, deduped while preserving order.
        return LinkedHashSet(customIps + builtIn).toList()
    }
}
