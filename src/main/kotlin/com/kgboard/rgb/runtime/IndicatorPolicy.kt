package com.kgboard.rgb.runtime

import com.kgboard.rgb.effect.*
import com.kgboard.rgb.scenario.*
import java.awt.Color

class IndicatorPolicy {
    fun layers(state: RuntimeState, now: Long): List<EffectLayer> = buildList {
        val id = state.activeProject ?: return@buildList
        val project = state.projects[id] ?: return@buildList
        fun add(
            ruleId: String, at: Long = 0, expiry: Long? = null, color: Color? = null,
            codes: List<Int>? = null, indices: List<Int>? = null, mapping: DeviceMappingRevision? = null
        ) {
            if (expiry != null && expiry <= now) return
            val scope = ScenarioCatalog.byId(ruleId)?.scope ?: return
            val rule =
                (if (scope == SettingsScope.APP) state.settings.rules else project.settings.rules)[ruleId] ?: return
            val target = if (indices == null) EffectTarget.AllLeds else EffectTarget.LedSet(indices)
            add(
                EffectLayer(
                    EffectKey(id, ruleId), ScenarioEffects.create(ruleId, rule.style, target), at, expiry, ruleId,
                    colorOverride = color, hintCodes = codes, mapping = mapping
                )
            )
        }

        val analysis = project.editor.analysis as? AnalysisResult.Ready
        if (!project.indexing && analysis != null) add(
            "analysis.${
                when {
                    analysis.hasErrors -> "error"; analysis.hasWarnings -> "warning"; else -> "clean"
                }
            }"
        )
        if (project.editor.hasTodos == true) add("todo")
        if (project.indexing) add("indexing")
        if (project.git.available) {
            add(
                "git.${
                    when {
                        project.git.conflict -> "conflict"; project.git.uncommitted -> "dirty"; project.git.unpushed -> "ahead"; else -> "synced"
                    }
                }"
            )
            project.git.branch?.let { branch -> project.settings.gitBranchColorRules.firstOrNull { it.matches(branch) } }
                ?.let { add("git.branch", color = LightingPolicy.color(it.color)) }
        }
        for (context in project.shortcutContexts) {
            val codes = project.shortcutKeyCodes[context]
            if (!codes.isNullOrEmpty()) add("shortcuts.$context", codes = codes)
            else if (project.shortcutMapping != null && project.shortcutMapping == state.primaryMapping()) {
                val indices = project.shortcutIndices[context].orEmpty()
                if (indices.isNotEmpty()) add(
                    "shortcuts.$context",
                    indices = indices,
                    mapping = project.shortcutMapping
                )
            }
        }
        project.savedAtMs?.let { at ->
            add(
                "fileSave",
                at,
                expiryAt(at, expiryAt(state.settings.rules["fileSave"]?.style?.durationMs ?: 300, 50))
            )
        }
        state.lowMemoryAtMs?.let { at ->
            add(
                "lowMemory",
                at,
                expiryAt(at, expiryAt(state.settings.rules["lowMemory"]?.style?.durationMs ?: 2000, 100))
            )
        }
    }

    fun nextDeadline(state: RuntimeState, now: Long): Long? = (
        state.projects.values.mapNotNull {
            it.savedAtMs?.let { at ->
                expiryAt(
                    at,
                    expiryAt(state.settings.rules["fileSave"]?.style?.durationMs ?: 300, 50)
                )
            }
        } +
            listOfNotNull(state.lowMemoryAtMs?.let {
                expiryAt(
                    it,
                    expiryAt(state.settings.rules["lowMemory"]?.style?.durationMs ?: 2000, 100)
                )
            }))
        .filter { it > now }.minOrNull()
}
