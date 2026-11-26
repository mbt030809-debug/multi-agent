package com.mbta.app

import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateMapOf
import com.mbta.app.data.WorkspaceStore
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.defaultContextLimit
import com.mbta.app.ui.MainScreen
import com.mbta.app.ui.theme.MbtATheme
import com.mbta.app.webview.WebViewManager

class MainActivity : ComponentActivity() {

    private lateinit var store: WorkspaceStore
    private lateinit var webViewManager: WebViewManager
    private var focusedPanelId: String = "claude"
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val tokenUsageMap = mutableStateMapOf<String, AgentTokenUsage>()

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    Array(data.clipData!!.itemCount) { idx ->
                        data.clipData!!.getItemAt(idx).uri
                    }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = WorkspaceStore(this)
        tokenUsageMap.putAll(store.loadTokenUsage())
        webViewManager = WebViewManager(
            context = this,
            onFileChooserRequested = { intent, callback ->
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                fileChooserLauncher.launch(intent)
            },
            onTokenDelta = { panelId, inputDelta, outputDelta ->
                val current = tokenUsageMap[panelId] ?: AgentTokenUsage(
                    panelId = panelId,
                    contextLimit = defaultContextLimit(panelId)
                )
                val updated = current.copy(
                    inputTokens = current.inputTokens + inputDelta,
                    outputTokens = current.outputTokens + outputDelta,
                    updatedAt = System.currentTimeMillis()
                )
                tokenUsageMap[panelId] = updated
                store.saveTokenUsage(tokenUsageMap.toMap())
            }
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webViewManager.canGoBack(focusedPanelId)) {
                        webViewManager.goBack(focusedPanelId)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        setContent {
            MbtATheme {
                MainScreen(
                    store = store,
                    webViewManager = webViewManager,
                    tokenUsageMap = tokenUsageMap,
                    onResetAgentTokens = { panelId ->
                        tokenUsageMap.remove(panelId)
                        store.saveTokenUsage(tokenUsageMap.toMap())
                    },
                    onResetAllTokens = {
                        tokenUsageMap.clear()
                        store.saveTokenUsage(emptyMap())
                    },
                    onFocusedPanelChanged = { focusedPanelId = it }
                )
            }
        }
    }

    override fun onDestroy() {
        webViewManager.destroyAll()
        super.onDestroy()
    }
}
