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

data class ExternalSessionItem(
    val id: String,
    val title: String,
    val url: String = "",
    val updatedAt: Long = 0L,
    val updatedLabel: String = ""
)

class HeadlessWebBridge(
    private val context: Context,
    private val onFileChooserRequested: (Intent, ValueCallback<Array<Uri>>) -> Unit
) {
    private val pool = mutableMapOf<String, WebView>()
    private val loadedUrls = mutableMapOf<String, Boolean>()
    private val targetUrls = mutableMapOf<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logs = ArrayDeque<String>()

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(logs) {
            if (logs.size >= 60) logs.removeFirst()
            logs.addLast("[$ts] $msg")
        }
    }

    fun getLogsText(): String {
        val poolInfo = pool.entries.joinToString("\n") { (k, wv) -> "  pool[$k] url=${wv.url} loaded=${loadedUrls[k]}" }
        val logLines = synchronized(logs) { logs.joinToString("\n") }
        return "=== MbtA WebBridge Diagnostics ===\nActive WebViews:\n$poolInfo\n\nRecent Events:\n$logLines"
    }

    fun dumpDomDiagnostics(key: String, onResult: (String) -> Unit) {
        val wv = pool[key]
        if (wv == null) {
            onResult("WebView($key) not in pool")
            return
        }
        val js = """
            (function(){
                try {
                    var iframes = Array.from(document.querySelectorAll('iframe')).map(function(f){
                        var r = f.getBoundingClientRect();
                        var sameOrigin = false;
                        try { sameOrigin = !!f.contentDocument; } catch(e) {}
                        return (f.src || '(no src)').slice(0, 80) + ' [' + Math.round(r.width) + 'x' + Math.round(r.height) + ', sameOrigin=' + sameOrigin + ']';
                    });
                    var edits = document.querySelectorAll('textarea, input, [contenteditable]').length;
                    var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').trim().slice(0, 250);
                    var tcount = {};
                    document.querySelectorAll('[data-testid]').forEach(function(e) { var k = e.getAttribute('data-testid'); tcount[k] = (tcount[k] || 0) + 1; });
                    var tids = Object.keys(tcount).slice(0, 40).map(function(k) { return k + 'x' + tcount[k]; }).join(', ');
                    var roles = document.querySelectorAll('[data-message-author-role]').length;
                    var arts = document.querySelectorAll('article').length;
                    return 'href=' + location.href + '\nreadyState=' + document.readyState + '\niframes(' + iframes.length + ')=' + iframes.join(' | ') + '\neditable=' + edits + '\ntestids=' + tids + '\nauthorRole=' + roles + ' article=' + arts + '\nbodyText=' + txt;
                } catch(e) { return 'dump error: ' + e; }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { res ->
            onResult(res ?: "null")
        }
    }

    private data class PendingRequest(
        val inputTokens: Long,
        val onChunk: (String) -> Unit,
        val onDone: (String, Long, Long) -> Unit,
        val onError: (String) -> Unit
    )

    private val pending = mutableMapOf<String, PendingRequest>()
    private val activeReqByKey = mutableMapOf<String, String>()
    private val pendingAutoUploadUris = mutableMapOf<String, Uri>()

    fun cancelActiveRequest(key: String) {
        val reqId = activeReqByKey.remove(key)
        if (reqId != null) pending.remove(reqId)
        val wv = pool[key] ?: return
        val js = """
            (function() {
                try {
                    if (window.__mbtaTimers) {
                        Object.keys(window.__mbtaTimers).forEach(function(k) {
                            var t = window.__mbtaTimers[k];
                            if (t && t.wait) clearInterval(t.wait);
                            if (t && t.poll) clearInterval(t.poll);
                        });
                        window.__mbtaTimers = {};
                    }
                    var stopBtn = document.querySelector('button[aria-label*="Stop" i], button[aria-label*="중지"], button[data-testid="stop-button"]');
                    if (stopBtn) stopBtn.click();
                } catch(e) {}
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

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
                log("onDone req=$requestId len=${finalText.length}")
                val req = pending.remove(requestId) ?: return@post
                val outTokens = ceil(finalText.length / 3.0).toLong().coerceAtLeast(1L)
                req.onDone(finalText, req.inputTokens, outTokens)
            }
        }

        @JavascriptInterface
        fun onError(requestId: String, code: String, message: String) {
            mainHandler.post {
                log("onError req=$requestId code=$code msg=$message")
                val req = pending.remove(requestId) ?: return@post
                req.onError(message)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(key: String, url: String): WebView {
        pool[key]?.let { return it }
        targetUrls[key] = url
        log("createWebView key=$key url=$url")

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
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    log("onPageStarted key=$key url=$url")
                    view?.evaluateJavascript(
                        """
                        (function(){
                          if (window.__mbtaShadowHooked) return;
                          window.__mbtaShadowHooked = true;
                          var orig = Element.prototype.attachShadow;
                          if (orig) {
                            Element.prototype.attachShadow = function(init) {
                              var root = orig.call(this, init);
                              try { this.__mbtaShadowRoot = root; } catch(e) {}
                              return root;
                            };
                          }
                        })();
                        """.trimIndent(),
                        null
                    )
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    loadedUrls[key] = true
                    log("onPageFinished key=$key url=$loadedUrl")
                    CookieManager.getInstance().flush()
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
                    val autoUri = pendingAutoUploadUris.remove(key)
                    if (autoUri != null) {
                        log("onShowFileChooser auto-fulfilled key=$key uri=$autoUri")
                        filePathCallback.onReceiveValue(arrayOf(autoUri))
                        return true
                    }
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

    fun navigateIfNeeded(key: String, desiredUrl: String): Boolean {
        val wv = pool[key] ?: return false
        if (desiredUrl.isBlank()) return false
        val cur = wv.url.orEmpty().trimEnd('/')
        val target = desiredUrl.trimEnd('/')
        if (cur == target) return false
        targetUrls[key] = desiredUrl
        loadedUrls[key] = false
        log("navigateIfNeeded key=$key to=$desiredUrl")
        wv.loadUrl(desiredUrl)
        return true
    }

    suspend fun attachFileToWebInputSuspend(
        key: String,
        url: String,
        filename: String,
        mimeType: String,
        base64Data: String,
        contentUri: Uri? = null
    ): Boolean {
        val wv = getOrCreateWebView(key, url)
        if (contentUri != null) {
            pendingAutoUploadUris[key] = contentUri
        }
        val qName = JSONObject.quote(filename)
        val qMime = JSONObject.quote(mimeType.ifBlank { "application/octet-stream" })
        val qB64 = JSONObject.quote(base64Data)
        val js = """
            (function() {
                try {
                    $DEEP_QUERY_JS
                    function buildDataTransfer() {
                        var bin = atob($qB64);
                        var arr = new Uint8Array(bin.length);
                        for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                        var file = new File([arr], $qName, { type: $qMime, lastModified: Date.now() });
                        var dt = new DataTransfer();
                        dt.items.add(file);
                        return dt;
                    }
                    function hasAttachmentPreview() {
                        var sels = 'img[src^="blob:"], img[src^="data:image/"], [data-test-id*="image-preview"], [data-test-id*="file-preview"], .file-preview, [aria-label*="Remove attachment" i], [aria-label*="첨부" i][aria-label*="삭제"], [aria-label*="제거"]';
                        var els = queryAllDeep(sels);
                        for (var i = 0; i < els.length; i++) {
                            var r = els[i].getBoundingClientRect();
                            if (r.width > 12 && r.height > 12) return true;
                        }
                        return false;
                    }
                    // Tier 1: Direct <input type="file"> injection
                    var inputs = queryAllDeep('input[type="file"]');
                    if (inputs && inputs.length > 0) {
                        var inp = inputs[inputs.length - 1];
                        var dt1 = buildDataTransfer();
                        inp.files = dt1.files;
                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                        inp.dispatchEvent(new Event('change', { bubbles: true }));
                        return 'input_ok';
                    }
                    // Tier 2: ClipboardEvent paste + DragEvent drop onto rich editor (Gemini / Claude / ChatGPT)
                    var editors = queryAllDeep('rich-textarea [contenteditable="true"], .ql-editor[contenteditable="true"], #prompt-textarea, div.ProseMirror[contenteditable="true"], [contenteditable="true"], textarea');
                    var target = null;
                    for (var j = 0; j < editors.length; j++) {
                        var er = editors[j].getBoundingClientRect();
                        if (er.width > 40 && er.height > 12) { target = editors[j]; break; }
                    }
                    if (target) {
                        target.focus();
                        try {
                            var dt2 = buildDataTransfer();
                            var pasteEvt = new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: dt2 });
                            target.dispatchEvent(pasteEvt);
                        } catch(pe) {}
                        try {
                            var dt3 = buildDataTransfer();
                            target.dispatchEvent(new DragEvent('dragenter', { bubbles: true, cancelable: true, dataTransfer: dt3 }));
                            target.dispatchEvent(new DragEvent('dragover', { bubbles: true, cancelable: true, dataTransfer: dt3 }));
                            target.dispatchEvent(new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt3 }));
                        } catch(de) {}
                        return 'paste_drop_sent';
                    }
                    return 'no_target';
                } catch(e) { return 'err:' + e; }
            })();
        """.trimIndent()

        val firstRes = suspendCancellableCoroutine<String> { cont ->
            mainHandler.post {
                wv.evaluateJavascript(js) { res ->
                    if (cont.isActive) cont.resume(res.orEmpty().trim('"'))
                }
            }
        }
        log("attachFile tier12 key=$key res=$firstRes")

        // Wait briefly and check if attachment preview appeared; if not, trigger Tier 3 (upload button + silent onShowFileChooser)
        kotlinx.coroutines.delay(700L)
        val previewAfterPaste = suspendCancellableCoroutine<Boolean> { cont ->
            val checkJs = """
                (function() {
                    try {
                        $DEEP_QUERY_JS
                        var els = queryAllDeep('img[src^="blob:"], img[src^="data:image/"], [data-test-id*="image"], [data-test-id*="file"], .file-preview, button[aria-label*="Remove" i], button[aria-label*="삭제"], button[aria-label*="제거"]');
                        for (var i = 0; i < els.length; i++) {
                            var r = els[i].getBoundingClientRect();
                            if (r.width > 12 && r.height > 12) return 'yes';
                        }
                        return 'no';
                    } catch(e) { return 'no'; }
                })();
            """.trimIndent()
            mainHandler.post {
                wv.evaluateJavascript(checkJs) { res ->
                    if (cont.isActive) cont.resume(res?.contains("yes") == true)
                }
            }
        }

        if (previewAfterPaste || firstRes == "input_ok") {
            pendingAutoUploadUris.remove(key)
            // Give Gemini/web app enough time to finish uploading the image before send
            kotlinx.coroutines.delay(1400L)
            return true
        }

        // Tier 3: Click Gemini/web upload button -> click "파일 업로드" / "Upload file" menu item -> triggers silent onShowFileChooser
        if (contentUri != null) {
            val triggerUploadJs = """
                (function() {
                    try {
                        $DEEP_QUERY_JS
                        var btns = queryAllDeep('button[aria-label*="Upload" i], button[aria-label*="업로드"], button[aria-label*="파일"], button[aria-label*="이미지"], button[aria-label*="추가"], button.upload-card-button, xap-deferred-trigger-button button');
                        for (var i = 0; i < btns.length; i++) {
                            var r = btns[i].getBoundingClientRect();
                            if (r.width > 16 && r.height > 16) {
                                btns[i].click();
                                setTimeout(function() {
                                    var items = queryAllDeep('button, [role="menuitem"], mat-list-item, li');
                                    for (var k = 0; k < items.length; k++) {
                                        var txt = (items[k].innerText || items[k].getAttribute('aria-label') || '').trim();
                                        if (/파일\s*업로드|이미지\s*업로드|사진|upload\s*file|upload\s*from|add\s*file|photos/i.test(txt)) {
                                            items[k].click();
                                            break;
                                        }
                                    }
                                }, 350);
                                return 'clicked_upload';
                            }
                        }
                        return 'no_upload_btn';
                    } catch(e) { return 'err:' + e; }
                })();
            """.trimIndent()
            val tier3Res = suspendCancellableCoroutine<String> { cont ->
                mainHandler.post {
                    wv.evaluateJavascript(triggerUploadJs) { res ->
                        if (cont.isActive) cont.resume(res.orEmpty().trim('"'))
                    }
                }
            }
            log("attachFile tier3 key=$key res=$tier3Res")
            if (tier3Res == "clicked_upload") {
                kotlinx.coroutines.delay(2200L)
                pendingAutoUploadUris.remove(key)
                return true
            }
        }
        pendingAutoUploadUris.remove(key)
        return firstRes == "paste_drop_sent"
    }

    fun sendPrompt(
        key: String,
        kind: ConnectionKind,
        url: String,
        prompt: String,
        conversationUrl: String = "",
        onChunk: (String) -> Unit,
        onDone: (String, Long, Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val wv = getOrCreateWebView(key, conversationUrl.ifBlank { url })
        val navigated = if (conversationUrl.isNotBlank()) navigateIfNeeded(key, conversationUrl) else false
        val reqId = UUID.randomUUID().toString()
        activeReqByKey[key] = reqId
        log("sendPrompt key=$key kind=${kind.name} navigated=$navigated loaded=${loadedUrls[key]} wvUrl=${wv.url}")
        val estInput = ceil(prompt.length / 3.0).toLong().coerceAtLeast(1L)
        pending[reqId] = PendingRequest(estInput, onChunk, onDone, onError)

        val delayMs = if (!navigated && loadedUrls[key] == true) 50L else 3500L
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

    fun flushCookies() {
        CookieManager.getInstance().flush()
    }

    fun switchWebModel(key: String, modelName: String) {
        val wv = pool[key] ?: return
        val quoted = JSONObject.quote(modelName.lowercase())
        val js = """
            (function() {
                $DEEP_QUERY_JS
                var target = $quoted;
                var btns = queryAllDeep('button, [role="button"], [aria-haspopup]');
                var picker = null;
                for (var i = 0; i < btns.length; i++) {
                    var t = (btns[i].innerText || btns[i].getAttribute('aria-label') || '').toLowerCase();
                    if (/gemini|claude|gpt|sonnet|opus|haiku|neon|hydrogen|model|모델/.test(t)) {
                        picker = btns[i];
                        break;
                    }
                }
                if (!picker) return;
                picker.click();
                setTimeout(function() {
                    var items = queryAllDeep('[role="menuitem"], [role="option"], li, button');
                    for (var j = 0; j < items.length; j++) {
                        var txt = (items[j].innerText || '').toLowerCase();
                        if (txt && (txt.indexOf(target) !== -1 || target.indexOf(txt.trim()) !== -1)) {
                            items[j].click();
                            return;
                        }
                    }
                }, 350);
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    /** claude.ai/code 모델 메뉴 하단 '노력(effort)' 항목을 눌러 적용 (낮음/중간/높음/엑스트라/최대) */
    fun switchWebEffort(key: String, effort: String) {
        val wv = pool[key] ?: return
        val cands = when (effort.lowercase()) {
            "low" -> listOf("낮음", "low")
            "medium" -> listOf("중간", "medium")
            "high" -> listOf("높음", "high")
            "extra", "xhigh" -> listOf("엑스트라", "extra", "xhigh")
            "max" -> listOf("최대", "max")
            else -> return
        }
        val arr = cands.joinToString(",") { JSONObject.quote(it) }
        val js = """
            (function() {
                $DEEP_QUERY_JS
                var cands = [$arr];
                function findPicker() {
                    var btns = queryAllDeep('button, [role="button"], [aria-haspopup]');
                    for (var i = 0; i < btns.length; i++) {
                        var t = (btns[i].innerText || btns[i].getAttribute('aria-label') || '').toLowerCase();
                        if (/sonnet|opus|haiku|fable|claude|모델|model/.test(t)) return btns[i];
                    }
                    return null;
                }
                function clickEffort() {
                    var items = queryAllDeep('[role="menuitem"], [role="option"], [role="menuitemradio"], li, button, div');
                    for (var j = 0; j < items.length; j++) {
                        var el = items[j];
                        if (el.children && el.children.length > 4) continue;
                        var txt = (el.innerText || '').trim().split('\n')[0].trim().toLowerCase();
                        if (!txt) continue;
                        for (var k = 0; k < cands.length; k++) {
                            if (txt === cands[k]) { el.click(); return true; }
                        }
                    }
                    return false;
                }
                if (clickEffort()) return;
                var picker = findPicker();
                if (!picker) return;
                picker.click();
                setTimeout(clickEffort, 400);
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    fun currentUrl(key: String): String? = pool[key]?.url

    /** 세션 삭제 시 해당 세션 전용 WebView 해제 */
    fun releaseWebView(key: String) {
        activeReqByKey.remove(key)?.let { pending.remove(it) }
        val wv = pool.remove(key) ?: return
        loadedUrls.remove(key)
        targetUrls.remove(key)
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
    }

    fun clickWebActionButton(key: String, label: String, onResult: (Boolean) -> Unit = {}) {
        val wv = pool[key]
        if (wv == null) {
            onResult(false)
            return
        }
        val norm = label.trim().lowercase()
        val candidates = when (norm) {
            "허용" -> listOf("허용", "allow", "allow once", "approve", "run")
            "허용 안 함", "거부" -> listOf("허용 안 함", "거부", "deny", "don't allow", "do not allow", "reject")
            "항상 허용" -> listOf("항상 허용", "always allow", "allow always")
            else -> listOf(norm)
        }
        val candJson = org.json.JSONArray(candidates).toString()
        val js = """
            (function() {
                try {
                    $DEEP_QUERY_JS
                    var cands = $candJson;
                    var btns = queryAllDeep('button, [role="button"]');
                    for (var pass = 0; pass < 2; pass++) {
                        for (var i = btns.length - 1; i >= 0; i--) {
                            var b = btns[i];
                            if (b.disabled || b.getAttribute('aria-disabled') === 'true') continue;
                            var r = b.getBoundingClientRect();
                            if (r.width <= 0 || r.height <= 0) continue;
                            var t = ((b.innerText || b.textContent || b.getAttribute('aria-label') || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
                            if (!t || t.length > 40) continue;
                            for (var j = 0; j < cands.length; j++) {
                                var c = cands[j];
                                if ((pass === 0 && t === c) || (pass === 1 && t.indexOf(c) !== -1 && c.length >= 2)) {
                                    b.scrollIntoView({block: 'center'});
                                    b.click();
                                    return '1';
                                }
                            }
                        }
                    }
                    return '0';
                } catch(e) { return '0'; }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { res ->
            val ok = res?.contains("1") == true
            log("clickWebActionButton($key, '$label') -> $ok")
            onResult(ok)
        }
    }

    fun fetchClaudeWebUsagePercent(key: String, onResult: (Int?, String) -> Unit) {
        val wv = pool[key]
        if (wv == null) {
            onResult(null, "")
            return
        }
        val js = """
            (function() {
                try {
                    fetch('/api/organizations', { credentials: 'include' })
                        .then(function(r) { return r.ok ? r.json() : null; })
                        .then(function(orgs) {
                            if (!orgs || !orgs.length) return null;
                            var orgId = orgs[0].uuid || orgs[0].id;
                            if (!orgId) return null;
                            return fetch('/api/organizations/' + orgId + '/usage', { credentials: 'include' })
                                .then(function(r2) { return r2.ok ? r2.json() : null; });
                        })
                        .then(function(data) {
                            if (!data) return;
                            var h5 = data.five_hour && typeof data.five_hour.utilization === 'number' ? Math.max(0, Math.min(100, Math.round(100 - data.five_hour.utilization))) : null;
                            var d7 = data.seven_day && typeof data.seven_day.utilization === 'number' ? Math.max(0, Math.min(100, Math.round(100 - data.seven_day.utilization))) : null;
                            if (h5 === null && d7 === null) return;
                            var primary = h5 !== null ? h5 : d7;
                            var parts = [];
                            if (h5 !== null) parts.push('5시간 ' + h5 + '%');
                            if (d7 !== null) parts.push('주간 ' + d7 + '%');
                            var payload = primary + '|' + parts.join(' · ');
                            window.MbtABridge && window.MbtABridge.onChunk('__usage_' + '$key', payload);
                        })
                        .catch(function(){});
                } catch(e) {}
            })();
        """.trimIndent()
        val usageReqId = "__usage_$key"
        pending[usageReqId] = PendingRequest(
            inputTokens = 0L,
            onChunk = { payload ->
                pending.remove(usageReqId)
                val parts = payload.split("|", limit = 2)
                val remPct = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 100)
                val lbl = parts.getOrNull(1).orEmpty().ifBlank { "Claude 계정" }
                onResult(remPct, lbl)
            },
            onDone = { _, _, _ -> },
            onError = { _ -> }
        )
        wv.evaluateJavascript(js, null)
    }

    fun fetchChatGptWebUsagePercent(key: String, onResult: (Int?, String) -> Unit) {
        val wv = pool[key]
        if (wv == null) {
            onResult(null, "")
            return
        }
        val js = """
            (function() {
                try {
                    fetch('/backend-api/wham/usage', { credentials: 'include' })
                        .then(function(r) { return r.ok ? r.json() : null; })
                        .then(function(data) {
                            if (data && data.rate_limit) {
                                var rl = data.rate_limit;
                                var p = rl.primary_window && typeof rl.primary_window.used_percent === 'number' ? Math.max(0, Math.min(100, Math.round(100 - rl.primary_window.used_percent))) : null;
                                var s = rl.secondary_window && typeof rl.secondary_window.used_percent === 'number' ? Math.max(0, Math.min(100, Math.round(100 - rl.secondary_window.used_percent))) : null;
                                if (p !== null || s !== null) {
                                    var primary = p !== null ? p : s;
                                    var parts = [];
                                    if (p !== null) parts.push('5시간 ' + p + '%');
                                    if (s !== null) parts.push('주간 ' + s + '%');
                                    window.MbtABridge && window.MbtABridge.onChunk('__usage_' + '$key', primary + '|' + parts.join(' · '));
                                    return;
                                }
                            }
                            return fetch('/backend-api/conversation_limit', { credentials: 'include' })
                                .then(function(r2) { return r2.ok ? r2.json() : null; })
                                .then(function(d2) {
                                    if (d2 && typeof d2.remaining === 'number' && typeof d2.limit === 'number' && d2.limit > 0) {
                                        var pct = Math.max(0, Math.min(100, Math.round((d2.remaining / d2.limit) * 100)));
                                        window.MbtABridge && window.MbtABridge.onChunk('__usage_' + '$key', pct + '|' + d2.remaining + '/' + d2.limit + ' 남음');
                                    }
                                });
                        })
                        .catch(function(){});
                } catch(e) {}
            })();
        """.trimIndent()
        val usageReqId = "__usage_$key"
        pending[usageReqId] = PendingRequest(
            inputTokens = 0L,
            onChunk = { payload ->
                pending.remove(usageReqId)
                val parts = payload.split("|", limit = 2)
                val remPct = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 100)
                val lbl = parts.getOrNull(1).orEmpty().ifBlank { "ChatGPT 계정" }
                onResult(remPct, lbl)
            },
            onDone = { _, _, _ -> },
            onError = { _ -> }
        )
        wv.evaluateJavascript(js, null)
    }

    fun fetchWebSessionMessages(
        key: String,
        kind: ConnectionKind,
        url: String,
        agentName: String,
        forceReload: Boolean = false,
        onResult: (List<com.mbta.app.model.ChatMessage>) -> Unit
    ) {
        val wv = getOrCreateWebView(key, url)
        val navigated = navigateIfNeeded(key, url)
        val reloaded = if (!navigated && forceReload) {
            loadedUrls[key] = false
            log("fetchWebSessionMessages reload key=$key url=$url")
            mainHandler.post { wv.reload() }
            true
        } else false
        val expectedPath = runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault("")
        val delayMs = if (navigated || reloaded) 1800L else 300L
        val selector = when (kind) {
            ConnectionKind.CHATGPT_WEB -> "[data-message-author-role=\"user\"], [data-message-author-role=\"assistant\"]"
            ConnectionKind.CLAUDE_WEB -> "[data-testid=\"user-message\"], .font-user-message, .font-claude-response, .font-claude-message"
            ConnectionKind.REMOTE_WEB -> "[data-testid=\"user-message\"], .font-user-message, .font-claude-response, .font-claude-message, [data-message-author-role], article"
            ConnectionKind.GEMINI_WEB -> "user-query, model-response"
            else -> "[data-message-author-role], user-query, model-response, article"
        }
        val js = """
            (function() {
                try {
                    $DEEP_QUERY_JS
                    var scrollers = queryAllDeep('infinite-scroller, .chat-history, main, [role="main"]');
                    for (var s = 0; s < scrollers.length; s++) {
                        try { scrollers[s].scrollTop = scrollers[s].scrollHeight; } catch(se) {}
                    }
                    var nodes = queryAllDeep('$selector');
                    var out = [];
                    var lastTxt = '';
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        var tag = (el.tagName || '').toLowerCase();
                        var roleAttr = (el.getAttribute && el.getAttribute('data-message-author-role')) || '';
                        var cls = (el.className || '').toString().toLowerCase();
                        var testId = (el.getAttribute && el.getAttribute('data-testid')) || '';
                        var isUser = roleAttr === 'user' || tag === 'user-query' || testId === 'user-message' || cls.indexOf('user-message') !== -1 || cls.indexOf('user-query') !== -1;
                        var txt = (el.innerText || '').trim();
                        if (isUser) {
                            txt = txt.replace(/^(?:말씀하신\s*내용|You\s*said)\s*[:：]?\s*\n+/i, '').trim();
                        } else {
                            txt = txt.replace(/^(?:Gemini의\s*대답|Gemini\s*said)\s*[:：]?\s*\n+/i, '').trim();
                        }
                        if (!txt || txt === lastTxt) continue;
                        lastTxt = txt;
                        out.push({ role: isUser ? 'user' : 'assistant', content: txt });
                    }
                    return JSON.stringify(out.slice(-40));
                } catch(e) { return '[]'; }
            })();
        """.trimIndent()
        var stableReads = 0
        var lastCount = -1
        var lastTail = ""
        fun attemptFetch(attempt: Int) {
            val curWvUrl = wv.url.orEmpty()
            // Wait for reload/navigation onPageFinished before reading DOM so we never read stale pre-reload DOM
            if ((navigated || reloaded) && loadedUrls[key] != true && attempt < 18) {
                mainHandler.postDelayed({ attemptFetch(attempt + 1) }, 500L)
                return
            }
            if (expectedPath.isNotBlank() && !curWvUrl.contains(expectedPath) && attempt < 18) {
                mainHandler.postDelayed({ attemptFetch(attempt + 1) }, 600L)
                return
            }
            wv.evaluateJavascript(js) { raw ->
                val list = mutableListOf<com.mbta.app.model.ChatMessage>()
                try {
                    val cleaned = raw?.trim()?.let {
                        if (it.startsWith("\"") && it.endsWith("\"")) {
                            org.json.JSONTokener(it).nextValue().toString()
                        } else it
                    } ?: "[]"
                    val arr = org.json.JSONArray(cleaned)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val roleStr = obj.optString("role")
                        val content = obj.optString("content").trim()
                        if (content.isBlank()) continue
                        val role = if (roleStr == "user") com.mbta.app.model.MessageRole.USER else com.mbta.app.model.MessageRole.ASSISTANT
                        list.add(
                            com.mbta.app.model.ChatMessage(
                                id = "msg_${UUID.randomUUID()}",
                                role = role,
                                content = content,
                                agentName = if (role == com.mbta.app.model.MessageRole.ASSISTANT) agentName else ""
                            )
                        )
                    }
                } catch (_: Exception) {}
                val tailSig = list.lastOrNull()?.content.orEmpty()
                if (list.isNotEmpty() && reloaded) {
                    if (list.size == lastCount && tailSig == lastTail) {
                        stableReads++
                    } else {
                        lastCount = list.size
                        lastTail = tailSig
                        stableReads = 0
                    }
                    if (stableReads < 1 && attempt < 16) {
                        mainHandler.postDelayed({ attemptFetch(attempt + 1) }, 700L)
                        return@evaluateJavascript
                    }
                }
                if (list.isNotEmpty() || attempt >= 18) {
                    log("fetchWebSessionMessages key=$key url=$curWvUrl count=${list.size} attempt=$attempt reloaded=$reloaded")
                    onResult(list)
                } else {
                    mainHandler.postDelayed({ attemptFetch(attempt + 1) }, 600L)
                }
            }
        }
        mainHandler.postDelayed({ attemptFetch(0) }, delayMs)
    }

    fun fetchWebSessions(
        key: String,
        kind: ConnectionKind = ConnectionKind.GEMINI_WEB,
        url: String = "",
        onResult: (List<ExternalSessionItem>) -> Unit
    ) {
        val wv = pool[key] ?: if (url.isNotBlank()) getOrCreateWebView(key, url) else null
        if (wv == null) {
            onResult(emptyList())
            return
        }

        val scrapeJs = """
            (function() {
                try {
                    $DEEP_QUERY_JS
                    var seen = {};
                    var out = [];
                    var iconWords = /^(chat_bubble|chat_bubble_outline|more_vert|more_horiz|push_pin|keep|edit|delete|auto_awesome|spark|menu|close|add|search|history|chevron_right|expand_more|forum|chat|comment|star|pin|open_in_new|content_copy|share)$/i;

                    function cleanTitle(raw) {
                        if (!raw) return '';
                        var lines = raw.split(/\r?\n/).map(function(l) { return l.trim(); }).filter(function(l) {
                            if (!l) return false;
                            if (iconWords.test(l)) return false;
                            if (/^[\u200B-\u200D\uFEFF\uE000-\uF8FF]+$/.test(l)) return false;
                            return true;
                        });
                        var joined = lines.join(' ').replace(/\s+/g, ' ').trim();
                        if (/^[0-9a-f\-_]{14,}$/i.test(joined)) return '';
                        return joined;
                    }

                    function findTimeLabel(el) {
                        try {
                            var tEl = el.querySelector && el.querySelector('time');
                            if (tEl) {
                                var txt = (tEl.innerText || tEl.getAttribute('datetime') || '').trim();
                                if (txt) return txt;
                            }
                            var cur = el;
                            for (var up = 0; up < 5 && cur; up++) {
                                var prev = cur.previousElementSibling;
                                var steps = 0;
                                while (prev && steps < 6) {
                                    var tag = (prev.tagName || '').toLowerCase();
                                    var cls = (prev.className || '').toString().toLowerCase();
                                    if (/^h[1-6]$/.test(tag) || prev.getAttribute('role') === 'heading' || /date|group|section|header|time|label|title/.test(cls)) {
                                        var htxt = (prev.innerText || '').replace(/\s+/g, ' ').trim();
                                        if (htxt && htxt.length <= 32 && /(오늘|어제|지난|최근|이번|전|년|월|일|today|yesterday|previous|last|recent|week|month|ago|202\d)/i.test(htxt)) {
                                            return htxt;
                                        }
                                    }
                                    prev = prev.previousElementSibling;
                                    steps++;
                                }
                                cur = cur.parentElement;
                            }
                        } catch(e) {}
                        return '';
                    }

                    function extractTitleFromEl(el) {
                        var titleSelectors = ['.conversation-title', '[data-test-id="conversation-title"]', '.title', '.truncate', 'span[dir="auto"]', 'div[dir="auto"]'];
                        for (var s = 0; s < titleSelectors.length; s++) {
                            try {
                                var sub = el.querySelector && el.querySelector(titleSelectors[s]);
                                if (sub) {
                                    var ct = cleanTitle(sub.innerText || sub.textContent || '');
                                    if (ct) return ct;
                                }
                            } catch(e) {}
                        }
                        var direct = cleanTitle(el.innerText || '');
                        if (direct) return direct;
                        var aria = cleanTitle(el.getAttribute && (el.getAttribute('aria-label') || el.getAttribute('title')) || '');
                        return aria;
                    }

                    var links = queryAllDeep('a[href]');
                    for (var i = 0; i < links.length; i++) {
                        var href = links[i].href || '';
                        if (!/(\/c\/[^\/?#]+|\/chat\/[^\/?#]+|\/app\/[^\/?#]+|\/r\/[^\/?#]+|\/gem\/[^\/?#]+\/[^\/?#]+|\/code\/(?:sessions?\/)?(?:session_)?[A-Za-z0-9_-]{8,})/.test(href)) continue;
                        if (/\/(new|settings|pricing|login|signin|about|faq|updates|extensions|gems)(\/|$|\?)/i.test(href)) continue;
                        if (seen[href]) continue;
                        var title = extractTitleFromEl(links[i]);
                        if (!title) continue;
                        seen[href] = true;
                        out.push({
                            id: href,
                            title: title.slice(0, 80),
                            url: href,
                            updatedLabel: findTimeLabel(links[i])
                        });
                        if (out.length >= 25) break;
                    }
                    return JSON.stringify(out);
                } catch(e) { return '[]'; }
            })();
        """.trimIndent()

        val openSidebarJs = """
            (function() {
                try {
                    $DEEP_QUERY_JS
                    function isVisible(el) {
                        if (!el) return false;
                        try {
                            var st = window.getComputedStyle(el);
                            if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                            var r = el.getBoundingClientRect();
                            return r.width > 0 && r.height > 0;
                        } catch(e) { return true; }
                    }
                    var selectors = [
                        'button[data-test-id="side-nav-menu-button"]',
                        'side-nav-menu-button button',
                        'button[data-testid="open-sidebar-button"]',
                        'button[data-testid="sidebar-toggle"]',
                        'button[data-testid="menu-button"]',
                        'button[aria-label*="Main menu" i]',
                        'button[aria-label*="기본 메뉴"]',
                        'button[aria-label*="Open sidebar" i]',
                        'button[aria-label*="사이드바 열기"]',
                        'button[aria-label*="사이드바"]',
                        'button[aria-label*="sidebar" i]',
                        'button[aria-label*="내비게이션"]',
                        'button[aria-label*="navigation" i]',
                        'button[aria-label*="메뉴"]',
                        'button[aria-label*="menu" i]'
                    ];
                    for (var i = 0; i < selectors.length; i++) {
                        var els = queryAllDeep(selectors[i]);
                        for (var j = 0; j < els.length; j++) {
                            var el = els[j];
                            if (!isVisible(el)) continue;
                            var lbl = ((el.getAttribute('aria-label') || '') + ' ' + (el.innerText || '')).toLowerCase();
                            if (/close|닫기|more|옵션|settings|설정|account|계정|profile|프로필/.test(lbl)) continue;
                            var expanded = el.getAttribute('aria-expanded');
                            if (expanded === 'true') return 'already_open';
                            el.click();
                            return 'clicked:' + selectors[i];
                        }
                    }
                    var icons = queryAllDeep('mat-icon, span.google-symbols, span.material-symbols-outlined');
                    for (var k = 0; k < icons.length; k++) {
                        var txt = (icons[k].innerText || icons[k].textContent || '').trim().toLowerCase();
                        if (txt === 'menu' || txt === 'side_navigation' || txt === 'dock_to_right') {
                            var btn = icons[k].closest ? icons[k].closest('button, [role="button"]') : icons[k].parentElement;
                            if (btn && isVisible(btn)) {
                                btn.click();
                                return 'clicked_icon:' + txt;
                            }
                        }
                    }
                    return 'not_found';
                } catch(e) { return 'err:' + e; }
            })();
        """.trimIndent()

        // Claude Code 웹은 /code/ 세션만 (일반 채팅 링크가 섞여 나오는 것 방지)
        val codeOnly = kind == ConnectionKind.REMOTE_WEB && url.contains("claude.ai/code", ignoreCase = true)
        fun parseResult(raw: String?): List<ExternalSessionItem> {
            val list = mutableListOf<ExternalSessionItem>()
            try {
                val cleaned = raw?.trim()?.let {
                    if (it.startsWith("\"") && it.endsWith("\"")) {
                        org.json.JSONTokener(it).nextValue().toString()
                    } else it
                } ?: "[]"
                val arr = org.json.JSONArray(cleaned)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val title = obj.optString("title").trim()
                    if (title.isBlank()) continue
                    if (codeOnly && !obj.optString("url").contains("/code/")) continue
                    list.add(
                        ExternalSessionItem(
                            id = obj.optString("id"),
                            title = title,
                            url = obj.optString("url"),
                            updatedLabel = obj.optString("updatedLabel")
                        )
                    )
                }
            } catch (_: Exception) {}
            return list
        }

        fun pollScrape(attempt: Int, sidebarClicked: Boolean) {
            wv.evaluateJavascript(scrapeJs) { raw ->
                val list = parseResult(raw)
                if (list.isNotEmpty() || attempt >= 8) {
                    log("fetchWebSessions key=$key kind=${kind.name} attempt=$attempt found=${list.size}")
                    onResult(list)
                } else if (!sidebarClicked) {
                    wv.evaluateJavascript(openSidebarJs) { openRes ->
                        log("fetchWebSessions autoOpenSidebar key=$key res=$openRes")
                        mainHandler.postDelayed({ pollScrape(attempt + 1, true) }, 550L)
                    }
                } else {
                    mainHandler.postDelayed({ pollScrape(attempt + 1, true) }, 450L)
                }
            }
        }

        val initialDelay = if (loadedUrls[key] == true) 50L else 2200L
        mainHandler.postDelayed({ pollScrape(0, false) }, initialDelay)
    }

    /** 세션 목록이 비었을 때 원인 파악용: 현재 페이지의 링크/사이드바 요소를 텍스트로 덤프 */
    fun dumpSidebarDiagnostics(key: String, onResult: (String) -> Unit) {
        val wv = pool[key]
        if (wv == null) {
            onResult("WebView 없음 (key=$key)")
            return
        }
        val js = """
            (function() {
                try {
                    $DEEP_QUERY_JS
                    var out = [];
                    out.push('url=' + location.href);
                    var links = queryAllDeep('a[href]');
                    out.push('links=' + links.length);
                    for (var i = 0; i < links.length && i < 60; i++) {
                        out.push('A ' + (links[i].getAttribute('href') || '') + ' | ' + ((links[i].innerText || '').trim().replace(/\s+/g, ' ').slice(0, 40)));
                    }
                    var cands = queryAllDeep('[data-session-id],[data-testid*="session" i],[role="link"],[role="listitem"],nav button');
                    out.push('candidates=' + cands.length);
                    for (var j = 0; j < cands.length && j < 30; j++) {
                        var e = cands[j];
                        out.push('E <' + e.tagName.toLowerCase() + ' testid=' + (e.getAttribute('data-testid') || '') + ' sid=' + (e.getAttribute('data-session-id') || '') + '> ' + ((e.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 40)));
                    }
                    return out.join('\n');
                } catch(e) { return 'err:' + e; }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { raw ->
            val text = raw?.trim()?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) org.json.JSONTokener(it).nextValue().toString() else it
            } ?: ""
            onResult(text)
        }
    }

    /** 로그인된 웹 계정의 이메일/이름을 읽어옴 (Claude/ChatGPT는 계정 API, 그 외는 화면의 계정 아바타 라벨) */
    fun fetchWebAccountName(key: String, kind: ConnectionKind, url: String, onResult: (String) -> Unit) {
        val wv = pool[key]
        if (wv == null) {
            onResult("")
            return
        }
        val endpoint = when {
            kind == ConnectionKind.CLAUDE_WEB -> "/api/account"
            kind == ConnectionKind.CHATGPT_WEB -> "/api/auth/session"
            kind == ConnectionKind.REMOTE_WEB && url.contains("claude.ai", ignoreCase = true) -> "/api/account"
            else -> ""
        }
        val reqId = "__acct_$key"
        val js = """
            (function() {
                $DEEP_QUERY_JS
                function send(v) { try { window.MbtABridge && window.MbtABridge.onChunk('$reqId', v || ''); } catch(e) {} }
                function domScan() {
                    try {
                        var els = queryAllDeep('[aria-label],[title]');
                        for (var i = 0; i < els.length; i++) {
                            var t = (els[i].getAttribute('aria-label') || els[i].getAttribute('title') || '');
                            var m = t.match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/);
                            if (m) return m[0];
                        }
                    } catch(e) {}
                    return '';
                }
                var ep = ${JSONObject.quote(endpoint)};
                if (ep) {
                    fetch(ep, { credentials: 'include' })
                        .then(function(r) { return r.ok ? r.json() : null; })
                        .then(function(d) {
                            var v = '';
                            if (d) v = d.email_address || d.email || (d.user && d.user.email) || d.full_name || d.name || '';
                            send(v || domScan());
                        })
                        .catch(function() { send(domScan()); });
                } else {
                    send(domScan());
                }
            })();
        """.trimIndent()
        fun attempt(n: Int) {
            val cur = pool[key] ?: run { onResult(""); return }
            pending[reqId] = PendingRequest(
                inputTokens = 0L,
                onChunk = { payload ->
                    pending.remove(reqId)
                    val v = payload.trim()
                    if (v.isNotEmpty()) onResult(v)
                    else if (n < 4) mainHandler.postDelayed({ attempt(n + 1) }, 3000L)
                    else onResult("")
                },
                onDone = { _, _, _ -> },
                onError = { _ -> }
            )
            cur.evaluateJavascript(js, null)
        }
        attempt(0)
    }

    fun checkLoginStatus(
        key: String,
        kind: ConnectionKind,
        url: String,
        onResult: (Boolean) -> Unit
    ) {
        val wv = pool[key]
        val cookieMgr = CookieManager.getInstance()
        val cookies = listOfNotNull(
            cookieMgr.getCookie(url),
            wv?.url?.let { cookieMgr.getCookie(it) },
            if (kind == ConnectionKind.GEMINI_WEB || kind == ConnectionKind.REMOTE_WEB) {
                cookieMgr.getCookie("https://google.com")
            } else null,
            if (kind == ConnectionKind.GEMINI_WEB || kind == ConnectionKind.REMOTE_WEB) {
                cookieMgr.getCookie("https://accounts.google.com")
            } else null
        ).joinToString(";")

        val hasAuthCookie = when (kind) {
            ConnectionKind.CLAUDE_WEB -> cookies.contains("sessionKey") || cookies.contains("__cf_bm")
            ConnectionKind.CHATGPT_WEB -> cookies.contains("__Secure-next-auth.session-token") || cookies.contains("oai-did")
            ConnectionKind.GEMINI_WEB -> cookies.contains("SID=") || cookies.contains("__Secure-1PSID")
            ConnectionKind.REMOTE_WEB -> cookies.isNotBlank()
            else -> false
        }

        if (wv != null && loadedUrls[key] == true) {
            val js = """
                (function() {
                    $DEEP_QUERY_JS
                    var host = (location.hostname || '').toLowerCase();
                    var path = (location.pathname || '').toLowerCase();
                    if (host === 'accounts.google.com' || host === 'auth0.com' || /^\/(login|signin|oauth|account-chooser)/.test(path)) return 'false';
                    var els = queryAllDeep('#prompt-textarea, div.ProseMirror, rich-textarea [contenteditable], [contenteditable]:not([contenteditable="false"]), textarea, [role="textbox"]');
                    if (els.length > 0) return 'true';
                    try {
                        if (window.localStorage && window.localStorage.length > 0) return 'true';
                    } catch(e) {}
                    if (${if (hasAuthCookie) "true" else "false"}) return 'true';
                    var bodyLen = (document.body && document.body.innerText) ? document.body.innerText.trim().length : 0;
                    if (bodyLen > 40) return 'true';
                    return 'false';
                })();
            """.trimIndent()
            wv.evaluateJavascript(js) { res ->
                val ok = res?.contains("true") == true
                onResult(ok)
            }
            return
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

                $DEEP_QUERY_JS

                function isVisible(el) {
                    if (!el) return false;
                    try {
                        var st = window.getComputedStyle(el);
                        if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                        var r = el.getBoundingClientRect();
                        return r.width > 0 && r.height > 0;
                    } catch(e) { return true; }
                }

                function findFirst(selectors) {
                    var fallback = null;
                    for (var i = 0; i < selectors.length; i++) {
                        try {
                            var els = queryAllDeep(selectors[i]);
                            for (var j = 0; j < els.length; j++) {
                                if (els[j] && !fallback) fallback = els[j];
                                if (isVisible(els[j])) return els[j];
                            }
                        } catch(e) {}
                    }
                    return fallback;
                }

                var inputSelectors = [];
                var msgSelectors = [];
                if (kind === 'CHATGPT_WEB') {
                    inputSelectors = ['#prompt-textarea', '[contenteditable]:not([contenteditable="false"])', 'textarea', '[role="textbox"]'];
                    msgSelectors = ['[data-message-author-role="assistant"] .markdown', '[data-message-author-role="assistant"]'];
                } else if (kind === 'CLAUDE_WEB') {
                    inputSelectors = ['div.ProseMirror', 'fieldset [contenteditable]', '[contenteditable]:not([contenteditable="false"])', 'textarea', '[role="textbox"]'];
                    msgSelectors = ['.font-claude-response', '.font-claude-message', '[data-testid="assistant-message"]', '[data-is-streaming]', '.prose'];
                } else if (kind === 'GEMINI_WEB') {
                    inputSelectors = ['rich-textarea [contenteditable]', 'div.ql-editor', '[contenteditable]:not([contenteditable="false"])', 'textarea', '[role="textbox"]'];
                    msgSelectors = ['model-response .markdown', 'model-response message-content', 'message-content', '.model-response-text'];
                } else {
                    inputSelectors = [
                        '[contenteditable]:not([contenteditable="false"])',
                        '[role="textbox"]',
                        'textarea',
                        'input[type="text"]',
                        'input[type="search"]',
                        'input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]):not([type="file"])'
                    ];
                    msgSelectors = ['[data-message-author-role="assistant"]', '.font-claude-response', '.prose', 'article', 'pre'];
                }

                function findAnyEditable() {
                    var el = findFirst(inputSelectors);
                    if (el) return el;
                    var all = queryAllDeep('*');
                    for (var k = 0; k < all.length; k++) {
                        try {
                            var node = all[k];
                            if (!node) continue;
                            if (node.isContentEditable && isVisible(node)) return node;
                            var ph = (node.getAttribute && (node.getAttribute('placeholder') || node.getAttribute('data-placeholder') || node.getAttribute('aria-label'))) || '';
                            if (/ask anything|@ to mention|message|물어보기|입력/i.test(ph) && isVisible(node)) return node;
                        } catch(e) {}
                    }
                    for (var m = 0; m < all.length; m++) {
                        try {
                            var txtNode = all[m];
                            if (!txtNode || !isVisible(txtNode)) continue;
                            var txt = (txtNode.innerText || txtNode.textContent || '').trim();
                            if (txt.length > 0 && txt.length <= 80 && /ask anything|@ to mention|물어보기/i.test(txt)) {
                                var p = txtNode;
                                for (var up = 0; up < 4 && p; up++) {
                                    if (p.querySelector) {
                                        var cand = p.querySelector('[contenteditable], textarea, input:not([type="hidden"]), [role="textbox"]');
                                        if (cand && isVisible(cand)) return cand;
                                    }
                                    p = p.parentElement;
                                }
                                txtNode.click();
                                var ae = document.activeElement;
                                if (ae && (ae.isContentEditable || ae.tagName === 'TEXTAREA' || ae.tagName === 'INPUT' || ae.getAttribute('role') === 'textbox')) {
                                    return ae;
                                }
                            }
                        } catch(e) {}
                    }
                    return null;
                }

                function proceedWithInput(inputEl) {
                    function getLatestMsgText() {
                        for (var i = 0; i < msgSelectors.length; i++) {
                            try {
                                var nodes = queryAllDeep(msgSelectors[i]);
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
                        try {
                            inputEl.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: promptText }));
                        } catch(e) {
                            inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                    }

                    setTimeout(function() {
                        var sendBtn = findFirst([
                            'button[data-testid="send-button"]',
                            'button[aria-label*="send" i]',
                            'button[aria-label*="submit" i]',
                            'button[aria-label*="run" i]',
                            'button[aria-label*="전송"]',
                            'button[aria-label*="보내기"]',
                            'button.send-button',
                            'button[type="submit"]'
                        ]);
                        if (!sendBtn) {
                            var scopeEl = (inputEl.closest && inputEl.closest('form')) || (inputEl.parentElement && inputEl.parentElement.parentElement && inputEl.parentElement.parentElement.parentElement);
                            if (scopeEl && scopeEl.querySelectorAll) {
                                var btns = scopeEl.querySelectorAll('button');
                                for (var b = btns.length - 1; b >= 0; b--) {
                                    var btn = btns[b];
                                    if (!btn || btn.disabled || !isVisible(btn)) continue;
                                    var bLabel = ((btn.getAttribute('aria-label') || '') + ' ' + (btn.innerText || '')).toLowerCase();
                                    if (/attach|upload|mic|voice|record|plus|add|menu|model|gemini|claude/.test(bLabel)) continue;
                                    sendBtn = btn;
                                    break;
                                }
                            }
                        }
                        function fireEnter(el) {
                            var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
                            el.dispatchEvent(new KeyboardEvent('keydown', opts));
                            el.dispatchEvent(new KeyboardEvent('keypress', opts));
                            el.dispatchEvent(new KeyboardEvent('keyup', opts));
                        }
                        if (sendBtn && !sendBtn.disabled) {
                            sendBtn.click();
                            setTimeout(function() {
                                var rem = (inputEl.value !== undefined ? inputEl.value : inputEl.innerText) || '';
                                if (rem.trim().length > 0) {
                                    fireEnter(inputEl);
                                }
                            }, 350);
                        } else {
                            fireEnter(inputEl);
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
                                    if (candidate.indexOf(promptText) === 0) {
                                        candidate = candidate.slice(promptText.length).trim();
                                    }
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
                }

                var waitTries = 0;
                var waitTimer = setInterval(function() {
                    var found = findAnyEditable();
                    if (found) {
                        clearInterval(waitTimer);
                        proceedWithInput(found);
                        return;
                    }
                    waitTries++;
                    if (waitTries >= 25) {
                        clearInterval(waitTimer);
                        if (window.MbtABridge) {
                            var loc = (location.hostname || '') + (location.pathname || '');
                            var iframeEls = document.querySelectorAll('iframe');
                            var iframes = iframeEls.length;
                            var iframeInfo = '';
                            if (iframes > 0) {
                                try {
                                    var f0 = iframeEls[0];
                                    var r0 = f0.getBoundingClientRect();
                                    iframeInfo = ', f0=' + (f0.src || '').slice(0, 60) + ' [' + Math.round(r0.width) + 'x' + Math.round(r0.height) + ']';
                                } catch(e) {}
                            }
                            var ta = queryAllDeep('textarea, input, [contenteditable]').length;
                            window.MbtABridge.onError(reqId, 'NEED_LOGIN', '입력창을 찾을 수 없습니다 (' + loc + ', iframe=' + iframes + iframeInfo + ', editable=' + ta + '). 상단 👁️ 버튼으로 화면 상태를 확인해주세요.');
                        }
                    }
                }, 400);
            })();
        """.trimIndent()
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val DEEP_QUERY_JS = """
            function queryAllDeep(selector) {
                var results = [];
                function walk(root) {
                    if (!root || !root.querySelectorAll) return;
                    try {
                        var found = root.querySelectorAll(selector);
                        for (var i = 0; i < found.length; i++) results.push(found[i]);
                    } catch(e) {}
                    try {
                        var all = root.querySelectorAll('*');
                        for (var j = 0; j < all.length; j++) {
                            if (all[j].shadowRoot) walk(all[j].shadowRoot);
                            if (all[j].__mbtaShadowRoot && all[j].__mbtaShadowRoot !== all[j].shadowRoot) walk(all[j].__mbtaShadowRoot);
                            if (all[j].tagName === 'IFRAME') {
                                try {
                                    if (all[j].contentDocument) walk(all[j].contentDocument);
                                } catch(e) {}
                            }
                        }
                    } catch(e) {}
                }
                walk(document);
                return results;
            }
        """
    }
}
