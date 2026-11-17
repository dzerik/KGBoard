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
        val selectedGit = project.git.selected
        selectedGit?.facts?.let { facts ->
            val indicator = GitIndicatorPolicy.classify(facts)
            val rule = when (indicator) {
                GitIndicator.CONFLICT -> "conflict"; GitIndicator.DIRTY -> "dirty"; GitIndicator.DIVERGED -> "diverged"
                GitIndicator.AHEAD -> "ahead"; GitIndicator.BEHIND -> "behind"; GitIndicator.SYNCED -> "synced"
                else -> null
            }
            rule?.let { add("git.$it") }
            if (facts.freshness == Freshness.FRESH) facts.branch?.let { branch ->
                project.settings.gitBranchColorRules.firstOrNull { it.matches(branch) }
            }?.let { add("git.branch", color = LightingPolicy.color(it.color)) }
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
                project.saveDeadline?.wakeAt(state.timeAt(now)) ?: expiryAt(
                    at,
                    expiryAt(state.settings.rules["fileSave"]?.style?.durationMs ?: 300, 50)
                )
            )
        }
        state.lowMemoryAtMs?.let { at ->
            add(
                "lowMemory",
                at,
                state.lowMemoryDeadline?.wakeAt(state.timeAt(now)) ?: expiryAt(
                    at,
                    expiryAt(state.settings.rules["lowMemory"]?.style?.durationMs ?: 2000, 100)
                )
            )
        }
    }

    fun nextDeadline(state: RuntimeState, now: Long): Long? = (
        state.projects.values.mapNotNull {
            it.savedAtMs?.let { at ->
                it.saveDeadline?.wakeAt(state.timeAt(now)) ?: expiryAt(
                    at,
                    expiryAt(state.settings.rules["fileSave"]?.style?.durationMs ?: 300, 50)
                )
            }
        } +
            listOfNotNull(state.lowMemoryAtMs?.let {
                state.lowMemoryDeadline?.wakeAt(state.timeAt(now)) ?: expiryAt(
                    it,
                    expiryAt(state.settings.rules["lowMemory"]?.style?.durationMs ?: 2000, 100)
                )
            }))
        .filter { it > now }.minOrNull()
}
