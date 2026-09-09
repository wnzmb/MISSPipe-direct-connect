package org.schabi.newpipe.network

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MissAvCloudflareInterceptor(private val context: Context) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)

        if (response.code == 403
                && response.header("cf-mitigated") == "challenge") {
            response.close()

            val latch = CountDownLatch(1)
            MissAvCloudflareActivity.onFinished = { latch.countDown() }

            val intent = Intent(context, MissAvCloudflareActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MissAvCloudflareActivity.EXTRA_URL, request.url.toString())
            }
            context.startActivity(intent)

            val finished = latch.await(VERIFICATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.w(TAG, "Cloudflare verification timed out after "
                        + VERIFICATION_TIMEOUT_MS + "ms")
            }

            val cfCookies = loadCfCookies()
            val newRequest = if (cfCookies != null && cfCookies.isNotBlank()) {
                val existingCookie = request.header("Cookie")
                val combinedCookie = if (existingCookie != null && existingCookie.isNotBlank()) {
                    "$existingCookie; $cfCookies"
                } else {
                    cfCookies
                }
                request.newBuilder()
                    .header("Cookie", combinedCookie)
                    .build()
            } else {
                request
            }
            response = chain.proceed(newRequest)
        }

        return response
    }

    private fun loadCfCookies(): String? {
        val prefs = context.getSharedPreferences(
            MissAvCloudflareActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(MissAvCloudflareActivity.KEY_CF_COOKIES, null)
    }

    companion object {
        private const val TAG = "MissAvCFInterceptor"
        private const val VERIFICATION_TIMEOUT_MS = 30_000L
    }
}
