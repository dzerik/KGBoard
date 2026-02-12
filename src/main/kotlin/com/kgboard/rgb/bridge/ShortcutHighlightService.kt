package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.EffectTarget
import com.kgboard.rgb.effect.StaticEffect
import com.kgboard.rgb.layout.KeyboardLayoutService
import com.kgboard.rgb.settings.KgBoardProjectSettings
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.Color

/**
 * Highlights keyboard shortcuts relevant to the current context.
 *
 * Contexts:
 * - debug: step over, step into, resume, etc.
 * - search: find, replace, find in files
 * - editing: copy, paste, undo, redo
 * - vcs: commit, push, pull, update
 * - always: user-configured always-on shortcuts
 */
@Service(Service.Level.PROJECT)
class ShortcutHighlightService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ShortcutHighlightService::class.java)

    companion object {
        const val EFFECT_ID_PREFIX = "shortcut:"

        private val CONTEXT_ACTIONS = mapOf(
            "debug" to listOf(
                "StepOver", "StepInto", "StepOut", "Resume", "Pause",
                "ToggleLineBreakpoint", "EvaluateExpression", "RunToCursor"
            ),
            "search" to listOf(
                "Find", "Replace", "FindInPath", "ReplaceInPath",
                "FindNext", "FindPrevious", "FindUsages"
            ),
            "editing" to listOf(
                "\$Copy", "\$Paste", "\$Cut", "\$Undo", "\$Redo",
                "\$SelectAll", "EditorDuplicate", "CommentByLineComment"
            ),
            "vcs" to listOf(
                "CheckinProject", "Vcs.Push", "Vcs.UpdateProject",
                "Git.Pull", "Annotate", "Vcs.ShowTabbedFileHistory"
            )
        )

        fun getInstance(project: Project): ShortcutHighlightService =
            project.getService(ShortcutHighlightService::class.java)
    }

    private var activeContexts = mutableSetOf<String>()

    fun activateContext(context: String) {
        val projectSettings = KgBoardProjectSettings.getInstance(project)
        if (!projectSettings.shortcutHighlightEnabled) return
        if (context !in projectSettings.shortcutContexts) return

        activeContexts.add(context)
        updateHighlights()
    }

    fun deactivateContext(context: String) {
        if (activeContexts.remove(context)) {
            clearHighlightsForContext(context)
        }
    }

    fun clearAll() {
        val effectManager = EffectManagerService.getInstance(project)
        for (context in activeContexts) {
            effectManager.removeTargetedEffect("$EFFECT_ID_PREFIX$context")
        }
        activeContexts.clear()
    }

    private fun updateHighlights() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled) return

        val layout = KeyboardLayoutService.getInstance()
        if (!layout.isAvailable) return

        val keymap = KeymapManager.getInstance().activeKeymap

        for (context in activeContexts) {
            val actionIds = CONTEXT_ACTIONS[context] ?: continue
            val ledIndices = mutableListOf<Int>()

            for (actionId in actionIds) {
                val shortcuts = keymap.getShortcuts(actionId)
                for (shortcut in shortcuts) {
                    if (shortcut is KeyboardShortcut) {
                        val keyCode = shortcut.firstKeyStroke.keyCode
                        layout.getLedIndexForKeyCode(keyCode)?.let { ledIndices.add(it) }
                    }
                }
            }

            if (ledIndices.isNotEmpty()) {
                val effectManager = EffectManagerService.getInstance(project)
                effectManager.addTargetedEffect(
                    "$EFFECT_ID_PREFIX$context",
                    StaticEffect(
                        color = contextColor(context),
                        name = "shortcut-$context",
                        priority = 1,
                        target = EffectTarget.LedSet(ledIndices.distinct())
                    )
                )
            }
        }
    }

    private fun clearHighlightsForContext(context: String) {
        val effectManager = EffectManagerService.getInstance(project)
        effectManager.removeTargetedEffect("$EFFECT_ID_PREFIX$context")
    }

    private fun contextColor(context: String): Color = when (context) {
        "debug" -> Color(101, 31, 255)  // purple
        "search" -> Color(41, 121, 255) // blue
        "editing" -> Color(0, 200, 83)  // green
        "vcs" -> Color(255, 145, 0)     // orange
        else -> Color(255, 255, 255)    // white
    }

    override fun dispose() {
        activeContexts.clear()
    }
}
