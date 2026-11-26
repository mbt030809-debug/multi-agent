package com.mbta.app.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class WebViewManager(
    private val context: Context,
    private val onFileChooserRequested: (Intent, ValueCallback<Array<Uri>>) -> Unit,
    private val onTokenDelta: (connectionId: String, inputDelta: Long, outputDelta: Long) -> Unit = { _, _, _ -> }
) {
    private val pool = mutableMapOf<String, WebView>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private inner class TokenJsBridge(private val connectionId: String) {
        @JavascriptInterface
        fun reportDelta(inputTokens: Long, outputTokens: Long) {
            if (inputTokens > 0 || outputTokens > 0) {
                mainHandler.post {
                    onTokenDelta(connectionId, inputTokens.coerceAtLeast(0L), outputTokens.coerceAtLeast(0L))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(
        sessionKey: String,
        connectionId: String,
        url: String,
        desktopMode: Boolean,
        trackWebTokens: Boolean
    ): WebView {
        pool[sessionKey]?.let { existing ->
            return existing
        }

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#11111B"))

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = if (desktopMode) DESKTOP_UA else MOBILE_UA
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            if (trackWebTokens) {
                addJavascriptInterface(TokenJsBridge(connectionId), "MbtATokenBridge")
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    view?.evaluateJavascript(DARK_THEME_CSS_JS, null)
                    if (trackWebTokens) {
                        view?.evaluateJavascript(TOKEN_ESTIMATOR_JS, null)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https") {
                        return false
                    }
                    return try {
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        true
                    } catch (_: Exception) {
                        true
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    onFileChooserRequested(intent, filePathCallback)
                    return true
                }
            }

            loadUrl(url)
        }

        pool[sessionKey] = webView
        return webView
    }

    fun reload(sessionKey: String) {
        pool[sessionKey]?.reload()
    }

    fun canGoBack(sessionKey: String): Boolean = pool[sessionKey]?.canGoBack() == true

    fun goBack(sessionKey: String) {
        pool[sessionKey]?.let {
            if (it.canGoBack()) it.goBack()
        }
    }

    fun removeWebView(sessionKey: String) {
        pool.remove(sessionKey)?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    fun destroyAll() {
        pool.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        pool.clear()
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val DARK_THEME_CSS_JS = """
            (function() {
                try {
                    document.documentElement.style.colorScheme = 'dark';
                } catch(e) {}
            })();
        """
        private const val TOKEN_ESTIMATOR_JS = """
            (function() {
                if (window.__mbtaTokenInit) return;
                window.__mbtaTokenInit = true;
                var lastLen = 0;
                var ready = false;
                setTimeout(function() {
                    lastLen = (document.body && document.body.innerText) ? document.body.innerText.length : 0;
                    ready = true;
                }, 4000);
                document.addEventListener('keydown', function(e) {
                    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
                        var el = document.activeElement;
                        if (el && (el.tagName === 'TEXTAREA' || el.isContentEditable)) {
                            var txt = el.value !== undefined ? el.value : (el.innerText || '');
                            var est = Math.ceil((txt ? txt.trim().length : 0) / 3);
                            if (est > 0 && window.MbtATokenBridge) {
                                window.MbtATokenBridge.reportDelta(est, 0);
                            }
                        }
                    }
                }, true);
                setInterval(function() {
                    if (!ready || !document.body) return;
                    var curLen = (document.body.innerText || '').length;
                    var delta = curLen - lastLen;
                    if (delta > 20 && delta < 30000) {
                        var estOut = Math.ceil(delta / 3);
                        if (estOut > 0 && window.MbtATokenBridge) {
                            window.MbtATokenBridge.reportDelta(0, estOut);
                        }
                    }
                    lastLen = curLen;
                }, 3000);
            })();
        """
    }
}
