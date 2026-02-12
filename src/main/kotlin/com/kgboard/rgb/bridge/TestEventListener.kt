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
 * - Tests running → blue pulse
 * - All tests passed → green flash
 * - Tests failed → red static
 */
class TestEventListener(private val project: Project) : SMTRunnerEventsListener {

    private val log = Logger.getInstance(TestEventListener::class.java)

    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled) return

        log.info("Testing started")
        EffectManagerService.getInstance(project).applyPersistent(
            PulseEffect(
                color = settings.testRunningColor,
                periodMs = settings.pulseSpeedMs,
                name = "tests-running",
                priority = 6
            )
        )
    }

    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled) return

        val effectManager = EffectManagerService.getInstance(project)
        val allTests = testsRoot.allTests
        val failed = allTests.count { it.isDefect }
        val total = allTests.size

        if (failed > 0) {
            log.info("Tests finished: $failed/$total failed")
            effectManager.applyEffect(
                StaticEffect(
                    color = settings.testFailColor,
                    name = "tests-failed",
                    priority = 8
                )
            )
        } else {
            log.info("All $total tests passed")
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
