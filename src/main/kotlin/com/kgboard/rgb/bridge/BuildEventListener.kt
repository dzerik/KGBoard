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
 * - Build started -> pulsing yellow
 * - Build success -> green flash
 * - Build failure -> red static
 *
 * Supports dual-path dispatch:
 * - Without LED indices: Phase 1 API (applyEffect/applyTemporary) for backward compatibility
 * - With LED indices: Phase 2 API (addTargetedEffect) for per-key targeting
 */
class BuildEventListener(private val project: Project) : CompilationStatusListener {

    private val log = Logger.getInstance(BuildEventListener::class.java)

    companion object {
        const val EFFECT_ID = "build-status"
    }

    override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, compileContext: CompileContext) {
        try {
            val settings = KgBoardSettings.getInstance()
            if (!settings.enabled) return

            val effectManager = EffectManagerService.getInstance(project)
            val ledIndices = settings.buildLedIndices
            val isTargeted = ledIndices.isNotEmpty()

            if (aborted) {
                log.info("Build aborted")
                if (isTargeted) {
                    val target = settings.resolveTarget(ledIndices)
                    effectManager.addTargetedEffect(
                        EFFECT_ID,
                        FlashEffect(
                            color = settings.stopColor,
                            durationMs = 1000,
                            name = "build-aborted",
                            priority = 8,
                            target = target
                        ),
                        timeoutMs = 1100
                    )
                } else {
                    effectManager.applyTemporary(
                        FlashEffect(
                            color = settings.stopColor,
                            durationMs = 1000,
                            name = "build-aborted",
                            priority = 8
                        )
                    )
                }
                return
            }

            if (errors > 0) {
                log.info("Build failed with $errors errors")
                if (isTargeted) {
                    val target = settings.resolveTarget(ledIndices)
                    effectManager.addTargetedEffect(
                        EFFECT_ID,
                        StaticEffect(
                            color = settings.buildFailureColor,
                            name = "build-failure",
                            priority = 9,
                            target = target
                        )
                    )
                } else {
                    effectManager.applyEffect(
                        StaticEffect(
                            color = settings.buildFailureColor,
                            name = "build-failure",
                            priority = 9
                        )
                    )
                }
            } else {
                log.info("Build succeeded (warnings: $warnings)")
                if (isTargeted) {
                    val target = settings.resolveTarget(ledIndices)
                    effectManager.addTargetedEffect(
                        EFFECT_ID,
                        FlashEffect(
                            color = settings.buildSuccessColor,
                            durationMs = 2000,
                            name = "build-success",
                            priority = 7,
                            target = target
                        ),
                        timeoutMs = 2100
                    )
                } else {
                    effectManager.applyTemporary(
                        FlashEffect(
                            color = settings.buildSuccessColor,
                            durationMs = 2000,
                            name = "build-success",
                            priority = 7
                        )
                    )
                }
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
            val ledIndices = settings.buildLedIndices
            val isTargeted = ledIndices.isNotEmpty()

            if (isTargeted) {
                val target = settings.resolveTarget(ledIndices)
                effectManager.addTargetedEffect(
                    EFFECT_ID,
                    PulseEffect(
                        color = settings.buildInProgressColor,
                        periodMs = settings.pulseSpeedMs,
                        name = "build-progress",
                        priority = 5,
                        target = target
                    )
                )
            } else {
                effectManager.applyPersistent(
                    PulseEffect(
                        color = settings.buildInProgressColor,
                        periodMs = settings.pulseSpeedMs,
                        name = "build-progress",
                        priority = 5
                    )
                )
            }
        } catch (e: Exception) {
            log.warn("Build progress event error: ${e.message}")
        }
    }
}
