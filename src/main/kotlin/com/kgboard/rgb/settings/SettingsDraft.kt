package com.kgboard.rgb.settings

import com.intellij.util.xmlb.XmlSerializerUtil
import com.kgboard.rgb.device.*
import com.kgboard.rgb.preset.KeyGroupService
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.*

/** Data only; reset preserves the containers bound by Swing controls. */
class AppDraft private constructor() {
    val state = KgBoardSettings.State()
    val rules = mutableMapOf<String, RuleAppearance>()
    val bindings = mutableListOf<ConfiguredBinding>()
    var groups = KeyGroupService.GroupState()
    fun reset(source: KgBoardSettings.State) {
        val migrated = SettingsMigration.app(source)
        XmlSerializerUtil.copyBean(migrated, state)
        val snapshot = RuntimeSettings.capture(migrated)
        rules.clear(); rules.putAll(snapshot.rules)
        bindings.clear(); bindings.addAll(snapshot.bindings)
    }

    fun toState(): KgBoardSettings.State {
        val snapshot = RuntimeSettings.capture(state)
        return snapshot.copy(
            ruleRecords = snapshot.ruleRecords.withEditedRules(rules),
            bindingRecords = snapshot.bindingRecords.withConfigurations(bindings)
        ).toState()
    }

    companion object {
        fun capture(source: KgBoardSettings.State) = AppDraft().also { it.reset(source) }
    }
}

class ProjectDraft private constructor() {
    val state = KgBoardProjectSettings.State()
    val rules = mutableMapOf<String, RuleAppearance>()
    fun reset(source: KgBoardProjectSettings.State) {
        val migrated = SettingsMigration.project(source)
        XmlSerializerUtil.copyBean(migrated, state)
        rules.clear(); rules.putAll(ProjectSettingsSnapshot.capture(migrated).rules)
    }

    fun toState(): KgBoardProjectSettings.State {
        val snapshot = ProjectSettingsSnapshot.capture(state)
        return snapshot.copy(ruleRecords = snapshot.ruleRecords.withEditedRules(rules)).toState()
    }

    companion object {
        fun capture(source: KgBoardProjectSettings.State) = ProjectDraft().also { it.reset(source) }
    }
}
