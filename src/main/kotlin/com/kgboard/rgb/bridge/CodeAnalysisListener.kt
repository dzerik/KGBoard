package com.kgboard.rgb.bridge

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.problems.WolfTheProblemSolver
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.EffectTarget
import com.kgboard.rgb.effect.StaticEffect
import com.kgboard.rgb.settings.KgBoardProjectSettings
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Monitors code analysis results and displays per-key RGB indicator.
 *
 * - Errors → red
 * - Warnings only → yellow
 * - Clean → green
 */
@Service(Service.Level.PROJECT)
class CodeAnalysisListener(private val project: Project) : Disposable {

    private val log = Logger.getInstance(CodeAnalysisListener::class.java)

    companion object {
        const val EFFECT_ID = "code-analysis"

        fun getInstance(project: Project): CodeAnalysisListener =
            project.getService(CodeAnalysisListener::class.java)
    }

    fun start() {
        val projectSettings = KgBoardProjectSettings.getInstance(project)
        if (!projectSettings.analysisEnabled) return

        project.messageBus.connect(this)
            .subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
                override fun daemonFinished() {
                    updateAnalysisStatus()
                }

                override fun daemonFinished(fileEditors: Collection<FileEditor>) {
                    updateAnalysisStatus()
                }
            })

        log.info("Code analysis listener started")
    }

    private fun updateAnalysisStatus() {
        try {
            val settings = KgBoardSettings.getInstance()
            val projectSettings = KgBoardProjectSettings.getInstance(project)
            if (!settings.enabled || !projectSettings.analysisEnabled) return

            val ledIndices = projectSettings.analysisLedIndices
            if (ledIndices.isEmpty()) return

            val target = EffectTarget.LedSet(ledIndices)
            val wolf = WolfTheProblemSolver.getInstance(project)

            val hasErrors = wolf.hasProblemFilesBeneath { true }

            val color = when {
                hasErrors -> settings.parseColor(projectSettings.analysisErrorColor)
                else -> settings.parseColor(projectSettings.analysisCleanColor)
            }

            val effectManager = EffectManagerService.getInstance(project)
            effectManager.addTargetedEffect(
                EFFECT_ID,
                StaticEffect(
                    color = color,
                    name = "code-analysis",
                    priority = 2,
                    target = target
                )
            )
        } catch (e: Exception) {
            log.warn("Code analysis update error: ${e.message}")
        }
    }

    override fun dispose() {
        try {
            EffectManagerService.getInstance(project).removeTargetedEffect(EFFECT_ID)
        } catch (_: Exception) {
            // project may already be disposed
        }
    }
}
