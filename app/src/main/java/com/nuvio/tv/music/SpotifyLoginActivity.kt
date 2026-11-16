package com.nuvio.tv.music

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
 *
 * TV remotes cannot click a web page directly, so a virtual cursor sits over the page: D-pad
 * moves it, OK clicks the element under it. While the on-screen keyboard is open the cursor
 * steps aside and the remote controls the keyboard.
 */
class SpotifyLoginActivity : Activity() {
    companion object {
        private const val LOGIN_URL =
            "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; AFTALMO) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
        private const val POLL_MS = 1_500L
        private const val TIMEOUT_MS = 5 * 60 * 1000L
    }

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var cursor: CursorView
    private lateinit var cursorButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastTriedCookie = ""
    private var validating = false
    private var finished = false
    private var cursorOn = true
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorStep = 24f

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
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        MusicSpotifyWebSession.init(this)
        cursorStep = 26f * resources.displayMetrics.density

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
            settings.userAgentString = MOBILE_USER_AGENT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "D-pad moves the cursor · OK clicks · Back hides it"
                    checkCookie()
                }
            }
            webChromeClient = WebChromeClient()
        }
        root.addView(webView)

        cursor = CursorView(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(cursor, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

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
        cursorButton = Button(this).apply {
            text = "Cursor: on"
            isFocusable = false
            setOnClickListener { setCursorOn(true) }
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            isFocusable = true
            setOnClickListener { cancelAndFinish() }
        }
        top.addView(title)
        top.addView(cursorButton)
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
        // Start from a clean guest session; a stale sp_dc from an earlier attempt can make
        // Spotify's login form fail after the email step.
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
        webView.clearCache(true)
        CookieManager.getInstance().removeAllCookies {
            webView.loadUrl(LOGIN_URL)
        }
        handler.postDelayed(poll, POLL_MS)
        handler.postDelayed(timeout, TIMEOUT_MS)

        webView.post {
            cursorX = webView.width / 2f
            cursorY = webView.height / 2f
            cursor.moveTo(cursorX, cursorY)
        }

        // While the on-screen keyboard is open, let the remote control the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.isVisible(WindowInsetsCompat.Type.ime())
            setCursorOn(!ime)
            insets
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cursorOn) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveCursor(0f, -cursorStep)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveCursor(0f, cursorStep)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    moveCursor(-cursorStep, 0f)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    moveCursor(cursorStep, 0f)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    clickAtCursor()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    setCursorOn(false)
                    return true
                }
                else -> return super.dispatchKeyEvent(event)
            }
        }
        return true
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, webView.width.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, webView.height.toFloat())
        cursor.moveTo(cursorX, cursorY)
    }

    private fun clickAtCursor() {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun setCursorOn(on: Boolean) {
        cursorOn = on
        cursor.visibility = if (on) View.VISIBLE else View.GONE
        cursorButton.text = if (on) "Cursor: on" else "Cursor: off"
        cursorButton.isFocusable = !on
        if (on) {
            status.text = "D-pad moves the cursor · OK clicks · Back hides it"
        } else {
            status.text = "Cursor off. Press Back or Cancel."
        }
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
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SpotifyWebClient.connect(cookie, requireSignedIn = true).getOrThrow()
                }
            }
            val client = result.getOrNull()
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
                val error = result.exceptionOrNull()?.message
                status.text = if (error.isNullOrBlank()) {
                    "Waiting for Spotify sign-in…"
                } else {
                    "Spotify check failed: $error"
                }
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
        if (cursorOn) {
            setCursorOn(false)
            return
        }
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

    private class CursorView(context: android.content.Context) : View(context) {
        private var x = 0f
        private var y = 0f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * context.resources.displayMetrics.density
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        fun moveTo(newX: Float, newY: Float) {
            x = newX
            y = newY
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = 12f * resources.displayMetrics.density
            canvas.drawCircle(x, y, r, fill)
            canvas.drawCircle(x, y, r, ring)
            canvas.drawCircle(x, y, 2.5f * resources.displayMetrics.density, dot)
        }
    }
}
