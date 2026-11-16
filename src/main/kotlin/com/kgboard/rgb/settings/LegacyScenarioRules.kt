package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.target.TargetSpec

/** The only mapping from legacy feature fields to independently configurable visual rules. */
internal object LegacyScenarioRules {
    private fun target(raw: String, blank: TargetSpec = TargetSpec.All): TargetSpec =
        if (raw.isBlank()) blank else TargetSpec.RawIndices(raw, null)

    fun app(s: RuntimeSettings): ScenarioRules {
        val rules = ScenarioCatalog.defaults(SettingsScope.APP).toMutableMap()
        fun set(
            id: String, color: String, goal: TargetSpec, enabled: Boolean? = null,
            duration: Long? = null, period: Long? = null
        ) {
            val before = rules.getValue(id)
            rules[id] = before.copy(
                enabled = enabled ?: before.enabled, target = goal,
                style = before.style.copy(
                    color = LightingPolicy.color(color),
                    durationMs = duration ?: before.style.durationMs, periodMs = period ?: before.style.periodMs
                )
            )
        }
        for (group in listOf("build", "test", "run", "debug")) {
            val goal = target(
                when (group) {
                    "build" -> s.buildLedIndices; "test" -> s.testLedIndices; else -> s.executionLedIndices
                }
            )
            val active = when (group) {
                "build" -> s.buildInProgressColor; "test" -> s.testRunningColor; "run" -> s.runColor; else -> s.debugColor
            }
            set(
                "$group.${if (group in listOf("build", "test")) "progress" else "active"}",
                active,
                goal,
                period = s.pulseSpeedMs
            )
            for (phase in listOf("success", "failure", "cancelled", "notExecuted")) {
                val color = when {
                    group == "build" && phase == "success" -> s.buildSuccessColor
                    group == "build" && phase == "failure" -> s.buildFailureColor
                    group == "test" && phase == "success" -> s.testPassColor
                    group == "test" && phase == "failure" -> s.testFailColor
                    else -> s.stopColor
                }
                set("$group.$phase", color, goal, duration = s.effectDurationMs, period = s.pulseSpeedMs)
            }
        }
        set(
            "debug.breakpoint",
            s.debugBreakpointFlashColor,
            target(s.debugBreakpointLedIndices),
            s.debugBreakpointFlashEnabled
        )
        set("fileSave", s.fileSaveFlashColor, target(s.fileSaveLedIndices), s.fileSaveFlashEnabled)
        set(
            "indexing",
            s.notifyIndexingColor,
            target(s.notifyIndexingLedIndices, TargetSpec.Unassigned),
            s.notifyIndexingEnabled
        )
        set("lowMemory", s.notifyLowMemoryColor, target(s.notifyLowMemoryLedIndices), s.notifyLowMemoryEnabled)
        set("todo", s.notifyTodoColor, target(s.notifyTodoLedIndices, TargetSpec.Unassigned), s.notifyTodoEnabled)
        for (phase in listOf("work", "break", "longBreak", "transition")) {
            val color = when (phase) {
                "work" -> s.pomodoroWorkColor; "transition" -> s.pomodoroTransitionColor; else -> s.pomodoroBreakColor
            }
            set("pomodoro.$phase", color, target(s.pomodoroLedIndices))
        }
        return ScenarioRules.from(SettingsScope.APP, rules)
    }

    fun project(s: ProjectSettingsSnapshot): ScenarioRules {
        val rules = ScenarioCatalog.defaults(SettingsScope.PROJECT).toMutableMap()
        fun set(id: String, color: String, goal: TargetSpec, enabled: Boolean) {
            val before = rules.getValue(id)
            rules[id] = before.copy(
                enabled = enabled,
                target = goal,
                style = before.style.copy(color = LightingPolicy.color(color))
            )
        }
        for (phase in listOf("conflict", "dirty", "ahead", "behind", "diverged", "synced")) {
            val color = when (phase) {
                "conflict" -> s.gitConflictColor; "dirty" -> s.gitUncommittedColor; "ahead" -> s.gitUnpushedColor
                "behind" -> "#00BCD4"; "diverged" -> "#FF9100"; else -> s.gitCleanColor
            }
            set("git.$phase", color, target(s.gitLedIndices, TargetSpec.Unassigned), s.gitEnabled)
        }
        set("git.branch", "#FFFFFF", TargetSpec.All, s.gitEnabled)
        for ((phase, color) in mapOf(
            "error" to s.analysisErrorColor,
            "warning" to s.analysisWarningColor,
            "clean" to s.analysisCleanColor
        ))
            set("analysis.$phase", color, target(s.analysisLedIndices, TargetSpec.Unassigned), s.analysisEnabled)
        for ((context, color) in mapOf(
            "debug" to s.shortcutDebugColor, "search" to s.shortcutSearchColor,
            "vcs" to s.shortcutVcsColor, "editing" to s.shortcutEditingColor
        ))
            set(
                "shortcuts.$context",
                color,
                TargetSpec.All,
                s.shortcutHighlightEnabled && context in s.shortcutContexts
            )
        return ScenarioRules.from(SettingsScope.PROJECT, rules)
    }
}
