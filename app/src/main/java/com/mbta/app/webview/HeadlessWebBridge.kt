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
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
    /** 마지막으로 사용한 시각 — 유휴 WebView 휴면 판정용 */
    private val lastUsedAt = mutableMapOf<String, Long>()
    /** 휴면시킨 WebView 의 마지막 주소 — 다시 만들 때 같은 대화를 연다 */
    private val sleptUrls = mutableMapOf<String, String>()
    /** 웹 화면 직접 보기·로그인처럼 작업이 없어도 닫으면 안 되는 key */
    private var keepAliveKeys: Set<String> = emptySet()
    /** 지금 화면에서 보고 있는 세션의 key */
    private var viewedKeys: Set<String> = emptySet()
    private var appForeground = true
    /** 요청별로 답변이 마지막으로 바뀐 시각 — 정지 안내용 */
    private val lastProgressAt = mutableMapOf<String, Long>()
    private var hiddenHost: FrameLayout? = null
    private val targetUrls = mutableMapOf<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logs = ArrayDeque<String>()

    init {
        // adb(무선 디버깅) 승인된 기기에서만 접근 가능한 WebView 원격 디버깅 (DOM/전송 버튼 구조 확인용)
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }
    }

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
    /** 지연 전송(postDelayed) 중인 프롬프트 주입 작업 — 중지/해제 시 제거하려고 key별로 보관 */
    private val pendingSendRunnables = mutableMapOf<String, Runnable>()
    /** 메인 프레임 로드 실패가 난 key — 이후 onPageFinished 가 loaded=true 로 덮어쓰지 않게 함 */
    private val loadFailedKeys = mutableSetOf<String>()

    init {
        mainHandler.postDelayed({ idleSweep.run() }, IdleWebViewPolicy.SWEEP_MS)
    }

    /** 렌더러 종료 등으로 WebView 가 풀에서 사라졌을 때 호출 (세션 키). 외부 캐시 무효화용 */
    var onWebViewDropped: ((String) -> Unit)? = null
    /** 웹 자동화가 로그인 필요(NEED_LOGIN)를 보고했을 때 호출 (세션 키) */
    var onNeedLogin: ((String) -> Unit)? = null

    fun cancelActiveRequest(key: String) {
        pendingSendRunnables.remove(key)?.let { mainHandler.removeCallbacks(it) }
        val reqId = activeReqByKey.remove(key)
        if (reqId != null) { pending.remove(reqId); lastProgressAt.remove(reqId) }
        val wv = pool[key] ?: return
        val js = """
            (function() {
                try {
                    if (Array.isArray(window.__mbtaTimers)) {
                        window.__mbtaTimers.forEach(function(t) { clearTimeout(t); clearInterval(t); });
                    }
                    window.__mbtaTimers = [];
                    window.__mbtaReqId = null;
                    var stopBtn = document.querySelector('button[aria-label*="Stop" i], button[aria-label*="중지"], button[data-testid="stop-button"]');
                    if (stopBtn) stopBtn.click();
                } catch(e) {}
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    /** 웹 화면에서 긁어온 텍스트의 아이콘 폰트 글리프(사설 영역 문자)와 낱자 한자 줄을 제거 */
    private fun cleanWebText(raw: String, final: Boolean = true): String {
        if (raw.startsWith("__")) return raw
        return WebTextCleaner.clean(raw, final)
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onChunk(requestId: String, text: String) {
            mainHandler.post {
                lastProgressAt[requestId] = System.currentTimeMillis()
                pending[requestId]?.onChunk?.invoke(cleanWebText(text, final = false))
            }
        }

        @JavascriptInterface
        fun onDone(requestId: String, finalText: String) {
            mainHandler.post {
                log("onDone req=$requestId len=${finalText.length}")
                lastProgressAt.remove(requestId)
                activeReqByKey.entries.firstOrNull { it.value == requestId }?.let { touch(it.key) }
                val req = pending.remove(requestId) ?: return@post
                val cleaned = cleanWebText(finalText)
                val outTokens = ceil(cleaned.length / 3.0).toLong().coerceAtLeast(1L)
                req.onDone(cleaned, req.inputTokens, outTokens)
            }
        }

        @JavascriptInterface
        fun onError(requestId: String, code: String, message: String) {
            mainHandler.post {
                log("onError req=$requestId code=$code msg=$message")
                lastProgressAt.remove(requestId)
                activeReqByKey.entries.firstOrNull { it.value == requestId }?.let { touch(it.key) }
                if (code == "NEED_LOGIN") {
                    activeReqByKey.entries.firstOrNull { it.value == requestId }?.key?.let { onNeedLogin?.invoke(it) }
                }
                val req = pending.remove(requestId) ?: return@post
                req.onError(message)
            }
        }
    }

    /** 메인 프레임 로드 실패: 진행 중 요청이 있으면 오류로 끝내고 로드 상태를 미완료로 둠 */
    private fun markLoadFailed(key: String, msg: String) {
        loadFailedKeys.add(key)
        loadedUrls[key] = false
        val reqId = activeReqByKey.remove(key) ?: return
        val req = pending.remove(reqId) ?: return
        req.onError(msg)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(key: String, requestedUrl: String): WebView {
        touch(key)
        pool[key]?.let { return it }
        // 휴면했던 창은 마지막 주소로 복원. 호출자가 대화 주소를 따로 주면 navigateIfNeeded 가 이어서 이동시킴
        val url = sleptUrls.remove(key) ?: requestedUrl
        targetUrls[key] = url
        log("createWebView key=$key url=$url")

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#11111B"))
            // 백그라운드에서도 렌더러 우선순위를 유지해 페이지 정지를 줄임
            runCatching { setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }

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
                    loadFailedKeys.remove(key)
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
                    if (key !in loadFailedKeys) loadedUrls[key] = true
                    log("onPageFinished key=$key url=$loadedUrl failed=${key in loadFailedKeys}")
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != true) return
                    val desc = error?.description?.toString()?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"
                    log("onReceivedError key=$key code=${error?.errorCode} desc=$desc")
                    markLoadFailed(key, "페이지 로드 실패: $desc")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame != true) return
                    val status = errorResponse?.statusCode ?: 0
                    // 401/403/429 등은 로그인·보안 확인 페이지가 뒤이어 정상 로드되는 경우가 많아 서버 오류(5xx)만 실패로 처리
                    if (status < 500) { log("onReceivedHttpError key=$key status=$status (무시)"); return }
                    log("onReceivedHttpError key=$key status=$status")
                    markLoadFailed(key, "페이지 로드 실패: HTTP $status")
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    log("onRenderProcessGone key=$key didCrash=${detail?.didCrash()}")
                    markLoadFailed(key, "페이지 로드 실패: 웹 렌더러가 종료되었습니다")
                    // 죽은 WebView 는 풀에서 빼고 해제 — 다음 요청 때 새로 만든다
                    pool.remove(key)?.let { dead ->
                        (dead.parent as? ViewGroup)?.removeView(dead)
                        mainHandler.post { runCatching { dead.destroy() } }
                    }
                    loadedUrls.remove(key)
                    targetUrls.remove(key)
                    loadFailedKeys.remove(key)
                    onWebViewDropped?.invoke(key)
                    return true
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
        hiddenHost?.addView(webView)
        return webView
    }

    private fun touch(key: String) {
        lastUsedAt[key] = System.currentTimeMillis()
    }

    private fun isBusy(key: String): Boolean =
        pendingSendRunnables.containsKey(key) || activeReqByKey[key]?.let { pending.containsKey(it) } == true

    fun setViewState(viewed: Set<String>, keepAlive: Set<String>) {
        // 목록에서 빠지는 창은 이 순간부터 유휴 시간을 잼
        (keepAliveKeys - keepAlive).forEach { touch(it) }
        keepAliveKeys = keepAlive
        if (viewed == viewedKeys) return
        viewedKeys = viewed
        applyCheckIntervals()
    }

    fun setAppForeground(foreground: Boolean) {
        if (foreground == appForeground) return
        appForeground = foreground
        applyCheckIntervals()
    }

    fun checkIntervalFor(key: String): Long =
        IdleWebViewPolicy.checkIntervalMs(appForeground && key in viewedKeys)

    private fun applyCheckIntervals() {
        pool.forEach { (key, wv) ->
            if (isBusy(key)) {
                wv.evaluateJavascript("window.__mbtaSetInterval && window.__mbtaSetInterval(${checkIntervalFor(key)});", null)
            }
        }
    }

    /** 팬딩으로 닫힌 창을 입력을 시작할 때 미리 열어 둠 */
    fun prewake(key: String, url: String) {
        getOrCreateWebView(key, url)
    }

    /** 이 세션(서브 대화 포함)의 웹 답변이 안내 기준 이상 멈춰 있으면 멈춘 초, 아니면 0 */
    fun stalledSeconds(sessionId: String): Long {
        val now = System.currentTimeMillis()
        val stalledMs = activeReqByKey.entries
            .filter { it.key.substringAfter('#', "").substringBefore('~') == sessionId && pending.containsKey(it.value) }
            .mapNotNull { entry -> lastProgressAt[entry.value]?.let { now - it } }
            .maxOrNull() ?: 0L
        return if (stalledMs >= IdleWebViewPolicy.STALL_NOTICE_MS) stalledMs / 1000L else 0L
    }

    /** 작업이 끝나고 보고 있지 않은 WebView 를 닫아 메모리·CPU 를 돌려줌 (대화 주소는 기억) */
    private fun sleepIdleWebViews() {
        val now = System.currentTimeMillis()
        pool.keys.toList()
            .filter { IdleWebViewPolicy.shouldSleep(it in keepAliveKeys, isBusy(it), now - (lastUsedAt[it] ?: now)) }
            .forEach { key ->
                val lastUrl = pool[key]?.url.orEmpty()
                val loadOk = loadedUrls[key] == true
                log("sleepWebView key=$key url=$lastUrl")
                releaseWebView(key)
                if (loadOk && lastUrl.startsWith("http")) sleptUrls[key] = lastUrl
                onWebViewDropped?.invoke(key)
            }
    }

    private val idleSweep = object : Runnable {
        override fun run() {
            runCatching { sleepIdleWebViews() }
            mainHandler.postDelayed(this, IdleWebViewPolicy.SWEEP_MS)
        }
    }

    /** 창을 만들고(또는 깨우고) 로딩이 끝날 때까지 기다림. 모델 전환처럼 페이지가 필요한 작업 전에 호출 */
    suspend fun ensureLoaded(key: String, url: String) {
        val fresh = pool[key] == null
        getOrCreateWebView(key, url)
        if (!fresh && loadedUrls[key] == true) return
        var waited = 0L
        while (loadedUrls[key] != true && key !in loadFailedKeys && waited < 15000L) {
            kotlinx.coroutines.delay(250L)
            waited += 250L
        }
        // 로딩 완료 후에도 웹 앱이 화면과 이전 대화를 그릴 시간이 필요
        kotlinx.coroutines.delay(IdleWebViewPolicy.SETTLE_MS)
        touch(key)
    }

    /** 같은 연결(같은 로그인 쿠키·출처)의 열려 있는 창 — 사용량·계정 조회는 아무 창이나 쓰면 됨 */
    private fun anyViewOf(connKey: String): WebView? =
        pool[connKey] ?: pool.entries.firstOrNull { it.key.startsWith("$connKey#") && loadedUrls[it.key] == true }?.value

    /** 숨은 호스트를 등록하고 이미 열려 있는 창을 모두 붙임 (여기서는 새 창을 만들지 않음) */
    fun bindHiddenHost(host: FrameLayout) {
        hiddenHost = host
        pool.values.forEach { wv ->
            if (wv.parent !== host) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                host.addView(wv)
            }
        }
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
        // 같은 WebView에서 진행 중이던 이전 요청은 새 요청이 타이머를 정리하므로 끝나지 않음 → 오류로 종료시켜 대기 상태로 남지 않게 함
        activeReqByKey[key]?.let { oldId ->
            pending.remove(oldId)?.let { old -> mainHandler.post { old.onError("새 요청이 시작되어 이전 요청을 중단했습니다.") } }
        }
        activeReqByKey[key] = reqId
        log("sendPrompt key=$key kind=${kind.name} navigated=$navigated loaded=${loadedUrls[key]} wvUrl=${wv.url}")
        val estInput = ceil(prompt.length / 3.0).toLong().coerceAtLeast(1L)
        pending[reqId] = PendingRequest(estInput, onChunk, onDone, onError)

        val ready = !navigated && loadedUrls[key] == true
        // 같은 key 의 이전 지연 전송은 새 요청으로 대체되므로 취소 (이전 프롬프트가 뒤늦게 주입되지 않게)
        pendingSendRunnables.remove(key)?.let { mainHandler.removeCallbacks(it) }
        var waitedMs = 0L
        val sendRunnable = object : Runnable {
            var settled = ready
            override fun run() {
                if (!settled) {
                    if (loadedUrls[key] != true && waitedMs < 15000L) {
                        waitedMs += 250L
                        mainHandler.postDelayed(this, 250L)
                    } else {
                        // 이전 대화가 다 그려지기 전에 보내면 옛 답변을 새 답변으로 읽을 수 있어 잠시 더 기다림
                        settled = true
                        mainHandler.postDelayed(this, IdleWebViewPolicy.SETTLE_MS)
                    }
                    return
                }
                pendingSendRunnables.remove(key)
                touch(key)
                lastProgressAt[reqId] = System.currentTimeMillis()
                wv.evaluateJavascript(buildAutomationJs(reqId, kind.name, prompt, checkIntervalFor(key)), null)
            }
        }
        pendingSendRunnables[key] = sendRunnable
        mainHandler.postDelayed(sendRunnable, if (ready) 50L else 500L)
    }

    suspend fun sendPromptSuspend(
        key: String,
        kind: ConnectionKind,
        url: String,
        prompt: String,
        conversationUrl: String = "",
        onChunk: (String) -> Unit = {}
    ): Pair<Triple<String, Long, Long>, String?> = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            // 앱에서 중지하면 웹 AI의 생성도 멈춤
            if (Looper.myLooper() == Looper.getMainLooper()) cancelActiveRequest(key)
            else mainHandler.post { cancelActiveRequest(key) }
        }
        sendPrompt(
            key = key,
            kind = kind,
            url = url,
            prompt = prompt,
            conversationUrl = conversationUrl,
            onChunk = onChunk,
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

    /** 웹 화면의 모델 메뉴에서 모델 선택. 결과(JSON: picker/clicked/after/sample)를 onResult로 돌려주고 진단 로그에도 남김 */
    fun switchWebModel(key: String, modelName: String, onResult: (String) -> Unit = {}) {
        val wv = pool[key]
        if (wv == null) {
            onResult("{\"error\":\"nowebview\"}")
            return
        }
        val quoted = JSONObject.quote(modelName.lowercase())
        val reqId = "__swm_$key"
        val quotedReq = JSONObject.quote(reqId)
        val js = """
            (function() {
                $DEEP_QUERY_JS
                $FULL_CLICK_JS
                $PICKER_FIND_JS
                var target = $quoted;
                var res = { picker: null, clicked: null, after: '', sample: [] };
                function send() { try { window.MbtABridge.onChunk($quotedReq, JSON.stringify(res)); } catch(e) {} }
                var picker = findPicker();
                if (!picker) { res.error = 'nopicker'; send(); return; }
                res.picker = pickerLabel(picker).replace(/\s+/g, ' ').slice(0, 40);
                var visBefore = snapshotVisible();
                var done = false;
                // 정규화: 소문자, 공백/하이픈/점 제거. 등급 1=완전 일치, 2=메뉴 텍스트가 target 포함, 3=target 토큰(claude 제외) 전부 포함
                function normText(s) { return (s || '').toLowerCase().replace(/[\s\-.]+/g, ''); }
                var targetN = normText(target);
                var targetToks = target.toLowerCase().split(/[\s\-.]+/).filter(function(t) { return t && t !== 'claude'; });
                function matchTier(txt) {
                    var nt = normText(txt);
                    if (!nt || !targetN) return 0;
                    if (nt === targetN) return 1;
                    if (nt.indexOf(targetN) !== -1) return 2;
                    if (targetToks.length && targetToks.every(function(t) { return nt.indexOf(normText(t)) !== -1; })) return 3;
                    return 0;
                }
                function pick() {
                    if (done) return;
                    var items = queryAllDeep('[role="menuitem"], [role="option"], [role="menuitemradio"], li, button, div');
                    var best = null;
                    for (var j = 0; j < items.length; j++) {
                        var el = items[j];
                        if (visBefore.has(el) || !isShown(el)) continue;
                        if (el === picker || picker.contains(el) || el.contains(picker)) continue;
                        if (el.children && el.children.length > 4) continue;
                        var txt = (el.innerText || '').toLowerCase().trim();
                        if (!txt || txt.length > 60) continue;
                        var tier = matchTier(txt);
                        if (!tier) continue;
                        // 순위: 등급 → 역할 없는 일반 div 는 마지막 → 가장 짧은 텍스트
                        var divPen = (el.tagName === 'DIV' && !(el.getAttribute && el.getAttribute('role'))) ? 1 : 0;
                        var key = [tier, divPen, txt.length];
                        if (!best || key[0] < best.key[0] || (key[0] === best.key[0] && (key[1] < best.key[1] || (key[1] === best.key[1] && key[2] < best.key[2])))) {
                            best = { el: el, txt: txt, key: key };
                        }
                    }
                    if (best) {
                        done = true;
                        res.clicked = best.txt.replace(/\s+/g, ' ').slice(0, 40);
                        fullClick(best.el);
                    }
                }
                fullClick(picker);
                setTimeout(pick, 450);
                setTimeout(pick, 1300);
                setTimeout(function() {
                    if (!done) {
                        var items = queryAllDeep('[role="menuitem"], [role="option"], [role="menuitemradio"], li, button, div');
                        for (var q = 0; q < items.length && res.sample.length < 14; q++) {
                            var e2 = items[q];
                            if (visBefore.has(e2) || !isShown(e2)) continue;
                            if (e2.children && e2.children.length > 4) continue;
                            var t2 = (e2.innerText || '').trim().replace(/\s+/g, ' ');
                            if (t2 && t2.length <= 40) res.sample.push(t2);
                        }
                    }
                    var p2 = findPicker();
                    res.after = p2 ? pickerLabel(p2).replace(/\s+/g, ' ').slice(0, 40) : '';
                    send();
                }, 2100);
            })();
        """.trimIndent()
        pending[reqId] = PendingRequest(
            inputTokens = 0L,
            onChunk = { payload ->
                pending.remove(reqId)
                log("switchWebModel key=$key target=$modelName -> $payload")
                onResult(payload)
            },
            onDone = { _, _, _ -> },
            onError = { _ -> }
        )
        wv.evaluateJavascript(js, null)
    }

    /** claude.ai/code 모델 메뉴 하단 '노력(effort)' 항목을 눌러 적용 (낮음/중간/높음/엑스트라/최대) */
    fun switchWebEffort(key: String, effort: String, onResult: (String) -> Unit = {}) {
        val wv = pool[key]
        if (wv == null) {
            onResult("{\"error\":\"nowebview\"}")
            return
        }
        val cands = when (effort.lowercase()) {
            "low" -> listOf("낮음", "low")
            "medium" -> listOf("중간", "medium")
            "high" -> listOf("높음", "high")
            "extra", "xhigh" -> listOf("엑스트라", "extra", "xhigh")
            "max" -> listOf("최대", "max")
            else -> {
                onResult("{\"error\":\"unknown_effort\"}")
                return
            }
        }
        val arr = cands.joinToString(",") { JSONObject.quote(it) }
        val reqId = "__swe_$key"
        val quotedReq = JSONObject.quote(reqId)
        val js = """
            (function() {
                $DEEP_QUERY_JS
                var cands = [$arr];
                $FULL_CLICK_JS
                $PICKER_FIND_JS
                var res = { picker: null, clicked: null, after: '', sample: [] };
                function send() { try { window.MbtABridge.onChunk($quotedReq, JSON.stringify(res)); } catch(e) {} }
                var picker = findPicker();
                var visBefore = snapshotVisible();
                function clickEffort() {
                    var items = queryAllDeep('[role="menuitem"], [role="option"], [role="menuitemradio"], li, button, div');
                    for (var j = 0; j < items.length; j++) {
                        var el = items[j];
                        if (visBefore.has(el) || !isShown(el)) continue;
                        if (el.children && el.children.length > 4) continue;
                        if (picker && (el === picker || picker.contains(el) || el.contains(picker))) continue;
                        var txt = (el.innerText || '').trim().split('\n')[0].trim().toLowerCase();
                        if (!txt) continue;
                        for (var k = 0; k < cands.length; k++) {
                            if (txt === cands[k] || (txt.indexOf(cands[k]) === 0 && txt.length - cands[k].length <= 6)) {
                                res.clicked = txt.slice(0, 40);
                                fullClick(el);
                                return true;
                            }
                        }
                    }
                    return false;
                }
                if (!picker) { res.error = 'nopicker'; send(); return; }
                res.picker = pickerLabel(picker).replace(/\s+/g, ' ').slice(0, 40);
                var effDone = false;
                function tryEffort() { if (!effDone && clickEffort()) effDone = true; }
                fullClick(picker);
                setTimeout(tryEffort, 450);
                setTimeout(tryEffort, 1300);
                setTimeout(function() {
                    if (!effDone) {
                        var items = queryAllDeep('[role="menuitem"], [role="option"], [role="menuitemradio"], li, button, div');
                        for (var q = 0; q < items.length && res.sample.length < 14; q++) {
                            var e2 = items[q];
                            if (visBefore.has(e2) || !isShown(e2)) continue;
                            if (e2.children && e2.children.length > 4) continue;
                            var t2 = (e2.innerText || '').trim().replace(/\s+/g, ' ');
                            if (t2 && t2.length <= 40) res.sample.push(t2);
                        }
                    }
                    var p2 = findPicker();
                    res.after = p2 ? pickerLabel(p2).replace(/\s+/g, ' ').slice(0, 40) : '';
                    send();
                }, 2100);
            })();
        """.trimIndent()
        pending[reqId] = PendingRequest(
            inputTokens = 0L,
            onChunk = { payload ->
                pending.remove(reqId)
                log("switchWebEffort key=$key effort=$effort -> $payload")
                onResult(payload)
            },
            onDone = { _, _, _ -> },
            onError = { _ -> }
        )
        wv.evaluateJavascript(js, null)
    }

    fun currentUrl(key: String): String? = pool[key]?.url

    /** 세션 삭제 시 해당 세션 전용 WebView 해제 */
    fun releaseWebView(key: String) {
        pendingSendRunnables.remove(key)?.let { mainHandler.removeCallbacks(it) }
        activeReqByKey.remove(key)?.let { pending.remove(it); lastProgressAt.remove(it) }
        sleptUrls.remove(key)
        lastUsedAt.remove(key)
        val wv = pool.remove(key) ?: return
        loadedUrls.remove(key)
        loadFailedKeys.remove(key)
        targetUrls.remove(key)
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
    }

    fun clickWebActionButton(key: String, label: String, onResult: (Boolean) -> Unit = {}) {
        val slept = sleptUrls[key]
        if (pool[key] == null && slept != null) {
            getOrCreateWebView(key, slept)
            mainHandler.postDelayed({ clickWebActionButton(key, label, onResult) }, 6000L)
            return
        }
        touch(key)
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

    fun fetchClaudeWebUsagePercent(key: String, onResult: (Int?, String, Long, Long) -> Unit) {
        val wv = anyViewOf(key)
        if (wv == null) {
            onResult(null, "", 0L, 0L)
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
                            var r5 = data.five_hour && data.five_hour.resets_at ? (new Date(data.five_hour.resets_at).getTime() || 0) : 0;
                            var r7 = data.seven_day && data.seven_day.resets_at ? (new Date(data.seven_day.resets_at).getTime() || 0) : 0;
                            var payload = primary + '|' + parts.join(' · ') + '|' + r5 + '|' + r7;
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
                val parts = payload.split("|")
                val remPct = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 100)
                val lbl = parts.getOrNull(1).orEmpty().ifBlank { "Claude 계정" }
                val r5 = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val r7 = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                onResult(remPct, lbl, r5, r7)
            },
            onDone = { _, _, _ -> },
            onError = { _ -> }
        )
        wv.evaluateJavascript(js, null)
    }

    fun fetchChatGptWebUsagePercent(key: String, onResult: (Int?, String) -> Unit) {
        val wv = anyViewOf(key)
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
        val codeWebFetch = kind == ConnectionKind.REMOTE_WEB && url.contains("claude.ai", ignoreCase = true)
        val selector = when (kind) {
            ConnectionKind.CHATGPT_WEB -> "[data-message-author-role=\"user\"], [data-message-author-role=\"assistant\"]"
            ConnectionKind.CLAUDE_WEB -> "[data-testid=\"user-message\"], .font-user-message, .font-claude-response, .font-claude-message"
            ConnectionKind.REMOTE_WEB -> if (url.contains("claude.ai", ignoreCase = true)) {
                "[data-testid=\"user-message\"], [data-testid=\"assistant-message\"]"
            } else {
                "[data-testid=\"user-message\"], .font-user-message, .font-claude-response, .font-claude-message, [data-message-author-role], article"
            }
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
                    $ROW_TEXT_JS
                    var nodes = queryAllDeep('$selector');
                    var out = [];
                    var lastTxt = '';
                    // claude.ai/code: 대화 행 단위로 읽어 코드 블록 등 assistant-message 바깥 내용도 포함
                    var codeRows = $codeWebFetch ? queryAllDeep('[data-testid="transcript-row"]') : [];
                    if (codeRows.length) {
                        for (var ci = 0; ci < codeRows.length; ci++) {
                            var cr = codeRows[ci];
                            var um = cr.querySelector('[data-testid="user-message"]');
                            var ct = um ? (um.innerText || '').trim() : rowText(cr);
                            if (!ct || ct === lastTxt) continue;
                            lastTxt = ct;
                            out.push({ role: um ? 'user' : 'assistant', content: ct });
                        }
                        nodes = [];
                    }
                    for (var i = 0; i < nodes.length; i++) {
                        var el = nodes[i];
                        var tag = (el.tagName || '').toLowerCase();
                        var roleAttr = (el.getAttribute && el.getAttribute('data-message-author-role')) || '';
                        var cls = (el.className || '').toString().toLowerCase();
                        var testId = (el.getAttribute && el.getAttribute('data-testid')) || '';
                        var isUser = roleAttr === 'user' || tag === 'user-query' || testId === 'user-message' || cls.indexOf('user-message') !== -1 || cls.indexOf('user-query') !== -1;
                        var txt = (el.innerText || '').trim().replace(/^Claude\s*(응답|said|responded)\s*[:：]\s*/i, '');
                        if (isUser) {
                            txt = txt.replace(/^(?:말씀하신\s*내용|You\s*said)\s*[:：]?\s*\n+/i, '').trim();
                        } else {
                            txt = txt.replace(/^(?:Gemini의\s*대답|Gemini\s*said)\s*[:：]?\s*\n+/i, '').trim();
                        }
                        if (!txt || txt === lastTxt) continue;
                        lastTxt = txt;
                        out.push({ role: isUser ? 'user' : 'assistant', content: txt });
                    }
                    // 연속된 assistant 블록(도구 실행 사이로 나뉜 한 응답)은 하나로 합침
                    var merged = [];
                    for (var q = 0; q < out.length; q++) {
                        var prev = merged.length ? merged[merged.length - 1] : null;
                        if (prev && prev.role === 'assistant' && out[q].role === 'assistant') prev.content += '\n\n' + out[q].content;
                        else merged.push(out[q]);
                    }
                    out = merged;
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

    /** claude.ai/code 등 웹 화면에 떠 있는 AI 질문 카드(번호 선택지 + 건너뛰기)를 읽어 JSON으로 반환. 없으면 null */
    fun readWebQuestion(key: String, onResult: (String?) -> Unit) {
        val wv = pool[key]
        if (wv == null) {
            onResult(null)
            return
        }
        val js = """
            (function() {
                try {

                $DEEP_QUERY_JS
                function lns(e) { return (e.innerText || '').split('\n').map(function(l) { return l.trim(); }).filter(function(l) { return l; }); }
                var CTRL = /^(건너뛰기|skip|제출|submit|기타|other)$/i;
                var CLICKABLE = 'button, [role="button"], [role="radio"], [role="option"], [role="checkbox"], label, input';
                function mkOpt(row) {
                    var ls = lns(row);
                    if (ls.length && /^\d+$/.test(ls[0])) ls = ls.slice(1);
                    if (!ls.length || CTRL.test(ls[0])) return null;
                    return { label: ls[0], description: ls.slice(1).join(' '), el: row };
                }
                function questionOf(card, firstOpt) {
                    var all = lns(card);
                    var q = [];
                    for (var k = 0; k < all.length; k++) {
                        if (all[k] === firstOpt.label || /^\d+$/.test(all[k])) break;
                        q.push(all[k]);
                    }
                    return q.join(' ');
                }
                // 전략 A: '기타' 행의 형제 행들을 선택지로 사용 (번호가 글자가 아니어도 동작)
                function strategyA(card) {
                    var els = Array.prototype.slice.call(card.querySelectorAll('*'));
                    var other = null;
                    for (var i = 0; i < els.length; i++) {
                        var l = lns(els[i]);
                        if (l.length >= 1 && l.length <= 2 && /^(기타|other)$/i.test(l[0])) other = els[i];
                    }
                    if (!other) return null;
                    var row = other;
                    while (row.parentElement && row.parentElement !== card && row.parentElement.children.length < 3) row = row.parentElement;
                    var parent = row.parentElement;
                    if (!parent) return null;
                    var sibs = Array.prototype.slice.call(parent.children);
                    var idx = sibs.indexOf(row);
                    var before = sibs.slice(0, idx);
                    var cand = before.filter(function(r) {
                        var ls = lns(r);
                        if (!ls.length || /^h[1-6]$/i.test(r.tagName)) return false;
                        var ptr = false;
                        try { ptr = window.getComputedStyle(r).cursor === 'pointer'; } catch(e) {}
                        return ptr || !!r.querySelector(CLICKABLE) || r.matches(CLICKABLE);
                    });
                    var opts = cand.map(mkOpt).filter(function(o) { return o; });
                    if (opts.length >= 2) return { card: card, opts: opts, question: questionOf(card, opts[0]) };
                    return null;
                }
                // 전략 B: 클릭 가능한 요소(버튼/라디오 등) 중 안쪽 것만
                function strategyB(card) {
                    var els = Array.prototype.slice.call(card.querySelectorAll(CLICKABLE));
                    var opts = els.map(mkOpt).filter(function(o) { return o; });
                    var inner = opts.filter(function(a) { return !opts.some(function(b) { return b !== a && a.el.contains(b.el); }); });
                    if (inner.length >= 2) return { card: card, opts: inner, question: questionOf(card, inner[0]) };
                    return null;
                }
                // 전략 C: 줄 맨 앞이 숫자인 요소
                function strategyC(card) {
                    var els = Array.prototype.slice.call(card.querySelectorAll('div, li, button, label'));
                    var opts = [];
                    for (var j = 0; j < els.length; j++) {
                        var ls = lns(els[j]);
                        if (ls.length >= 2 && /^\d+$/.test(ls[0])) opts.push({ label: ls[1], description: ls.slice(2).join(' '), el: els[j] });
                    }
                    var inner = opts.filter(function(a) { return !opts.some(function(b) { return b !== a && a.el.contains(b.el); }); });
                    if (inner.length >= 2) return { card: card, opts: inner, question: questionOf(card, inner[0]) };
                    return null;
                }
                // 전략 D: 동그라미(라디오) 표시가 있는 행들 — 행마다 동그라미가 하나씩 있는 카드
                function isCircle(e) {
                    if (e.getAttribute && (e.getAttribute('role') === 'radio' || e.getAttribute('role') === 'checkbox' || (e.tagName === 'INPUT' && /radio|checkbox/.test(e.type)))) return true;
                    if ((e.innerText || '').trim()) return false;
                    var r = e.getBoundingClientRect();
                    if (r.width < 10 || r.width > 34 || Math.abs(r.width - r.height) > 3) return false;
                    var br = '';
                    try { br = window.getComputedStyle(e).borderRadius || ''; } catch(x) {}
                    var v = parseFloat(br) || 0;
                    return br.indexOf('%') >= 0 ? v >= 45 : v >= r.width / 2 - 1;
                }
                function strategyD(card) {
                    var all = Array.prototype.slice.call(card.querySelectorAll('*'));
                    var circles = all.filter(isCircle).filter(function(c) { return !all.some(function(o) { return o !== c && c.contains(o) && isCircle(o); }); });
                    if (circles.length < 3) return null;
                    function cnt(n) { return circles.filter(function(c) { return n.contains(c); }).length; }
                    var opts = [];
                    circles.forEach(function(c) {
                        var row = c;
                        while (row.parentElement && row.parentElement !== card && cnt(row.parentElement) === 1) row = row.parentElement;
                        var o = mkOpt(row);
                        if (o) opts.push(o);
                    });
                    if (opts.length >= 2) return { card: card, opts: opts, question: questionOf(card, opts[0]) };
                    return null;
                }
                // 전략 E: 마지막 수단 — 카드 글자 줄(질문, 선택지[, 설명], 기타)을 해석하고 글자로 요소를 찾아 누름
                function strategyE(card) {
                    var ls = lns(card);
                    var oi = -1;
                    for (var k = 0; k < ls.length; k++) if (/^(기타|other)$/i.test(ls[k])) { oi = k; break; }
                    if (oi < 3) return null;
                    var L = ls.slice(0, oi);
                    var rest = L.slice(1);
                    var items = [];
                    if (rest.length % 2 === 0 && rest.length >= 4) {
                        for (var a = 0; a < rest.length; a += 2) items.push({ label: rest[a], description: rest[a + 1] });
                    } else {
                        rest.forEach(function(x) { items.push({ label: x, description: '' }); });
                    }
                    if (items.length < 2) return null;
                    var els = Array.prototype.slice.call(card.querySelectorAll('*'));
                    var opts = items.map(function(it) {
                        var m = null;
                        for (var i = 0; i < els.length; i++) {
                            var l2 = lns(els[i]);
                            if (l2.length && l2[0] === it.label && (!m || m.contains(els[i]))) m = els[i];
                        }
                        return { label: it.label, description: it.description, el: m || card };
                    });
                    return { card: card, opts: opts, question: L[0] };
                }
                function findCard() {
                    var btns = queryAllDeep('button, [role="button"]');
                    var skip = null;
                    for (var i = 0; i < btns.length; i++) {
                        var t = (btns[i].innerText || '').trim();
                        if (/^(건너뛰기|skip)$/i.test(t)) { skip = btns[i]; break; }
                    }
                    if (!skip) return null;
                    var el = skip;
                    for (var up = 0; up < 8 && el; up++) {
                        el = el.parentElement;
                        if (!el) break;
                        var r = strategyA(el) || strategyC(el) || strategyD(el) || strategyB(el) || strategyE(el);
                        if (r && r.question) return r;
                    }
                    return null;
                }

                    var c = findCard();
                    if (!c || !c.question) return '';
                    return JSON.stringify({
                        web: true,
                        question: c.question,
                        options: c.opts.map(function(o) { return { label: o.label, description: o.description }; })
                    });
                } catch(e) { return ''; }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { raw ->
            val cleaned = raw?.trim()?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) org.json.JSONTokener(it).nextValue().toString() else it
            }.orEmpty()
            onResult(cleaned.ifBlank { null })
        }
    }

    /** 웹 질문 카드의 '건너뛰기' 버튼을 눌러 질문을 건너뜀 (원본 AI가 건너뛴 것을 알 수 있도록) */
    fun skipWebQuestion(key: String, onResult: (Boolean) -> Unit) {
        val wv = pool[key]
        if (wv == null) {
            onResult(false)
            return
        }
        val js = """
            (function() {
                try {
                    $DEEP_QUERY_JS
                    $FULL_CLICK_JS
                    var btns = queryAllDeep('button, [role="button"]');
                    for (var i = 0; i < btns.length; i++) {
                        var t = (btns[i].innerText || '').trim();
                        if (/^(건너뛰기|skip)$/i.test(t)) { fullClick(btns[i]); return 'clicked'; }
                    }
                    return 'nocard';
                } catch(e) { return 'err'; }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { raw -> onResult(raw?.trim('"') == "clicked") }
    }

    /** 웹 질문 카드에서 선택지를 대신 클릭하거나, '기타' 입력란에 직접 답변을 넣어 전송 */
    fun answerWebQuestion(key: String, optionIndices: List<Int>, freeText: String, onResult: (Boolean) -> Unit) {
        val wv = pool[key]
        if (wv == null) {
            onResult(false)
            return
        }
        val quotedText = JSONObject.quote(freeText)
        val idxArr = optionIndices.joinToString(",")
        val js = """
            (function() {
                try {

                $DEEP_QUERY_JS
                $FULL_CLICK_JS
                function lns(e) { return (e.innerText || '').split('\n').map(function(l) { return l.trim(); }).filter(function(l) { return l; }); }
                var CTRL = /^(건너뛰기|skip|제출|submit|기타|other)$/i;
                var CLICKABLE = 'button, [role="button"], [role="radio"], [role="option"], [role="checkbox"], label, input';
                function mkOpt(row) {
                    var ls = lns(row);
                    if (ls.length && /^\d+$/.test(ls[0])) ls = ls.slice(1);
                    if (!ls.length || CTRL.test(ls[0])) return null;
                    return { label: ls[0], description: ls.slice(1).join(' '), el: row };
                }
                function questionOf(card, firstOpt) {
                    var all = lns(card);
                    var q = [];
                    for (var k = 0; k < all.length; k++) {
                        if (all[k] === firstOpt.label || /^\d+$/.test(all[k])) break;
                        q.push(all[k]);
                    }
                    return q.join(' ');
                }
                // 전략 A: '기타' 행의 형제 행들을 선택지로 사용 (번호가 글자가 아니어도 동작)
                function strategyA(card) {
                    var els = Array.prototype.slice.call(card.querySelectorAll('*'));
                    var other = null;
                    for (var i = 0; i < els.length; i++) {
                        var l = lns(els[i]);
                        if (l.length >= 1 && l.length <= 2 && /^(기타|other)$/i.test(l[0])) other = els[i];
                    }
                    if (!other) return null;
                    var row = other;
                    while (row.parentElement && row.parentElement !== card && row.parentElement.children.length < 3) row = row.parentElement;
                    var parent = row.parentElement;
                    if (!parent) return null;
                    var sibs = Array.prototype.slice.call(parent.children);
                    var idx = sibs.indexOf(row);
                    var before = sibs.slice(0, idx);
                    var cand = before.filter(function(r) {
                        var ls = lns(r);
                        if (!ls.length || /^h[1-6]$/i.test(r.tagName)) return false;
                        var ptr = false;
                        try { ptr = window.getComputedStyle(r).cursor === 'pointer'; } catch(e) {}
                        return ptr || !!r.querySelector(CLICKABLE) || r.matches(CLICKABLE);
                    });
                    var opts = cand.map(mkOpt).filter(function(o) { return o; });
                    if (opts.length >= 2) return { card: card, opts: opts, question: questionOf(card, opts[0]) };
                    return null;
                }
                // 전략 B: 클릭 가능한 요소(버튼/라디오 등) 중 안쪽 것만
                function strategyB(card) {
                    var els = Array.prototype.slice.call(card.querySelectorAll(CLICKABLE));
                    var opts = els.map(mkOpt).filter(function(o) { return o; });
                    var inner = opts.filter(function(a) { return !opts.some(function(b) { return b !== a && a.el.contains(b.el); }); });
                    if (inner.length >= 2) return { card: card, opts: inner, question: questionOf(card, inner[0]) };
                    return null;
                }
                // 전략 C: 줄 맨 앞이 숫자인 요소
                function strategyC(card) {
                    var els = Array.prototype.slice.call(card.querySelectorAll('div, li, button, label'));
                    var opts = [];
                    for (var j = 0; j < els.length; j++) {
                        var ls = lns(els[j]);
                        if (ls.length >= 2 && /^\d+$/.test(ls[0])) opts.push({ label: ls[1], description: ls.slice(2).join(' '), el: els[j] });
                    }
                    var inner = opts.filter(function(a) { return !opts.some(function(b) { return b !== a && a.el.contains(b.el); }); });
                    if (inner.length >= 2) return { card: card, opts: inner, question: questionOf(card, inner[0]) };
                    return null;
                }
                // 전략 D: 동그라미(라디오) 표시가 있는 행들 — 행마다 동그라미가 하나씩 있는 카드
                function isCircle(e) {
                    if (e.getAttribute && (e.getAttribute('role') === 'radio' || e.getAttribute('role') === 'checkbox' || (e.tagName === 'INPUT' && /radio|checkbox/.test(e.type)))) return true;
                    if ((e.innerText || '').trim()) return false;
                    var r = e.getBoundingClientRect();
                    if (r.width < 10 || r.width > 34 || Math.abs(r.width - r.height) > 3) return false;
                    var br = '';
                    try { br = window.getComputedStyle(e).borderRadius || ''; } catch(x) {}
                    var v = parseFloat(br) || 0;
                    return br.indexOf('%') >= 0 ? v >= 45 : v >= r.width / 2 - 1;
                }
                function strategyD(card) {
                    var all = Array.prototype.slice.call(card.querySelectorAll('*'));
                    var circles = all.filter(isCircle).filter(function(c) { return !all.some(function(o) { return o !== c && c.contains(o) && isCircle(o); }); });
                    if (circles.length < 3) return null;
                    function cnt(n) { return circles.filter(function(c) { return n.contains(c); }).length; }
                    var opts = [];
                    circles.forEach(function(c) {
                        var row = c;
                        while (row.parentElement && row.parentElement !== card && cnt(row.parentElement) === 1) row = row.parentElement;
                        var o = mkOpt(row);
                        if (o) opts.push(o);
                    });
                    if (opts.length >= 2) return { card: card, opts: opts, question: questionOf(card, opts[0]) };
                    return null;
                }
                // 전략 E: 마지막 수단 — 카드 글자 줄(질문, 선택지[, 설명], 기타)을 해석하고 글자로 요소를 찾아 누름
                function strategyE(card) {
                    var ls = lns(card);
                    var oi = -1;
                    for (var k = 0; k < ls.length; k++) if (/^(기타|other)$/i.test(ls[k])) { oi = k; break; }
                    if (oi < 3) return null;
                    var L = ls.slice(0, oi);
                    var rest = L.slice(1);
                    var items = [];
                    if (rest.length % 2 === 0 && rest.length >= 4) {
                        for (var a = 0; a < rest.length; a += 2) items.push({ label: rest[a], description: rest[a + 1] });
                    } else {
                        rest.forEach(function(x) { items.push({ label: x, description: '' }); });
                    }
                    if (items.length < 2) return null;
                    var els = Array.prototype.slice.call(card.querySelectorAll('*'));
                    var opts = items.map(function(it) {
                        var m = null;
                        for (var i = 0; i < els.length; i++) {
                            var l2 = lns(els[i]);
                            if (l2.length && l2[0] === it.label && (!m || m.contains(els[i]))) m = els[i];
                        }
                        return { label: it.label, description: it.description, el: m || card };
                    });
                    return { card: card, opts: opts, question: L[0] };
                }
                function findCard() {
                    var btns = queryAllDeep('button, [role="button"]');
                    var skip = null;
                    for (var i = 0; i < btns.length; i++) {
                        var t = (btns[i].innerText || '').trim();
                        if (/^(건너뛰기|skip)$/i.test(t)) { skip = btns[i]; break; }
                    }
                    if (!skip) return null;
                    var el = skip;
                    for (var up = 0; up < 8 && el; up++) {
                        el = el.parentElement;
                        if (!el) break;
                        var r = strategyA(el) || strategyC(el) || strategyD(el) || strategyB(el) || strategyE(el);
                        if (r && r.question) return r;
                    }
                    return null;
                }

                    var c = findCard();
                    if (!c) return 'nocard';
                    var idxs = [$idxArr];
                    var text = $quotedText;
                    function clickOpt(i) {
                        if (i < 0 || i >= c.opts.length) return;
                        var target = c.opts[i].el;
                        var clickEl = target.matches(CLICKABLE) ? target : (target.querySelector(CLICKABLE) || target);
                        fullClick(clickEl);
                    }
                    // 선택지를 눌러도 '제출' 버튼을 따로 눌러야 하는 카드가 있어, 선택 후 제출 버튼이 있으면 눌러줌
                    function findSubmit() {
                        var cardNow = findCard();
                        var scope = cardNow ? cardNow.card : document;
                        var bs = Array.prototype.slice.call(scope.querySelectorAll('button, [role="button"]'));
                        for (var q = 0; q < bs.length; q++) {
                            var tt = (bs[q].innerText || '').trim();
                            if (/^(제출|submit|보내기|전송|확인|답변 제출|완료|다음|next)$/i.test(tt) && !bs[q].disabled && bs[q].getAttribute('aria-disabled') !== 'true') return bs[q];
                        }
                        return null;
                    }
                    function submitSoon(delay) { setTimeout(function() { var sb = findSubmit(); if (sb) fullClick(sb); }, delay); }
                    if (idxs.length && !text) {
                        idxs.forEach(function(i, n) { setTimeout(function() { clickOpt(i); }, n * 300); });
                        submitSoon(idxs.length * 300 + 450);
                        return 'clicked';
                    }
                    if (text) {
                        var combined = text;
                        if (idxs.length) {
                            var labels = idxs.map(function(i) { return c.opts[i] ? c.opts[i].label : ''; }).filter(function(x) { return x; });
                            combined = '선택: ' + labels.join(', ') + '\n' + text;
                        }
                        function findInput() {
                            return c.card.querySelector('textarea, input[type="text"], input:not([type]), [contenteditable="true"]');
                        }
                        var inp = findInput();
                        if (!inp) {
                            var others = Array.prototype.slice.call(c.card.querySelectorAll('button, [role="button"], div'));
                            for (var i = 0; i < others.length; i++) {
                                var t = (others[i].innerText || '').trim().split('\n')[0].trim();
                                if (/^(기타|other)$/i.test(t)) { fullClick(others[i]); break; }
                            }
                            return 'opened_other';
                        }
                        inp.focus();
                        if (inp.isContentEditable) { inp.textContent = combined; }
                        else {
                            var proto = inp.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                            Object.getOwnPropertyDescriptor(proto, 'value').set.call(inp, combined);
                        }
                        inp.dispatchEvent(new Event('input', { bubbles: true }));
                        var kd = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true };
                        inp.dispatchEvent(new KeyboardEvent('keydown', kd));
                        inp.dispatchEvent(new KeyboardEvent('keyup', kd));
                        submitSoon(500);
                        return 'typed';
                    }
                    return 'noop';
                } catch(e) { return 'err:' + e; }
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { raw ->
            val r = raw?.trim()?.trim('"').orEmpty()
            if (r == "opened_other" && freeText.isNotBlank()) {
                // '기타' 입력란이 열리면 잠시 후 한 번 더 시도
                mainHandler.postDelayed({ answerWebQuestion(key, optionIndices, freeText, onResult) }, 500L)
            } else {
                onResult(r == "clicked" || r == "typed")
            }
        }
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
        val wv = anyViewOf(key)
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
        mainHandler.removeCallbacks(idleSweep)
        hiddenHost = null
        sleptUrls.clear()
        lastUsedAt.clear()
        lastProgressAt.clear()
        pendingSendRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        pendingSendRunnables.clear()
        pending.clear()
        pool.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        pool.clear()
    }

    /** 앱이 포그라운드로 돌아올 때 풀의 WebView 타이머·렌더링을 다시 켬 */
    fun resumeAll() {
        pool.values.forEach { wv ->
            runCatching {
                wv.onResume()
                wv.resumeTimers()
            }
        }
    }

    private fun buildAutomationJs(reqId: String, kindName: String, prompt: String, checkMs: Long): String {
        val quotedPrompt = JSONObject.quote(prompt)
        val quotedReqId = JSONObject.quote(reqId)
        val quotedKind = JSONObject.quote(kindName)

        return """
            (function() {
                var reqId = $quotedReqId;
                var kind = $quotedKind;
                var promptText = $quotedPrompt;
                var checkMs = $checkMs;
                // 이전 요청의 타이머를 모두 정리하고, 이 요청의 타이머만 유효하도록 등록
                var prevTimers = Array.isArray(window.__mbtaTimers) ? window.__mbtaTimers : [];
                prevTimers.forEach(function(t) { clearTimeout(t); clearInterval(t); });
                window.__mbtaTimers = [];
                window.__mbtaReqId = reqId;

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
                    msgSelectors = ['[data-message-author-role="assistant"] .markdown', '[data-message-author-role="assistant"]', '[data-response-body]'];
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
                    // claude.ai/code: article/pre(코드 블록)를 응답으로 오인하지 않도록 어시스턴트 메시지 셀렉터만 쓰고 나머지는 화면 텍스트 증가분으로 보완
                    if (kind === 'REMOTE_WEB' && (location.hostname || '').indexOf('claude.ai') >= 0) {
                        msgSelectors = ['[data-testid="assistant-message"]', '.font-claude-response', '.font-claude-message', '[data-message-author-role="assistant"]'];
                    }
                }

                $ROW_TEXT_JS
                // claude.ai/code: 실제 화면 구조(진단 로그 기준) - 마지막 사용자 메시지 이후의 대화 행을 읽음.
                // 코드 블록이 assistant-message 바깥에 있어도 포함되도록 행 단위로 읽고, 행이 없으면 assistant-message 방식으로 대체
                var isCodeWeb = (kind === 'REMOTE_WEB' && (location.hostname || '').indexOf('claude.ai') >= 0);
                function getCodeRowsReply() {
                    var rows = queryAllDeep('[data-testid="transcript-row"]');
                    if (!rows.length) return null;
                    var users = queryAllDeep('[data-testid="user-message"]');
                    var lastUser = users.length ? users[users.length - 1] : null;
                    var parts = [];
                    for (var ri = 0; ri < rows.length; ri++) {
                        var r = rows[ri];
                        if (lastUser && !(lastUser.compareDocumentPosition(r) & Node.DOCUMENT_POSITION_FOLLOWING)) continue;
                        if (r.querySelector('[data-testid="user-message"]')) continue;
                        var t = rowText(r);
                        if (t && parts.indexOf(t) < 0) parts.push(t);
                        var ls = r.querySelectorAll('a[href]');
                        for (var li = 0; li < ls.length; li++) {
                            var l = ls[li];
                            var href = l.href || '';
                            if (!href || href.indexOf('blob:') === 0 || t.indexOf(href) >= 0) continue;
                            if (l.hasAttribute('download') || /\.(apk|zip|pdf|png|jpe?g|gif|webp|mp4|mp3|csv|xlsx?|docx?|pptx?|tar|gz|7z|txt|md|json)(\?|#|$)/i.test(href) || /\/(download|files)\//i.test(href)) {
                                var nm = ((l.getAttribute('download') || l.innerText || '') + '').trim().split('\n')[0] || href.split('?')[0].split('/').pop();
                                parts.push('[📎 ' + nm + '](' + href + ')');
                            }
                        }
                    }
                    return { count: rows.length, text: parts.join('\n') };
                }
                function getCodeReply() {
                    var viaRows = getCodeRowsReply();
                    if (viaRows) return viaRows;
                    var users = queryAllDeep('[data-testid="user-message"]');
                    var assts = queryAllDeep('[data-testid="assistant-message"]');
                    var lastUser = users.length ? users[users.length - 1] : null;
                    // 중첩된 assistant-message는 바깥쪽 하나만 사용 (같은 글이 두 번 잡히는 것 방지)
                    assts = assts.filter(function(a) { return !assts.some(function(b) { return b !== a && b.contains(a); }); });
                    var parts = [];
                    for (var ai = 0; ai < assts.length; ai++) {
                        var a = assts[ai];
                        if (lastUser && !(lastUser.compareDocumentPosition(a) & Node.DOCUMENT_POSITION_FOLLOWING)) continue;
                        // 화면 낭독용 숨은 머리말("Claude 응답:") 제거, 똑같은 블록은 한 번만
                        var t = (a.innerText || '').trim().replace(/^Claude\s*(응답|said|responded)\s*[:：]\s*/i, '');
                        if (t && parts.indexOf(t) < 0) parts.push(t);
                        // 파일 카드/다운로드 링크는 글자만으로는 안 보이므로 [📎 이름](주소) 줄로 덧붙임 (앱이 다운로드 버튼으로 표시)
                        var ls = a.querySelectorAll('a[href]');
                        for (var li = 0; li < ls.length; li++) {
                            var l = ls[li];
                            var href = l.href || '';
                            if (!href || href.indexOf('blob:') === 0 || t.indexOf(href) >= 0) continue;
                            if (l.hasAttribute('download') || /\.(apk|zip|pdf|png|jpe?g|gif|webp|mp4|mp3|csv|xlsx?|docx?|pptx?|tar|gz|7z|txt|md|json)(\?|#|$)/i.test(href) || /\/(download|files)\//i.test(href)) {
                                var nm = ((l.getAttribute('download') || l.innerText || '') + '').trim().split('\n')[0] || href.split('?')[0].split('/').pop();
                                parts.push('[📎 ' + nm + '](' + href + ')');
                            }
                        }
                    }
                    return { count: assts.length, text: parts.join('\n\n') };
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
                        if (isCodeWeb) return getCodeReply();
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

                    var proceedDelay = promptText.length > 1000 ? 1200 : 300;
                    window.__mbtaTimers.push(setTimeout(function() {
                        if (window.__mbtaReqId !== reqId) return;
                        function findSendBtn() {
                            var sendSelectors = [
                                'button[aria-label="메시지 보내기"]',
                                'button[aria-label="Send message"]',
                                'button[data-testid="send-button"]',
                                'button.send-button',
                                'button[aria-label*="send" i]',
                                'button[aria-label*="submit" i]',
                                'button[aria-label*="run" i]',
                                'button[aria-label*="전송"]',
                                'button[aria-label*="보내기"]',
                                'button[type="submit"]'
                            ];
                            var excludeRe = /내보내기|공유|export|share|stop|중지|취소|cancel|opus|sonnet|haiku|fable|flash|\bpro\b|모델/i;
                            var exactSendSelectors = ['button[aria-label="메시지 보내기"]', 'button[aria-label="Send message"]'];
                            var sb = null;
                            // 1) 정확 일치 셀렉터: 요소가 있으면 가시성·화면 위치와 무관하게 첫 요소 선택 (disabled 여부는 trySend 에서 판단)
                            for (var ei = 0; ei < exactSendSelectors.length && !sb; ei++) {
                                var exEls = [];
                                try { exEls = queryAllDeep(exactSendSelectors[ei]); } catch(e) { exEls = []; }
                                if (exEls && exEls.length > 0 && exEls[0]) sb = exEls[0];
                            }
                            // 2) 그 외 셀렉터: 가시 + 화면 안 우선, 없으면 제외 패턴에 안 걸리는 첫 후보(가시성 무관)를 폴백
                            var fallbackCand = null;
                            for (var si = 0; si < sendSelectors.length && !sb; si++) {
                                if (exactSendSelectors.indexOf(sendSelectors[si]) >= 0) continue;
                                var sEls = [];
                                try { sEls = queryAllDeep(sendSelectors[si]); } catch(e) { sEls = []; }
                                for (var se = 0; se < sEls.length; se++) {
                                    var cand = sEls[se];
                                    if (!cand) continue;
                                    var candLabel = (cand.getAttribute('aria-label') || '') + ' ' + (cand.innerText || '');
                                    if (/mic|voice|마이크|음성/i.test(candLabel)) continue;
                                    if (excludeRe.test(candLabel)) continue;
                                    if (!fallbackCand) fallbackCand = cand;
                                    if (!isVisible(cand)) continue;
                                    var cr = cand.getBoundingClientRect();
                                    if (!(cr.bottom > 0 && cr.top < innerHeight)) continue;
                                    sb = cand;
                                    break;
                                }
                            }
                            if (!sb && fallbackCand) sb = fallbackCand;
                            if (!sb) {
                                var scopeEl = (inputEl.closest && inputEl.closest('form')) || (inputEl.parentElement && inputEl.parentElement.parentElement && inputEl.parentElement.parentElement.parentElement);
                                if (scopeEl && scopeEl.querySelectorAll) {
                                    var btns = scopeEl.querySelectorAll('button');
                                    // 입력창 주변 버튼: 가시 버튼 우선, 없으면 가시성 무관 첫 비제외 버튼
                                    for (var pass = 0; pass < 2 && !sb; pass++) {
                                        for (var b = btns.length - 1; b >= 0; b--) {
                                            var btn = btns[b];
                                            if (!btn || btn.disabled) continue;
                                            if (pass === 0 && !isVisible(btn)) continue;
                                            var bLabel = ((btn.getAttribute('aria-label') || '') + ' ' + (btn.innerText || '')).toLowerCase();
                                            if (/attach|upload|mic|voice|record|plus|add|menu|model|gemini|claude|마이크|음성|첨부|업로드|추가|도구|모델|내보내기|공유|export|share|stop|중지|취소|cancel|opus|sonnet|haiku|fable|flash|\bpro\b/.test(bLabel)) continue;
                                            sb = btn;
                                            break;
                                        }
                                    }
                                }
                            }
                            return sb;
                        }
                        function fireEnter(el) {
                            var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
                            el.dispatchEvent(new KeyboardEvent('keydown', opts));
                            el.dispatchEvent(new KeyboardEvent('keypress', opts));
                            el.dispatchEvent(new KeyboardEvent('keyup', opts));
                        }
                        function inputRemaining() {
                            return ((inputEl.value !== undefined ? inputEl.value : inputEl.innerText) || '').trim().length > 0;
                        }
                        // 프레임워크가 전송 버튼을 활성화할 때까지 기다리며 여러 번 시도 (Gemini 등은 입력 반영이 늦음)
                        var sendAttempts = 0;
                        var lastSendAction = 'none';
                        var lastClickAt = 0;
                        function describeBtn(b) {
                            try {
                                return '[' + (b.getAttribute('aria-label') || '').slice(0, 20) + '|' + (b.innerText || '').trim().slice(0, 12) + '|dis=' + (b.disabled ? 1 : 0) + '|ariaDis=' + (b.getAttribute('aria-disabled') || '-') + '|vis=' + (isVisible(b) ? 1 : 0) + ']';
                            } catch(e) { return '[?]'; }
                        }
                        // 전송 실패 진단: 입력창 글자 수, 마지막 전송 동작, 입력창 주변 버튼 목록
                        function sendDiag() {
                            var info = 'input=' + (((inputEl.value !== undefined ? inputEl.value : inputEl.innerText) || '').trim().length) + '/' + promptText.length + ', last=' + lastSendAction + ', attempts=' + sendAttempts;
                            try {
                                var sc = (inputEl.closest && inputEl.closest('form')) || (inputEl.parentElement && inputEl.parentElement.parentElement && inputEl.parentElement.parentElement.parentElement && inputEl.parentElement.parentElement.parentElement.parentElement);
                                if (sc && sc.querySelectorAll) {
                                    var bl = sc.querySelectorAll('button');
                                    var parts = [];
                                    for (var bi = 0; bi < bl.length && bi < 10; bi++) parts.push(describeBtn(bl[bi]));
                                    info += ', buttons=' + parts.join('');
                                }
                            } catch(e) {}
                            return info;
                        }
                        var firstSendAt = 0;
                        function trySend() {
                            sendAttempts++;
                            if (!firstSendAt) firstSendAt = Date.now();
                            var sb = findSendBtn();
                            var enabled = !!sb && !sb.disabled && sb.getAttribute('aria-disabled') !== 'true';
                            if (enabled && (lastClickAt === 0 || Date.now() - lastClickAt >= 1500)) {
                                // 클릭 후 1.5초 안에는 다시 누르지 않음. 그래도 입력이 남아 있으면 다음 시도에서 다시 클릭
                                lastClickAt = Date.now();
                                lastSendAction = 'click#' + sendAttempts + ':' + describeBtn(sb);
                                try { sb.click(); } catch(e) {}
                            } else if (!enabled && sendAttempts >= 7 && (sendAttempts - 7) % 4 === 0) {
                                // 버튼이 없거나 비활성인 상태가 이어지면 7회차부터 Enter를 보내되, 과다 입력을 막기 위해 4회마다 한 번만
                                lastSendAction = 'enter#' + sendAttempts + (sb ? ' (btn disabled:' + describeBtn(sb) + ')' : ' (no btn)');
                                fireEnter(inputEl);
                            } else {
                                lastSendAction = 'wait#' + sendAttempts + (sb ? ' (btn ' + (enabled ? 'cooldown' : 'disabled') + ':' + describeBtn(sb) + ')' : ' (no btn)');
                            }
                            var retryTimer = setTimeout(function() {
                                if (window.__mbtaReqId !== reqId) return;
                                // 첫 시도 후 30초(500ms 간격 약 60회) 안에서만 재시도
                                if (Date.now() - firstSendAt < 30000 && inputRemaining()) trySend();
                            }, 500);
                            window.__mbtaTimers.push(retryTimer);
                        }
                        trySend();

                        // 화면 안에 떠 있고 마지막 사용자 메시지 이후에 있는 '건너뛰기' 버튼만 질문 카드로 인정 (옛 대화 내용 오탐 방지)
                        function liveSkipOnScreen() {
                            var anchor = null;
                            var uSels = isCodeWeb ? ['[data-testid="user-message"]'] : ['user-query', '[data-message-author-role="user"]', '[data-testid="user-message"]'];
                            for (var ui = 0; ui < uSels.length; ui++) {
                                var uEls = [];
                                try { uEls = queryAllDeep(uSels[ui]); } catch(e) { uEls = []; }
                                for (var uj = 0; uj < uEls.length; uj++) {
                                    var uc = uEls[uj];
                                    if (uc && (!anchor || (anchor.compareDocumentPosition(uc) & Node.DOCUMENT_POSITION_FOLLOWING))) anchor = uc;
                                }
                            }
                            var sbs = document.querySelectorAll('button, [role="button"]');
                            for (var si2 = 0; si2 < sbs.length; si2++) {
                                var sk = sbs[si2];
                                if (!/^(건너뛰기|skip)$/i.test((sk.innerText || '').trim())) continue;
                                if (!isVisible(sk)) continue;
                                var skr = sk.getBoundingClientRect();
                                if (!(skr.bottom > 0 && skr.top < innerHeight)) continue;
                                if (!anchor || (anchor.compareDocumentPosition(sk) & Node.DOCUMENT_POSITION_FOLLOWING)) return true;
                            }
                            return false;
                        }

                        var lastText = '';
                        // 틱 수 대신 실제 경과 시간으로 판정 (백그라운드에서 타이머가 늦게 돌아도 시간 기준은 유지)
                        var startAt = Date.now();
                        var lastActiveAt = startAt;
                        var lastActivityAt = startAt;
                        var stableSince = startAt;
                        // 변화 감지는 textContent 길이로 (innerText 와 달리 화면 배치 계산이 필요 없음)
                        function bodySize() { return (document.body && document.body.textContent) ? document.body.textContent.length : 0; }
                        var lastBodyLen = bodySize();
                        var STOP_SEL = 'button[aria-label*="Stop"], button[aria-label*="중지"], button[data-testid="stop-button"], [data-is-streaming="true"]';
                        var timer = null, observer = null, confirmTimer = null, finished = false;
                        function stopWatch() {
                            finished = true;
                            clearInterval(timer);
                            clearTimeout(confirmTimer);
                            if (observer) observer.disconnect();
                            if (window.__mbtaSetInterval === setCheckInterval) window.__mbtaSetInterval = null;
                        }
                        function setCheckInterval(ms) {
                            if (finished) return;
                            clearInterval(timer);
                            timer = setInterval(check, ms);
                            window.__mbtaTimers.push(timer);
                        }
                        // 일반 채팅(ChatGPT/Claude/Gemini)은 셀렉터만 사용, 그 외(Claude Code 웹 등)는 화면 텍스트 증가분도 보조로 사용
                        var useBodyFallback = (!isCodeWeb && kind !== 'CHATGPT_WEB' && kind !== 'CLAUDE_WEB' && kind !== 'GEMINI_WEB');
                        function check() {
                            if (finished) return;
                            if (window.__mbtaReqId !== reqId) { stopWatch(); return; }
                            var now = Date.now();
                            var bodyNow = bodySize();
                            var activity = bodyNow !== lastBodyLen;
                            lastBodyLen = bodyNow;
                            var streamingNow = !!document.querySelector(STOP_SEL);
                            // 질문 카드(건너뛰기 버튼)가 떠 있으면 사용자 답을 기다리는 중이므로 시간 초과/완료로 보지 않음
                            if (!streamingNow && liveSkipOnScreen()) streamingNow = true;
                            if (!streamingNow && isCodeWeb) {
                                // Claude Code 웹의 '작업 중' 줄에 글자가 있으면 생성 중 (화면 변화 없이 10분(500ms 틱 1200회)이 지나면 무시)
                                var wl = document.querySelector('[data-testid="code-working-line"]');
                                var working = !!(wl && (wl.innerText || '').trim());
                                // 마지막 화면 변화 시각 기준: 화면 변화 없이 10분이 지나면 '작업 중' 줄이 있어도 생성 중으로 보지 않음
                                if (activity || !working) lastActivityAt = now;
                                streamingNow = working && (now - lastActivityAt) < 600000;
                            }
                            if (activity || streamingNow) lastActiveAt = now;
                            // 화면이 계속 변하거나 생성 중이면 오래 걸려도 계속 기다림. 3분간 변화가 없거나 90분이 넘으면 종료
                            if (now - startAt > 90 * 60 * 1000 || now - lastActiveAt > 180 * 1000) {
                                stopWatch();
                                if (lastText) {
                                    var doneText = isCodeWeb ? lastText + '\n\n(⚠️ 응답 대기 시간 초과 — 일부만 수신되었을 수 있음)' : lastText;
                                    window.MbtABridge.onDone(reqId, doneText);
                                } else {
                                    window.MbtABridge.onError(reqId, 'TIMEOUT', '응답 시간이 초과되었습니다. [전송 진단 ' + sendDiag() + ']');
                                }
                                return;
                            }

                            var cur = getLatestMsgText();
                            var candidate = '';
                            if (cur.count > before.count || (cur.text && cur.text !== before.text)) {
                                candidate = cur.text;
                            }
                            if (!candidate && (cur.count === 0 || useBodyFallback) && document.body) {
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
                                stableSince = now;
                                if (window.MbtABridge) window.MbtABridge.onChunk(reqId, lastText);
                            } else if (lastText) {
                                if (!streamingNow && !activity) {
                                    // 마지막 텍스트 변화 후 2.5초 경과하면 완료
                                    if (now - stableSince >= 2500) {
                                        stopWatch();
                                        if (window.MbtABridge) window.MbtABridge.onDone(reqId, lastText);
                                    }
                                } else {
                                    stableSince = now;
                                }
                            }
                        }
                        window.__mbtaSetInterval = setCheckInterval;
                        setCheckInterval(checkMs);
                        // 생성이 끝나는 순간(중지 버튼이 사라짐)은 확인 간격을 기다리지 않고 바로 확인
                        var wasStreaming = false, lastProbeAt = 0;
                        function confirmDone(left) {
                            clearTimeout(confirmTimer);
                            confirmTimer = setTimeout(function() {
                                check();
                                if (!finished && left > 1) confirmDone(left - 1);
                            }, 2700);
                            window.__mbtaTimers.push(confirmTimer);
                        }
                        try {
                            observer = new MutationObserver(function() {
                                if (finished) return;
                                if (window.__mbtaReqId !== reqId) { stopWatch(); return; }
                                var t = Date.now();
                                if (t - lastProbeAt < 500) return;
                                lastProbeAt = t;
                                var s = !!document.querySelector(STOP_SEL);
                                if (wasStreaming && !s) { check(); confirmDone(2); }
                                wasStreaming = s;
                            });
                            observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-label', 'data-is-streaming'] });
                        } catch(e) {}
                    }, proceedDelay));
                }

                var waitTries = 0;
                var waitTimer = setInterval(function() {
                    if (window.__mbtaReqId !== reqId) { clearInterval(waitTimer); return; }
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
                window.__mbtaTimers.push(waitTimer);
            })();
        """.trimIndent()
    }

    companion object {
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        /** React/Radix 메뉴는 click만으로는 안 열리는 경우가 있어 포인터/마우스 이벤트 전체를 발생시킴 */
        private const val FULL_CLICK_JS = """
            // 메뉴를 열기 전에 화면에 보이던 요소를 기억해 두었다가, 열린 뒤 새로 보이는 요소만 메뉴 항목 후보로 사용
            // (대화 내용에 "opus", "높음" 같은 글자가 있어도 그걸 누르지 않도록)
            function isShown(el) {
                try { return !!(el.offsetParent || (el.getClientRects && el.getClientRects().length)); } catch (e) { return true; }
            }
            function snapshotVisible() {
                var set = new Set();
                var all = queryAllDeep('*');
                for (var i = 0; i < all.length; i++) { if (isShown(all[i])) set.add(all[i]); }
                return set;
            }
            function fullClick(el) {
                try {
                    el.scrollIntoView({ block: 'center' });
                    var r = el.getBoundingClientRect();
                    var x = r.left + r.width / 2, y = r.top + r.height / 2;
                    var o = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0, buttons: 1, pointerId: 1, pointerType: 'mouse', isPrimary: true };
                    ['pointerover', 'pointerenter', 'mouseover', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(t) {
                        var ev;
                        try { ev = (t.indexOf('pointer') === 0) ? new PointerEvent(t, o) : new MouseEvent(t, o); } catch (e) { ev = new MouseEvent(t === 'click' ? 'click' : t.replace('pointer', 'mouse'), o); }
                        el.dispatchEvent(ev);
                    });
                } catch (e) { try { el.click(); } catch (e2) {} }
            }
        """

        /** claude.ai/code 대화 행의 텍스트 추출: 코드 블록(pre)을 ```로 감싸 포함하고 shadow DOM까지 읽음 (innerText는 둘 다 놓칠 수 있음) */
        private const val ROW_TEXT_JS = """
            var ROW_SKIP_TESTIDS = { 'message-actions': 1, 'epitaxy-read-aloud': 1, 'user-message-actions': 1, 'user-message-copy': 1, 'code-working-line': 1, 'response-status-announcer': 1, 'jump-to-latest': 1, 'user-message': 1 };
            function rowWalk(node, out, inPre) {
                if (node.nodeType === 3) {
                    var v = node.nodeValue || '';
                    if (!inPre) v = v.replace(/\s+/g, ' ');
                    out.push(v);
                    return;
                }
                if (node.nodeType !== 1) return;
                var tag = (node.tagName || '').toLowerCase();
                if (tag === 'script' || tag === 'style' || tag === 'svg' || tag === 'button' || tag === 'noscript') return;
                var tid = node.getAttribute ? node.getAttribute('data-testid') : null;
                if (tid && ROW_SKIP_TESTIDS[tid]) return;
                if (node.getAttribute && node.getAttribute('role') === 'button') return;
                var st = null;
                try { st = window.getComputedStyle(node); } catch (e) {}
                if (st && (st.display === 'none' || st.visibility === 'hidden')) return;
                if (tag === 'br') { out.push('\n'); return; }
                var isPre = (tag === 'pre');
                var block = st ? (st.display !== 'inline' && st.display !== 'inline-block' && st.display !== 'contents')
                               : /^(div|p|pre|li|ul|ol|h[1-6]|table|tr|blockquote)$/.test(tag);
                var hardBlock = /^(p|h[1-6]|ul|ol|blockquote|table)$/.test(tag);
                var BRK = hardBlock ? '\u0002' : '\u0001';
                if (isPre) out.push('\n```\n'); else if (block) out.push(BRK);
                var kids = node.shadowRoot ? node.shadowRoot.childNodes : node.childNodes;
                for (var i = 0; i < kids.length; i++) rowWalk(kids[i], out, inPre || isPre);
                if (isPre) out.push('\n```\n'); else if (block) out.push(BRK);
            }
            function rowText(row) {
                var out = [];
                rowWalk(row, out, false);
                // 블록 경계: 중첩 div가 빈 줄을 만들지 않도록 연속 경계는 하나로 합침 (문단/제목/목록만 빈 줄 유지)
                var t = out.join('')
                    .replace(/([\u0001\u0002])[ \t]+/g, '$1').replace(/[ \t]+([\u0001\u0002])/g, '$1')
                    .replace(/[\u0001\u0002]*\u0002[\u0001\u0002]*/g, '\n\n')
                    .replace(/\u0001+/g, '\n')
                    .replace(/[ \t]+\n/g, '\n')
                    .replace(/\n[ \t]+/g, '\n')
                    .replace(/\n{3,}/g, '\n\n')
                    .replace(/\n(Text|text|plaintext|bash|shell|sh|json|kotlin|kt|python|py|js|javascript|ts|typescript|html|css|xml|yaml|yml|java|sql|diff|markdown|md)[ \t]*\n+```/g, '\n```')
                    .trim();
                return t.replace(/^Claude\s*(응답|said|responded)\s*[:：]\s*/i, '');
            }
        """

        /** 모델/effort 선택 버튼 찾기: 입력창 근처의 '모델명+숫자' 버튼 우선 (사이드바의 'Claude' 같은 버튼을 잘못 고르지 않도록) */
        private const val PICKER_FIND_JS = """
            function pickerLabel(b) { return ((b.innerText || '') + ' ' + (b.getAttribute('aria-label') || '')).trim(); }
            var VNUM = /(opus|sonnet|haiku|fable|gemini|gpt|neon|hydrogen)[^a-z0-9]{0,3}\d/i;
            function findPicker() {
                var inputs = queryAllDeep('[contenteditable="true"], textarea, [role="textbox"]');
                for (var i = 0; i < inputs.length; i++) {
                    var p = inputs[i];
                    for (var up = 0; up < 7 && p; up++) {
                        p = p.parentElement;
                        if (!p) break;
                        var bs = p.querySelectorAll('button, [role="button"], [aria-haspopup]');
                        for (var j = 0; j < bs.length; j++) { if (VNUM.test(pickerLabel(bs[j]))) return bs[j]; }
                    }
                }
                var all = queryAllDeep('button, [role="button"], [aria-haspopup]');
                var last = null;
                for (var k = 0; k < all.length; k++) { if (VNUM.test(pickerLabel(all[k]))) last = all[k]; }
                if (last) return last;
                for (var m = 0; m < all.length; m++) {
                    if (/gemini|claude|gpt|sonnet|opus|haiku|neon|hydrogen|model|모델/i.test(pickerLabel(all[m]))) return all[m];
                }
                return null;
            }
        """

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
