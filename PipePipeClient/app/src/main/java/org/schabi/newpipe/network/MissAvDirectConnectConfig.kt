package org.schabi.newpipe.network

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.services.missav.MissAvDomainManager

/**
 * Pushes user settings (custom IPs / custom backup domains) into the
 * direct-connect providers. Called at app start and whenever the
 * corresponding preference changes, so edits apply immediately without
 * restarting the app.
 */
object MissAvDirectConnectConfig {

    private const val TAG = "MissAvDirectConnect"

    fun sync(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val customIps = parseList(
            prefs.getString(context.getString(R.string.missav_custom_ips_key), null)
        )
        val customDomains = parseList(
            prefs.getString(context.getString(R.string.missav_custom_domains_key), null)
        )

        MissAvIpProvider.setCustomIps(customIps)
        MissAvDomainManager.configure(customDomains)
        MissAvIpProvider.resetFailures()
        MissAvDomainManager.resetFailures()

        Log.d(TAG, "Synced direct-connect config: ${customIps.size} custom IPs, "
                + "${customDomains.size} custom domains")
    }

    private fun parseList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
