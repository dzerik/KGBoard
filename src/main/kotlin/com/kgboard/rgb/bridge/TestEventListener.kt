package com.kgboard.rgb.bridge

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.FlashEffect
import com.kgboard.rgb.effect.PulseEffect
import com.kgboard.rgb.effect.StaticEffect
import com.kgboard.rgb.settings.KgBoardSettings

/**
 * Listens to test execution events.
 * - Tests running -> blue pulse
 * - All tests passed -> green flash
 * - Tests failed -> red static
 *
 * Supports dual-path dispatch:
 * - Without LED indices: Phase 1 API for backward compatibility
 * - With LED indices: Phase 2 API for per-key targeting
 */
class TestEventListener(private val project: Project) : SMTRunnerEventsListener {

    private val log = Logger.getInstance(TestEventListener::class.java)

    companion object {
        const val EFFECT_ID = "test-status"
    }

    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
        try {
            val settings = KgBoardSettings.getInstance()
            if (!settings.enabled) return

            val effectManager = EffectManagerService.getInstance(project)
            val ledIndices = settings.testLedIndices
            val isTargeted = ledIndices.isNotEmpty()

            log.info("Testing started")
            if (isTargeted) {
                val target = settings.resolveTarget(ledIndices)
                effectManager.addTargetedEffect(
                    EFFECT_ID,
                    PulseEffect(
                        color = settings.testRunningColor,
                        periodMs = settings.pulseSpeedMs,
                        name = "tests-running",
                        priority = 6,
                        target = target
                    )
                )
            } else {
                effectManager.applyPersistent(
                    PulseEffect(
                        color = settings.testRunningColor,
                        periodMs = settings.pulseSpeedMs,
                        name = "tests-running",
                        priority = 6
                    )
                )
            }
        } catch (e: Exception) {
            log.warn("Test started event error: ${e.message}")
        }
    }

    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        try {
            val settings = KgBoardSettings.getInstance()
            if (!settings.enabled) return

            val effectManager = EffectManagerService.getInstance(project)
            val allTests = testsRoot.allTests
            val failed = allTests.count { it.isDefect }
            val total = allTests.size
            val ledIndices = settings.testLedIndices
            val isTargeted = ledIndices.isNotEmpty()

            if (failed > 0) {
                log.info("Tests finished: $failed/$total failed")
                if (isTargeted) {
                    val target = settings.resolveTarget(ledIndices)
                    effectManager.addTargetedEffect(
                        EFFECT_ID,
                        StaticEffect(
                            color = settings.testFailColor,
                            name = "tests-failed",
                            priority = 8,
                            target = target
                        )
                    )
                } else {
                    effectManager.applyEffect(
                        StaticEffect(
                            color = settings.testFailColor,
                            name = "tests-failed",
                            priority = 8
                        )
                    )
                }
            } else {
                log.info("All $total tests passed")
                if (isTargeted) {
                    val target = settings.resolveTarget(ledIndices)
                    effectManager.addTargetedEffect(
                        EFFECT_ID,
                        FlashEffect(
                            color = settings.testPassColor,
                            durationMs = 2000,
                            name = "tests-passed",
                            priority = 7,
                            target = target
                        ),
                        timeoutMs = 2100
                    )
                } else {
                    effectManager.applyTemporary(
                        FlashEffect(
                            color = settings.testPassColor,
                            durationMs = 2000,
                            name = "tests-passed",
                            priority = 7
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("Test finished event error: ${e.message}")
        }
    }

    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFinished() {}
    override fun onCustomProgressTestFailed() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy?) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy?) {}
    override fun onTestsCountInSuite(count: Int) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, testCount: Int) {}
}
