package com.mbta.app.data

import android.content.Context
import com.mbta.app.model.AiPanel
import com.mbta.app.model.DefaultData
import com.mbta.app.model.SplitMode
import com.mbta.app.model.WorkspacePreset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkspaceStore(context: Context) {
    private val prefs = context.getSharedPreferences("mbta_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun loadPanels(): List<AiPanel> {
        val raw = prefs.getString(KEY_PANELS, null) ?: return DefaultData.defaultPanels
        return runCatching { json.decodeFromString<List<AiPanel>>(raw) }
            .getOrElse { DefaultData.defaultPanels }
            .ifEmpty { DefaultData.defaultPanels }
    }

    fun savePanels(panels: List<AiPanel>) {
        prefs.edit().putString(KEY_PANELS, json.encodeToString(panels)).apply()
    }

    fun loadPresets(): List<WorkspacePreset> {
        val raw = prefs.getString(KEY_PRESETS, null) ?: return DefaultData.defaultPresets
        return runCatching { json.decodeFromString<List<WorkspacePreset>>(raw) }
            .getOrElse { DefaultData.defaultPresets }
            .ifEmpty { DefaultData.defaultPresets }
    }

    fun savePresets(presets: List<WorkspacePreset>) {
        prefs.edit().putString(KEY_PRESETS, json.encodeToString(presets)).apply()
    }

    fun loadPrimaryPanelId(): String = prefs.getString(KEY_PRIMARY_PANEL, "claude") ?: "claude"

    fun savePrimaryPanelId(id: String) {
        prefs.edit().putString(KEY_PRIMARY_PANEL, id).apply()
    }

    fun loadSecondaryPanelId(): String = prefs.getString(KEY_SECONDARY_PANEL, "gemini") ?: "gemini"

    fun saveSecondaryPanelId(id: String) {
        prefs.edit().putString(KEY_SECONDARY_PANEL, id).apply()
    }

    fun loadSplitMode(): SplitMode {
        val raw = prefs.getString(KEY_SPLIT_MODE, SplitMode.SINGLE.name) ?: SplitMode.SINGLE.name
        return runCatching { SplitMode.valueOf(raw) }.getOrElse { SplitMode.SINGLE }
    }

    fun saveSplitMode(mode: SplitMode) {
        prefs.edit().putString(KEY_SPLIT_MODE, mode.name).apply()
    }

    companion object {
        private const val KEY_PANELS = "panels"
        private const val KEY_PRESETS = "presets"
        private const val KEY_PRIMARY_PANEL = "primary_panel_id"
        private const val KEY_SECONDARY_PANEL = "secondary_panel_id"
        private const val KEY_SPLIT_MODE = "split_mode"
    }
}
