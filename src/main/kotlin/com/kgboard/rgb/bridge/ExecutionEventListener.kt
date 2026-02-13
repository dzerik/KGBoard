package com.kgboard.rgb.bridge

import com.intellij.execution.ExecutionListener
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.FlashEffect
import com.kgboard.rgb.effect.PulseEffect
import com.kgboard.rgb.effect.StaticEffect
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Listens to process execution events (Run, Debug).
 * - Run started -> green static
 * - Debug started -> purple pulse
 * - Process terminated -> return to idle
 *
 * Supports dual-path dispatch:
 * - Without LED indices: Phase 1 API for backward compatibility
 * - With LED indices: Phase 2 API for per-key targeting
 */
class ExecutionEventListener(private val project: Project) : ExecutionListener {

    private val log = Logger.getInstance(ExecutionEventListener::class.java)

    companion object {
        const val EFFECT_ID = "execution-status"
    }

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        try {
            val settings = KgBoardSettings.getInstance()
            if (!settings.enabled) return

            val effectManager = EffectManagerService.getInstance(project)
            val profileName = env.runProfile.name
            val ledIndices = settings.executionLedIndices
            val isTargeted = ledIndices.isNotEmpty()

            when (executorId) {
                DefaultDebugExecutor.EXECUTOR_ID -> {
                    log.info("Debug started: $profileName")
                    if (isTargeted) {
                        val target = settings.resolveTarget(ledIndices)
                        effectManager.addTargetedEffect(
                            EFFECT_ID,
                            PulseEffect(
                                color = settings.debugColor,
                                periodMs = settings.pulseSpeedMs,
                                name = "debug-active",
                                priority = 6,
                                target = target
                            )
                        )
                    } else {
                        effectManager.applyPersistent(
                            PulseEffect(
                                color = settings.debugColor,
                                periodMs = settings.pulseSpeedMs,
                                name = "debug-active",
                                priority = 6
                            )
                        )
                    }
                }
                DefaultRunExecutor.EXECUTOR_ID -> {
                    log.info("Run started: $profileName")
                    if (isTargeted) {
                        val target = settings.resolveTarget(ledIndices)
                        effectManager.addTargetedEffect(
                            EFFECT_ID,
                            StaticEffect(
                                color = settings.runColor,
                                name = "run-active",
                                priority = 4,
                                target = target
                            )
                        )
                    } else {
                        effectManager.applyPersistent(
                            StaticEffect(
                                color = settings.runColor,
                                name = "run-active",
                                priority = 4
                            )
                        )
                    }
                }
                else -> {
                    log.debug("Execution started with executor: $executorId")
                }
            }
        } catch (e: Exception) {
            log.warn("Execution event listener error: ${e.message}")
        }
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        try {
            val settings = KgBoardSettings.getInstance()
            if (!settings.enabled) return

            val effectManager = EffectManagerService.getInstance(project)
            val profileName = env.runProfile.name
            val ledIndices = settings.executionLedIndices
            val isTargeted = ledIndices.isNotEmpty()

            log.info("Process terminated: $profileName (exit code: $exitCode)")

            if (exitCode != 0) {
                if (isTargeted) {
                    val target = settings.resolveTarget(ledIndices)
                    effectManager.addTargetedEffect(
                        EFFECT_ID,
                        FlashEffect(
                            color = settings.stopColor,
                            durationMs = 1500,
                            name = "process-error",
                            priority = 7,
                            target = target
                        ),
                        timeoutMs = 1600
                    )
                } else {
                    effectManager.applyTemporary(
                        FlashEffect(
                            color = settings.stopColor,
                            durationMs = 1500,
                            name = "process-error",
                            priority = 7
                        )
                    )
                }
            } else {
                if (isTargeted) {
                    effectManager.removeTargetedEffect(EFFECT_ID)
                } else {
                    effectManager.returnToIdle()
                }
            }
        } catch (e: Exception) {
            log.warn("Process termination event error: ${e.message}")
        }
    }
}
