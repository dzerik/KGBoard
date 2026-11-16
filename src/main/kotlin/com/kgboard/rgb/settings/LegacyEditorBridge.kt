package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.*

/** Transitional legacy forms change only the appearance components actually edited by the user. */
internal object LegacyEditorBridge {
    private fun merge(actual: ScenarioRules, current: ScenarioRules, baseline: ScenarioRules): ScenarioRules {
        val edits = actual.executable.mapValues { (id, rule) ->
            val after = current.executable[id] ?: return@mapValues rule
            val before = baseline.executable[id] ?: return@mapValues rule
            rule.copy(
                enabled = if (after.enabled != before.enabled) after.enabled else rule.enabled,
                target = if (after.target != before.target) after.target else rule.target,
                style = rule.style.copy(
                    color = if (after.style.color != before.style.color) after.style.color else rule.style.color,
                    effect = if (after.style.effect != before.style.effect) after.style.effect else rule.style.effect,
                    durationMs = if (after.style.durationMs != before.style.durationMs) after.style.durationMs else rule.style.durationMs,
                    periodMs = if (after.style.periodMs != before.style.periodMs) after.style.periodMs else rule.style.periodMs
                )
            )
        }
        return actual.withEditedRules(edits)
    }

    fun app(draft: KgBoardSettings.State, baseline: KgBoardSettings.State): KgBoardSettings.State {
        val migrated = RuntimeSettings.capture(SettingsMigration.app(draft))
        return migrated.copy(
            ruleRecords = merge(
                migrated.ruleRecords,
                LegacyScenarioRules.app(RuntimeSettings.capture(draft)),
                LegacyScenarioRules.app(RuntimeSettings.capture(baseline))
            )
        ).toState()
    }

    fun project(
        draft: KgBoardProjectSettings.State,
        baseline: KgBoardProjectSettings.State
    ): KgBoardProjectSettings.State {
        val migrated = ProjectSettingsSnapshot.capture(SettingsMigration.project(draft))
        return migrated.copy(
            ruleRecords = merge(
                migrated.ruleRecords,
                LegacyScenarioRules.project(ProjectSettingsSnapshot.capture(draft)),
                LegacyScenarioRules.project(ProjectSettingsSnapshot.capture(baseline))
            )
        ).toState()
    }
}
