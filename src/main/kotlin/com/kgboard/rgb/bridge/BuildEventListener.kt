package com.kgboard.rgb.bridge

import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.FlashEffect
import com.kgboard.rgb.effect.PulseEffect
import com.kgboard.rgb.effect.StaticEffect
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Listens to compilation events and triggers RGB effects.
 * - Build started → pulsing yellow
 * - Build success → green flash
 * - Build failure → red static
 */
class BuildEventListener(private val project: Project) : CompilationStatusListener {

    private val log = Logger.getInstance(BuildEventListener::class.java)

    override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, compileContext: CompileContext) {
        try {
            val settings = KgBoardSettings.getInstance()
            if (!settings.enabled) return

            val effectManager = EffectManagerService.getInstance(project)

            if (aborted) {
                log.info("Build aborted")
                effectManager.applyTemporary(
                    FlashEffect(
                        color = settings.stopColor,
                        durationMs = 1000,
                        name = "build-aborted",
                        priority = 8
                    )
                )
                return
            }

            if (errors > 0) {
                log.info("Build failed with $errors errors")
                effectManager.applyEffect(
                    StaticEffect(
                        color = settings.buildFailureColor,
                        name = "build-failure",
                        priority = 9
                    )
                )
            } else {
                log.info("Build succeeded (warnings: $warnings)")
                effectManager.applyTemporary(
                    FlashEffect(
                        color = settings.buildSuccessColor,
                        durationMs = 2000,
                        name = "build-success",
                        priority = 7
                    )
                )
            }
        } catch (e: Exception) {
            log.warn("Build event listener error: ${e.message}")
        }
    }

    override fun automakeCompilationFinished(errors: Int, warnings: Int, compileContext: CompileContext) {
        compilationFinished(false, errors, warnings, compileContext)
    }

    override fun fileGenerated(outputRoot: String, relativePath: String) {
        try {
            val settings = KgBoardSettings.getInstance()
            if (!settings.enabled) return

            val effectManager = EffectManagerService.getInstance(project)
            effectManager.applyPersistent(
                PulseEffect(
                    color = settings.buildInProgressColor,
                    periodMs = settings.pulseSpeedMs,
                    name = "build-progress",
                    priority = 5
                )
            )
        } catch (e: Exception) {
            log.warn("Build progress event error: ${e.message}")
        }
    }
}
