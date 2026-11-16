package com.kgboard.rgb.runtime

import com.kgboard.rgb.effect.*

class IndicatorPolicy {
    fun layers(state: RuntimeState, now: Long): List<EffectLayer> = buildList {
        val id = state.activeProject ?: return@buildList
        val p = state.projects[id] ?: return@buildList
        val s = state.settings; val ps = p.settings
        fun add(name: String, effect: RgbEffect, at: Long = 0, expiry: Long? = null) {
            if (expiry == null || expiry > now) add(EffectLayer(EffectKey(id, name), effect, at, expiry))
        }
        fun color(raw: String) = LightingPolicy.color(raw)
        val analysis = p.editor.analysis as? AnalysisResult.Ready
        if (ps.analysisEnabled && !p.indexing && analysis != null && ps.analysisLedIndices.isNotBlank())
            add("analysis", StaticEffect(color(when {
                analysis.hasErrors -> ps.analysisErrorColor
                analysis.hasWarnings -> ps.analysisWarningColor
                else -> ps.analysisCleanColor
            }), "analysis", 2, target(ps.analysisLedIndices)))
        if (s.notifyTodoEnabled && p.editor.hasTodos == true && s.notifyTodoLedIndices.isNotBlank())
            add("todo", StaticEffect(color(s.notifyTodoColor), "todo", 1, target(s.notifyTodoLedIndices)))
        if (s.notifyIndexingEnabled && p.indexing && s.notifyIndexingLedIndices.isNotBlank())
            add("indexing", PulseEffect(color(s.notifyIndexingColor), periodMs = 1500, priority = 3, target = target(s.notifyIndexingLedIndices)))
        if (ps.gitEnabled && p.git.available) {
            if (ps.gitLedIndices.isNotBlank()) {
                val raw = when { p.git.conflict -> ps.gitConflictColor; p.git.uncommitted -> ps.gitUncommittedColor
                    p.git.unpushed -> ps.gitUnpushedColor; else -> ps.gitCleanColor }
                val effect = if (p.git.conflict) PulseEffect(color(raw), periodMs = 800, priority = 3, target = target(ps.gitLedIndices))
                    else StaticEffect(color(raw), priority = if (p.git.uncommitted || p.git.unpushed) 2 else 1, target = target(ps.gitLedIndices))
                add("git", effect)
            }
            p.git.branch?.let { branch -> ps.gitBranchColorRules.firstOrNull { it.matches(branch) } }?.let { rule ->
                add("git-branch", StaticEffect(color(rule.color), priority = 0))
            }
        }
        val mapping = (state.connection as? ConnectionState.Ready)?.let { DeviceMappingRevision(it.session, s.deviceIndex) }
        if (ps.shortcutHighlightEnabled && p.shortcutMapping == mapping) for (context in p.shortcutContexts.intersect(ps.shortcutContexts.toSet())) {
            val indices = p.shortcutIndices[context].orEmpty()
            if (indices.isNotEmpty()) add("shortcuts:$context", StaticEffect(color(when(context) {
                "debug" -> ps.shortcutDebugColor; "search" -> ps.shortcutSearchColor
                "vcs" -> ps.shortcutVcsColor; else -> ps.shortcutEditingColor
            }), priority = 1, target = EffectTarget.LedSet(indices)))
        }
        if (s.fileSaveFlashEnabled) p.savedAtMs?.let { at ->
            add("saved", FlashEffect(color(s.fileSaveFlashColor), 300, priority = 3, target = target(s.fileSaveLedIndices)), at, at + 350)
        }
        if (s.notifyLowMemoryEnabled) state.lowMemoryAtMs?.let { at ->
            add("memory", FlashEffect(color(s.notifyLowMemoryColor), 2000, priority = 10, target = target(s.notifyLowMemoryLedIndices)), at, at + 2100)
        }
    }
    fun nextDeadline(state: RuntimeState, now: Long): Long? =
        (state.projects.values.mapNotNull { it.savedAtMs?.plus(350) } + listOfNotNull(state.lowMemoryAtMs?.plus(2100))).filter { it > now }.minOrNull()
}
