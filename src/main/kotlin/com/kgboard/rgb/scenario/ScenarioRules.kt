package com.kgboard.rgb.scenario

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.target.*
import java.awt.Color

data class RuleRecord(
    var id: String = "", var enabled: Boolean = true,
    var target: TargetRecord = TargetRecord(), var color: String = "#FFFFFF",
    var effect: String = "STATIC", var durationMs: Long = 3000, var periodMs: Long = 1000
) {
    companion object {
        fun from(id: String, rule: RuleAppearance) = RuleRecord(
            id, rule.enabled, TargetRecord.from(rule.target),
            "#%02X%02X%02X".format(rule.style.color.red, rule.style.color.green, rule.style.color.blue),
            rule.style.effect.name, rule.style.durationMs, rule.style.periodMs
        )
    }
}

data class StoredTarget(
    val kind: String, val names: List<String>, val groupId: String,
    val raw: String, val fingerprint: String?
) {
    fun toRecord() = TargetRecord(kind, names.toMutableList(), groupId, raw, fingerprint)

    companion object {
        fun capture(record: TargetRecord) = with(record) {
            StoredTarget(kind, frozenList(names), groupId, raw, fingerprint)
        }
    }
}

data class StoredRule(
    val id: String, val enabled: Boolean, val target: StoredTarget,
    val color: String, val effect: String, val durationMs: Long, val periodMs: Long
) {
    fun toRecord() = RuleRecord(id, enabled, target.toRecord(), color, effect, durationMs, periodMs)
    fun appearance(): RuleAppearance? {
        if (durationMs < 0 || periodMs <= 0) return null
        val resolvedTarget = target.toRecord().toSpec() ?: return null
        val style = runCatching { EffectStyle.valueOf(effect) }.getOrNull() ?: return null
        val rgb = runCatching { Color.decode(color) }.getOrNull() ?: return null
        return RuleAppearance(enabled, resolvedTarget, RuleStyle(rgb, style, durationMs, periodMs))
    }

    companion object {
        fun capture(record: RuleRecord) = with(record) {
            StoredRule(id, enabled, StoredTarget.capture(target), color, effect, durationMs, periodMs)
        }
    }
}

/** Unknown, unsupported and duplicate entries survive round-trip but never execute. */
data class ScenarioRules private constructor(val scope: SettingsScope, val entries: List<StoredRule>) {
    val executable: Map<String, RuleAppearance> = run {
        val counts = entries.groupingBy { it.id }.eachCount()
        frozenMap(entries.mapNotNull { record ->
            if (counts[record.id] != 1 || ScenarioCatalog.byId(record.id)?.scope != scope) null
            else record.appearance()?.let { record.id to it }
        }.toMap())
    }

    fun toRecords() = entries.map { it.toRecord() }.toMutableList()
    fun withEditedRules(edits: Map<String, RuleAppearance>): ScenarioRules {
        val seen = mutableSetOf<String>()
        val records = entries.mapNotNull { record ->
            val edit = edits[record.id]?.takeIf { ScenarioCatalog.byId(record.id)?.scope == scope }
            if (edit == null) record.toRecord()
            else if (seen.add(record.id)) RuleRecord.from(record.id, edit) else null
        }.toMutableList()
        edits.forEach { (id, rule) ->
            if (id !in seen && entries.none { it.id == id } && ScenarioCatalog.byId(id)?.scope == scope)
                records.add(RuleRecord.from(id, rule))
        }
        return capture(scope, records)
    }

    companion object {
        fun empty(scope: SettingsScope) = ScenarioRules(scope, emptyList())
        fun capture(scope: SettingsScope, records: Collection<RuleRecord>) =
            ScenarioRules(scope, frozenList(records.map(StoredRule::capture)))

        fun from(scope: SettingsScope, rules: Map<String, RuleAppearance>) =
            capture(scope, rules.map { RuleRecord.from(it.key, it.value) })
    }
}
