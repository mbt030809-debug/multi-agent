package com.mbta.app.data

import android.content.Context
import com.mbta.app.model.AgentSession
import com.mbta.app.model.AgentTokenUsage
import com.mbta.app.model.AiConnection
import com.mbta.app.model.AppThemeMode
import com.mbta.app.model.Project
import com.mbta.app.model.SplitMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkspaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("mbta_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadConnections(): List<AiConnection> {
        val raw = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<AiConnection>>(raw) }
            .getOrElse { emptyList() }
    }

    fun saveConnections(connections: List<AiConnection>) {
        prefs.edit().putString(KEY_CONNECTIONS, json.encodeToString(connections)).apply()
    }

    fun loadProjects(): List<Project> {
        val raw = prefs.getString(KEY_PROJECTS, null) ?: return defaultProjects
        return runCatching { json.decodeFromString<List<Project>>(raw) }
            .getOrElse { defaultProjects }
            .ifEmpty { defaultProjects }
    }

    fun saveProjects(projects: List<Project>) {
        prefs.edit().putString(KEY_PROJECTS, json.encodeToString(projects)).apply()
    }

    fun loadSessions(): List<AgentSession> {
        val raw = prefs.getString(KEY_SESSIONS, null) ?: return defaultSessions
        return runCatching { json.decodeFromString<List<AgentSession>>(raw) }
            .getOrElse { defaultSessions }
            .ifEmpty { defaultSessions }
    }

    fun saveSessions(sessions: List<AgentSession>) {
        prefs.edit().putString(KEY_SESSIONS, json.encodeToString(sessions)).apply()
    }

    fun loadActiveProjectId(): String =
        prefs.getString(KEY_ACTIVE_PROJECT, "proj_default") ?: "proj_default"

    fun saveActiveProjectId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROJECT, id).apply()
    }

    fun loadActiveSessionId(): String =
        prefs.getString(KEY_ACTIVE_SESSION, "sess_default") ?: "sess_default"

    fun saveActiveSessionId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_SESSION, id).apply()
    }

    fun loadSecondarySessionId(): String? =
        prefs.getString(KEY_SECONDARY_SESSION, null)

    fun saveSecondarySessionId(id: String?) {
        prefs.edit().putString(KEY_SECONDARY_SESSION, id).apply()
    }

    fun loadSplitMode(): SplitMode {
        val raw = prefs.getString(KEY_SPLIT_MODE, SplitMode.SINGLE.name) ?: SplitMode.SINGLE.name
        return runCatching { SplitMode.valueOf(raw) }.getOrElse { SplitMode.SINGLE }
    }

    fun saveSplitMode(mode: SplitMode) {
        prefs.edit().putString(KEY_SPLIT_MODE, mode.name).apply()
    }

    fun loadTokenUsage(): Map<String, AgentTokenUsage> {
        val raw = prefs.getString(KEY_TOKEN_USAGE, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, AgentTokenUsage>>(raw) }
            .getOrElse { emptyMap() }
    }

    fun saveTokenUsage(map: Map<String, AgentTokenUsage>) {
        prefs.edit().putString(KEY_TOKEN_USAGE, json.encodeToString(map)).apply()
    }

    fun loadThemeMode(): AppThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
        return runCatching { AppThemeMode.valueOf(raw) }.getOrElse { AppThemeMode.DARK }
    }

    fun saveThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    companion object {
        private val defaultProjects = listOf(Project(id = "proj_default", name = "기본 프로젝트"))
        private val defaultSessions = listOf(
            AgentSession(
                id = "sess_default",
                projectId = "proj_default",
                title = "세션 1",
                connectionId = null
            )
        )

        private const val KEY_CONNECTIONS = "connections"
        private const val KEY_PROJECTS = "projects"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_ACTIVE_PROJECT = "active_project_id"
        private const val KEY_ACTIVE_SESSION = "active_session_id"
        private const val KEY_SECONDARY_SESSION = "secondary_session_id"
        private const val KEY_SPLIT_MODE = "split_mode"
        private const val KEY_TOKEN_USAGE = "token_usage"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
