package com.mbta.app.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mbta.app.model.AiPanel

class WebViewManager(
    private val context: Context,
    private val onFileChooserRequested: (Intent, ValueCallback<Array<Uri>>) -> Unit
) {
    private val pool = mutableMapOf<String, WebView>()

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(panel: AiPanel): WebView {
        pool[panel.id]?.let { existing ->
            return existing
        }

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

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
                userAgentString = if (panel.desktopMode) DESKTOP_UA else MOBILE_UA
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
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

            loadUrl(panel.url)
        }

        pool[panel.id] = webView
        return webView
    }

    fun reload(id: String) {
        pool[id]?.reload()
    }

    fun canGoBack(id: String): Boolean = pool[id]?.canGoBack() == true

    fun goBack(id: String) {
        pool[id]?.let {
            if (it.canGoBack()) it.goBack()
        }
    }

    fun loadUrl(id: String, url: String) {
        pool[id]?.loadUrl(url)
    }

    fun setDesktopMode(id: String, desktop: Boolean) {
        pool[id]?.let { wv ->
            wv.settings.userAgentString = if (desktop) DESKTOP_UA else MOBILE_UA
            wv.reload()
        }
    }

    fun removeWebView(id: String) {
        pool.remove(id)?.let { wv ->
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
    }
}
