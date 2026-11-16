package com.nuvio.tv.music

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nuvio.tv.music.spotifyweb.SpotifyWebClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A real Spotify sign-in page on the TV, so the same `sp_dc` session the desktop app uses can be
 * captured without typing a password into Nuvio. The page itself cannot read the HttpOnly cookie;
 * the app's CookieManager can, and the cookie is validated before it is saved.
 */
class SpotifyLoginActivity : Activity() {
    companion object {
        private const val LOGIN_URL =
            "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val POLL_MS = 1_500L
        private const val TIMEOUT_MS = 5 * 60 * 1000L
    }

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastTriedCookie = ""
    private var validating = false
    private var finished = false

    private val poll = object : Runnable {
        override fun run() {
            if (finished) return
            checkCookie()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val timeout = Runnable {
        if (finished) return@Runnable
        status.text = "Spotify sign-in timed out. Press Cancel and try again."
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MusicSpotifyWebSession.init(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = DESKTOP_USER_AGENT
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "Waiting for Spotify sign-in…"
                    checkCookie()
                }
            }
            webChromeClient = WebChromeClient()
        }
        root.addView(webView)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.argb(235, 18, 20, 26))
            setPadding(28, 18, 28, 18)
        }
        val title = TextView(this).apply {
            text = "Sign in to Spotify"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            isFocusable = true
            setOnClickListener { cancelAndFinish() }
        }
        top.addView(title)
        top.addView(cancel)
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        status = TextView(this).apply {
            text = "Loading Spotify…"
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.argb(220, 18, 20, 26))
            setPadding(28, 12, 28, 12)
        }
        root.addView(status, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        setContentView(root)
        webView.loadUrl(LOGIN_URL)
        handler.postDelayed(poll, POLL_MS)
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun checkCookie() {
        if (validating || finished) return
        val header = CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
        val cookie = header.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
            ?: return
        if (cookie == lastTriedCookie) return
        lastTriedCookie = cookie
        validating = true
        status.text = "Checking the Spotify session…"
        scope.launch {
            val client = runCatching {
                withContext(Dispatchers.IO) {
                    SpotifyWebClient.connect(cookie, requireSignedIn = true).getOrNull()
                }
            }.getOrNull()
            if (client?.isSignedIn == true) {
                val account = runCatching {
                    withContext(Dispatchers.IO) { client.account().getOrNull() }
                }.getOrNull()
                MusicSpotifyWebSession.save(
                    this@SpotifyLoginActivity,
                    cookie,
                    account?.name
                )
                finished = true
                handler.removeCallbacksAndMessages(null)
                setResult(RESULT_OK, Intent().putExtra("spotify_account", account?.name))
                finish()
            } else {
                status.text = "Waiting for Spotify sign-in…"
                validating = false
            }
        }
    }

    private fun cancelAndFinish() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        setResult(RESULT_CANCELED)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else cancelAndFinish()
    }

    override fun onDestroy() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
        super.onDestroy()
    }
}
