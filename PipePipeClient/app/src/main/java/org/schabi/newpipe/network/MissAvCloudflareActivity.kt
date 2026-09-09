package org.schabi.newpipe.network

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar

/**
 * Activity that displays a Cloudflare challenge page in a WebView and reports
 * back when the challenge is solved (i.e. `cf_clearance` cookie is present).
 *
 * The caller ([MissAvCloudflareInterceptor]) waits on a [CountDownLatch]
 * released via [onFinished].
 */
class MissAvCloudflareActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            visibility = View.GONE
        }
        progressBar = ProgressBar(this)
        setContentView(progressBar)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkChallengeSolved(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress >= 90) {
                    view?.postDelayed({
                        checkChallengeSolved(view, view?.url)
                    }, 1000)
                }
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        val url = intent.getStringExtra(EXTRA_URL)
        if (url != null) {
            webView.loadUrl(url)
        } else {
            Log.w(TAG, "No URL provided to CloudflareActivity")
            finish()
        }
    }

    private fun checkChallengeSolved(view: WebView?, url: String?) {
        if (url == null) return
        view?.evaluateJavascript("document.head.innerHTML") { html ->
            if (!html.contains("challenge-form")
                    && !html.contains("challenge-success-text")) {
                val cookies = CookieManager.getInstance().getCookie(url)
                if (cookies != null && cookies.contains("cf_clearance")) {
                    Log.d(TAG, "Cloudflare challenge solved, cf_clearance obtained")
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(KEY_CF_COOKIES, cookies)
                            .apply()
                    onFinished?.invoke()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val PREFS_NAME = "missav_cloudflare"
        const val KEY_CF_COOKIES = "cf_cookies"

        var onFinished: (() -> Unit)? = null
        const val TAG = "MissAvCFActivity"
    }
}
