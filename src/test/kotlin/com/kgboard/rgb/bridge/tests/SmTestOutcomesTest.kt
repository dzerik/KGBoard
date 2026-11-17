package com.kgboard.rgb.bridge.tests

import com.kgboard.rgb.runtime.OperationOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SmTestOutcomesTest {
    @Test
    fun `discovered but never executed SDK tests are not successful`() {
        val root = com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy()
        root.addChild(com.intellij.execution.testframework.sm.runner.SMTestProxy("never started", false, null))
        root.setStarted(); root.setFinished()
        assertEquals(OperationOutcome.NOT_EXECUTED, outcome(root))
    }

    @Test
    fun `SDK suite setup error is failure even with no executed leaf`() {
        val root = com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy()
        root.addChild(com.intellij.execution.testframework.sm.runner.SMTestProxy("not reached", false, null))
        root.setStarted(); root.setTestFailed("beforeAll failed", "stack", true); root.setFinished()
        assertEquals(OperationOutcome.FAILURE, outcome(root))
    }

    @Test
    fun `SDK ignored only tests do not become suite failure or success`() {
        val root = com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy()
        val child = com.intellij.execution.testframework.sm.runner.SMTestProxy("ignored", false, null)
        root.addChild(child); root.setStarted(); child.setTestIgnored("ignored", null); root.setFinished()
        assertEquals(OperationOutcome.NOT_EXECUTED, outcome(root))
    }

    @Test
    fun `SDK executed and passed leaf is successful`() {
        val root = com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy()
        val child = com.intellij.execution.testframework.sm.runner.SMTestProxy("passed", false, null)
        root.addChild(child); root.setStarted(); child.setStarted(); child.setFinished(); root.setFinished()
        assertEquals(OperationOutcome.SUCCESS, outcome(root))
    }
}
