package com.yegorkapomidorka.headswap

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

object CockpitRenderer {
    @Volatile var enabled = true
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    @Volatile private var frame: Bitmap? = null
    private var w = 1280
    private var h = 720
    private var downTime = 0L
    private var initialized = false

    @SuppressLint("SetJavaScriptEnabled")
    fun init(ctx: Context, width: Int, height: Int) {
        if (initialized && w == width && h == height) return
        w = width; h = height; initialized = true
        main.post {
            val wv = WebView(ctx.applicationContext)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun switchToAA() {
                    enabled = false
                }
            }, "CarBridge")
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, w, h)
            wv.loadUrl("file:///android_asset/media_hub.html")
            webView = wv
            scheduleCapture()
                        ObdReader.start { soc, range ->
                main.post {
                    webView?.evaluateJavascript("if(window.__setBattery) window.__setBattery($soc, $range);", null)
                }
                        }
        }
    }

    private fun scheduleCapture() {
        main.postDelayed({
            val wv = webView ?: return@postDelayed
            if (enabled) {
                try {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    wv.draw(c)
                    frame = bmp
                } catch (_: Throwable) {}
            }
            scheduleCapture()
        }, 120)
    }

    fun draw(canvas: Canvas, width: Int, height: Int): Boolean {
        if (!enabled) return false
        val bmp = frame ?: return false
        canvas.drawBitmap(bmp, 0f, 0f, null)
        return true
    }

    fun dispatchTouch(action: Int, x: Int, y: Int) {
        if (!enabled) return
        val fx = x.toFloat()
        val fy = y.toFloat()
        main.post {
            val wv = webView ?: return@post
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val ev = MotionEvent.obtain(downTime, now, action, fx, fy, 0)
            wv.dispatchTouchEvent(ev)
            ev.recycle()
            if (action == MotionEvent.ACTION_UP) {
                wv.evaluateJavascript("if(window.__carTap) window.__carTap($x, $y);", null)
            }
        }
    }
}
