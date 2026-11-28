package com.mbta.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mbta.app.data.WorkspaceStore
import com.mbta.app.service.AgentKeepAliveService
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AppThemeMode
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.TokenSource
import com.mbta.app.model.defaultContextLimitForKind
import com.mbta.app.ui.MainScreen
import com.mbta.app.ui.theme.MbtATheme
import com.mbta.app.util.NotificationHelper
import com.mbta.app.webview.HeadlessWebBridge

class MainActivity : ComponentActivity() {

    private lateinit var store: WorkspaceStore
    private lateinit var headlessWebBridge: HeadlessWebBridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val tokenUsageMap = mutableStateMapOf<String, AgentTokenUsage>()
    private var themeMode by mutableStateOf(AppThemeMode.DARK)
    private var isAppInForeground = true
    private var pendingNotifSessionId by mutableStateOf<String?>(null)
    private var pendingNotifReply by mutableStateOf<Pair<String, String>?>(null)

    private fun handleNotificationIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)
        if (notifId > 0) NotificationHelper.cancel(this, notifId)
        val sid = intent.getStringExtra(NotificationHelper.EXTRA_SESSION_ID)
        val quickReply = intent.getStringExtra(NotificationHelper.EXTRA_QUICK_REPLY)
        if (!sid.isNullOrBlank()) {
            pendingNotifSessionId = sid
            if (!quickReply.isNullOrBlank()) {
                pendingNotifReply = sid to quickReply
            }
            intent.removeExtra(NotificationHelper.EXTRA_SESSION_ID)
            intent.removeExtra(NotificationHelper.EXTRA_QUICK_REPLY)
            intent.removeExtra(NotificationHelper.EXTRA_NOTIF_ID)
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        store = WorkspaceStore(this)
        handleNotificationIntent(intent)
        themeMode = store.loadThemeMode()
        tokenUsageMap.putAll(store.loadTokenUsage())
        headlessWebBridge = HeadlessWebBridge(
            context = this,
            onFileChooserRequested = { intent, callback ->
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                fileChooserLauncher.launch(intent)
            }
        )

        setContent {
            MbtATheme(themeMode = themeMode) {
                MainScreen(
                    store = store,
                    headlessWebBridge = headlessWebBridge,
                    themeMode = themeMode,
                    tokenUsageMap = tokenUsageMap,
                    onThemeModeChanged = { newMode ->
                        themeMode = newMode
                        store.saveThemeMode(newMode)
                    },
                    onRecordTokens = { connectionId, kind, inTok, outTok ->
                        val current = tokenUsageMap[connectionId] ?: AgentTokenUsage(
                            panelId = connectionId,
                            contextLimit = defaultContextLimitForKind(kind),
                            source = if (kind == ConnectionKind.TERMUX_CLI || kind == ConnectionKind.REMOTE_CONTROL) {
                                TokenSource.CLI_BRIDGE
                            } else {
                                TokenSource.API_EXACT
                            }
                        )
                        val updated = current.copy(
                            inputTokens = current.inputTokens + inTok,
                            outputTokens = current.outputTokens + outTok,
                            updatedAt = System.currentTimeMillis()
                        )
                        tokenUsageMap[connectionId] = updated
                        store.saveTokenUsage(tokenUsageMap.toMap())
                    },
                    onResetAgentTokens = { connectionId ->
                        tokenUsageMap.remove(connectionId)
                        store.saveTokenUsage(tokenUsageMap.toMap())
                    },
                    onResetAllTokens = {
                        tokenUsageMap.clear()
                        store.saveTokenUsage(emptyMap())
                    },
                    onNotifyIfBackground = { title, body ->
                        if (!isAppInForeground) {
                            NotificationHelper.notifyDone(this, title, body)
                        }
                    },
                    onNotifyEvent = { sessionId, title, body, isUrgent, actions ->
                        if (!isAppInForeground) {
                            NotificationHelper.notifyEvent(this, sessionId, title, body, isUrgent, actions)
                        }
                    },
                    pendingNotifSessionId = pendingNotifSessionId,
                    pendingNotifReply = pendingNotifReply,
                    onConsumeNotifAction = {
                        pendingNotifSessionId = null
                        pendingNotifReply = null
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        headlessWebBridge.resumeAll()
    }

    override fun onPause() {
        isAppInForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            AgentKeepAliveService.stop(this)
        }
        headlessWebBridge.destroyAll()
        super.onDestroy()
    }
}
