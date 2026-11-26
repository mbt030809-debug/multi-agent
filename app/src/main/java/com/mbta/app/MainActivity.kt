package com.mbta.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mbta.app.data.WorkspaceStore
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AppThemeMode
import com.mbta.app.model.ConnectionKind
import com.mbta.app.model.TokenSource
import com.mbta.app.model.defaultContextLimitForKind
import com.mbta.app.ui.MainScreen
import com.mbta.app.ui.theme.MbtATheme

class MainActivity : ComponentActivity() {

    private lateinit var store: WorkspaceStore
    private val tokenUsageMap = mutableStateMapOf<String, AgentTokenUsage>()
    private var themeMode by mutableStateOf(AppThemeMode.DARK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = WorkspaceStore(this)
        themeMode = store.loadThemeMode()
        tokenUsageMap.putAll(store.loadTokenUsage())

        setContent {
            MbtATheme(themeMode = themeMode) {
                MainScreen(
                    store = store,
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
                    }
                )
            }
        }
    }
}
