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
import android.widget.FrameLayout
import com.mbta.app.model.ConnectionKind
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.ceil

class HeadlessWebBridge(
    private val context: Context,
    private val onFileChooserRequested: (Intent, ValueCallback<Array<Uri>>) -> Unit
) {
    private val pool = mutableMapOf<String, WebView>()
    private val loadedUrls = mutableMapOf<String, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PendingRequest(
        val inputTokens: Long,
        val onChunk: (String) -> Unit,
        val onDone: (String, Long, Long) -> Unit,
        val onError: (String) -> Unit
    )

    private val pending = mutableMapOf<String, PendingRequest>()

    private inner class JsBridge {
        @JavascriptInterface
        fun onChunk(requestId: String, text: String) {
            mainHandler.post {
                pending[requestId]?.onChunk?.invoke(text)
            }
        }

        @JavascriptInterface
        fun onDone(requestId: String, finalText: String) {
            mainHandler.post {
                val req = pending.remove(requestId) ?: return@post
                val outTokens = ceil(finalText.length / 3.0).toLong().coerceAtLeast(1L)
                req.onDone(finalText, req.inputTokens, outTokens)
            }
        }

        @JavascriptInterface
        fun onError(requestId: String, code: String, message: String) {
            mainHandler.post {
                val req = pending.remove(requestId) ?: return@post
                req.onError(message)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(key: String, url: String): WebView {
        pool[key]?.let { return it }

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
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = MOBILE_UA
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            addJavascriptInterface(JsBridge(), "MbtABridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    loadedUrls[key] = true
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https") return false
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

        pool[key] = webView
        return webView
    }

    fun attachToHiddenHost(host: FrameLayout, key: String, url: String) {
        val wv = getOrCreateWebView(key, url)
        if (wv.parent !== host) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            host.addView(wv)
        }
    }

    fun sendPrompt(
        key: String,
        kind: ConnectionKind,
        url: String,
        prompt: String,
        onChunk: (String) -> Unit,
        onDone: (String, Long, Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val wv = getOrCreateWebView(key, url)
        val reqId = UUID.randomUUID().toString()
        val estInput = ceil(prompt.length / 3.0).toLong().coerceAtLeast(1L)
        pending[reqId] = PendingRequest(estInput, onChunk, onDone, onError)

        val delayMs = if (loadedUrls[key] == true) 50L else 3500L
        mainHandler.postDelayed({
            val js = buildAutomationJs(reqId, kind.name, prompt)
            wv.evaluateJavascript(js, null)
        }, delayMs)
    }

    suspend fun sendPromptSuspend(
        key: String,
        kind: ConnectionKind,
        url: String,
        prompt: String
    ): Pair<Triple<String, Long, Long>, String?> = suspendCancellableCoroutine { cont ->
        sendPrompt(
            key = key,
            kind = kind,
            url = url,
            prompt = prompt,
            onChunk = {},
            onDone = { finalText, inTok, outTok ->
                if (cont.isActive) cont.resume(Triple(finalText, inTok, outTok) to null)
            },
            onError = { err ->
                if (cont.isActive) cont.resume(Triple("", 0L, 0L) to err)
            }
        )
    }

    fun reload(key: String) {
        pool[key]?.reload()
    }

    fun goBack(key: String) {
        pool[key]?.let { if (it.canGoBack()) it.goBack() }
    }

    fun checkLoginStatus(
        key: String,
        kind: ConnectionKind,
        url: String,
        onResult: (Boolean) -> Unit
    ) {
        val wv = pool[key]
        if (wv != null && loadedUrls[key] == true) {
            val js = """
                (function() {
                    var href = (location.href || '').toLowerCase();
                    if (/login|signin|auth|accounts\.google/.test(href)) return 'false';
                    var el = document.querySelector('#prompt-textarea, div.ProseMirror[contenteditable="true"], rich-textarea div[contenteditable="true"], div[contenteditable="true"], textarea');
                    return el ? 'true' : 'false';
                })();
            """.trimIndent()
            wv.evaluateJavascript(js) { res ->
                val ok = res?.contains("true") == true
                onResult(ok)
            }
            return
        }

        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        val hasAuthCookie = when (kind) {
            ConnectionKind.CLAUDE_WEB -> cookies.contains("sessionKey") || cookies.contains("__cf_bm")
            ConnectionKind.CHATGPT_WEB -> cookies.contains("__Secure-next-auth.session-token") || cookies.contains("oai-did")
            ConnectionKind.GEMINI_WEB -> cookies.contains("SID=") || cookies.contains("__Secure-1PSID")
            ConnectionKind.REMOTE_WEB -> cookies.isNotBlank()
            else -> false
        }
        onResult(hasAuthCookie)
    }

    fun destroyAll() {
        pending.clear()
        pool.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        pool.clear()
    }

    private fun buildAutomationJs(reqId: String, kindName: String, prompt: String): String {
        val quotedPrompt = JSONObject.quote(prompt)
        val quotedReqId = JSONObject.quote(reqId)
        val quotedKind = JSONObject.quote(kindName)

        return """
            (function() {
                var reqId = $quotedReqId;
                var kind = $quotedKind;
                var promptText = $quotedPrompt;

                function findFirst(selectors) {
                    for (var i = 0; i < selectors.length; i++) {
                        try {
                            var el = document.querySelector(selectors[i]);
                            if (el && el.offsetParent !== null) return el;
                        } catch(e) {}
                    }
                    return null;
                }

                var inputSelectors = [];
                var msgSelectors = [];
                if (kind === 'CHATGPT_WEB') {
                    inputSelectors = ['#prompt-textarea', 'div[contenteditable="true"][id="prompt-textarea"]', 'textarea'];
                    msgSelectors = ['[data-message-author-role="assistant"] .markdown', '[data-message-author-role="assistant"]'];
                } else if (kind === 'CLAUDE_WEB') {
                    inputSelectors = ['div.ProseMirror[contenteditable="true"]', 'fieldset div[contenteditable="true"]', 'div[contenteditable="true"]', 'textarea'];
                    msgSelectors = ['.font-claude-response', '.font-claude-message', '[data-testid="assistant-message"]', '[data-is-streaming]', '.prose'];
                } else if (kind === 'GEMINI_WEB') {
                    inputSelectors = ['rich-textarea div[contenteditable="true"]', 'div.ql-editor[contenteditable="true"]', 'div[contenteditable="true"]', 'textarea'];
                    msgSelectors = ['model-response .markdown', 'model-response message-content', 'message-content', '.model-response-text'];
                } else {
                    inputSelectors = ['textarea', 'div[contenteditable="true"]', 'input[type="text"]'];
                    msgSelectors = ['[data-message-author-role="assistant"]', '.font-claude-response', '.prose', 'article', 'pre'];
                }

                var inputEl = findFirst(inputSelectors);
                if (!inputEl) {
                    if (window.MbtABridge) {
                        var loc = (location.hostname || '') + (location.pathname || '');
                        window.MbtABridge.onError(reqId, 'NEED_LOGIN', '입력창을 찾을 수 없습니다 (' + loc + '). 상단 로그인 버튼으로 해당 계정에 먼저 로그인해주세요.');
                    }
                    return;
                }

                function getLatestMsgText() {
                    for (var i = 0; i < msgSelectors.length; i++) {
                        try {
                            var nodes = document.querySelectorAll(msgSelectors[i]);
                            if (nodes && nodes.length > 0) {
                                return { count: nodes.length, text: (nodes[nodes.length - 1].innerText || '').trim() };
                            }
                        } catch(e) {}
                    }
                    return { count: 0, text: '' };
                }

                var before = getLatestMsgText();
                var beforeBodyLen = (document.body && document.body.innerText) ? document.body.innerText.length : 0;

                inputEl.focus();
                if (inputEl.tagName === 'TEXTAREA' || inputEl.tagName === 'INPUT') {
                    var proto = inputEl.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                    setter.call(inputEl, promptText);
                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                } else {
                    try {
                        document.execCommand('selectAll', false, null);
                        document.execCommand('insertText', false, promptText);
                    } catch(e) {
                        inputEl.textContent = promptText;
                    }
                    inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                }

                setTimeout(function() {
                    var sendBtn = findFirst([
                        'button[data-testid="send-button"]',
                        'button[aria-label*="Send"]',
                        'button[aria-label*="전송"]',
                        'button[aria-label*="보내기"]',
                        'button.send-button',
                        'button[type="submit"]'
                    ]);
                    if (sendBtn && !sendBtn.disabled) {
                        sendBtn.click();
                        setTimeout(function() {
                            var rem = (inputEl.value !== undefined ? inputEl.value : inputEl.innerText) || '';
                            if (rem.trim().length > 0) {
                                inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                            }
                        }, 350);
                    } else {
                        inputEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                    }

                    var lastText = '';
                    var stableTicks = 0;
                    var totalTicks = 0;
                    var timer = setInterval(function() {
                        totalTicks++;
                        if (totalTicks > 180) {
                            clearInterval(timer);
                            if (lastText) {
                                window.MbtABridge.onDone(reqId, lastText);
                            } else {
                                window.MbtABridge.onError(reqId, 'TIMEOUT', '응답 시간이 초과되었습니다.');
                            }
                            return;
                        }

                        var cur = getLatestMsgText();
                        var candidate = '';
                        if (cur.count > before.count || (cur.text && cur.text !== before.text)) {
                            candidate = cur.text;
                        } else if (cur.count === 0 && document.body) {
                            var bodyText = document.body.innerText || '';
                            if (bodyText.length > beforeBodyLen + promptText.length + 5) {
                                candidate = bodyText.slice(beforeBodyLen).trim();
                            }
                        }

                        if (candidate && candidate !== lastText) {
                            lastText = candidate;
                            stableTicks = 0;
                            if (window.MbtABridge) window.MbtABridge.onChunk(reqId, lastText);
                        } else if (lastText) {
                            var streaming = !!document.querySelector('button[aria-label*="Stop"], button[aria-label*="중지"], button[data-testid="stop-button"], [data-is-streaming="true"]');
                            if (!streaming) {
                                stableTicks++;
                                if (stableTicks >= 5) {
                                    clearInterval(timer);
                                    if (window.MbtABridge) window.MbtABridge.onDone(reqId, lastText);
                                }
                            }
                        }
                    }, 500);
                }, 300);
            })();
        """.trimIndent()
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
