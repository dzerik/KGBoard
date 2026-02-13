package com.kgboard.rgb.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Project-level settings for KGBoard.
 * Stores per-project color overrides, git indicator config, analysis config, etc.
 * Falls back to app-level KgBoardSettings when project-level values are not set.
 */
@Service(Service.Level.PROJECT)
@State(name = "KgBoardProjectSettings", storages = [Storage("kgboard-project.xml")])
class KgBoardProjectSettings : PersistentStateComponent<KgBoardProjectSettings.State> {

    data class GitColorRule(
        var pattern: String = ".*",
        var color: String = "#FFFFFF"
    )

    data class State(
        // Git indicator
        var gitEnabled: Boolean = true,
        var gitLedIndices: String = "", // comma-separated LED indices
        var gitUncommittedColor: String = "#FFD600",
        var gitConflictColor: String = "#FF1744",
        var gitUnpushedColor: String = "#2979FF",
        var gitCleanColor: String = "#00C853",
        var gitBranchColorRules: MutableList<GitColorRule> = mutableListOf(),
        var gitPollingIntervalSec: Int = 5,

        // Code analysis indicator
        var analysisEnabled: Boolean = true,
        var analysisLedIndices: String = "", // comma-separated LED indices
        var analysisErrorColor: String = "#FF1744",
        var analysisWarningColor: String = "#FFD600",
        var analysisCleanColor: String = "#00C853",

        // Shortcut highlight
        var shortcutHighlightEnabled: Boolean = false,
        var shortcutContexts: MutableList<String> = mutableListOf("debug", "search"),
        var shortcutDebugColor: String = "#651FFF",
        var shortcutSearchColor: String = "#2979FF",
        var shortcutEditingColor: String = "#00C853",
        var shortcutVcsColor: String = "#FF9100"
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    val gitEnabled: Boolean get() = state.gitEnabled
    val gitLedIndices: List<Int>
        get() = state.gitLedIndices.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }

    val gitUncommittedColor: String get() = state.gitUncommittedColor
    val gitConflictColor: String get() = state.gitConflictColor
    val gitUnpushedColor: String get() = state.gitUnpushedColor
    val gitCleanColor: String get() = state.gitCleanColor
    val gitPollingIntervalSec: Int get() = state.gitPollingIntervalSec

    val analysisEnabled: Boolean get() = state.analysisEnabled
    val analysisLedIndices: List<Int>
        get() = state.analysisLedIndices.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }

    val analysisErrorColor: String get() = state.analysisErrorColor
    val analysisWarningColor: String get() = state.analysisWarningColor
    val analysisCleanColor: String get() = state.analysisCleanColor

    val shortcutHighlightEnabled: Boolean get() = state.shortcutHighlightEnabled
    val shortcutContexts: List<String> get() = state.shortcutContexts
    val shortcutDebugColor: String get() = state.shortcutDebugColor
    val shortcutSearchColor: String get() = state.shortcutSearchColor
    val shortcutEditingColor: String get() = state.shortcutEditingColor
    val shortcutVcsColor: String get() = state.shortcutVcsColor

    companion object {
        fun getInstance(project: Project): KgBoardProjectSettings =
            project.getService(KgBoardProjectSettings::class.java)
    }
}
