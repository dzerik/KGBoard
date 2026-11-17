package com.kgboard.rgb.bridge.tests

import com.kgboard.rgb.runtime.OperationOutcome
import com.kgboard.rgb.bridge.testOutcome

/** SDK suites may fail before any discovered leaf is launched. */
fun outcome(root: com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy): OperationOutcome {
    val all = root.allTests
    val leaves = all.filter { it !== root && !it.isSuite }
    val launched = leaves.filter { it.wasLaunched() }
    return testOutcome(
        root.wasTerminated(), launched.size, launched.count { it.isIgnored },
        (all + root).count { it.isDefect && !it.isIgnored })
}
