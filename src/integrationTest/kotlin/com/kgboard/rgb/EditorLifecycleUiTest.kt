package com.kgboard.rgb

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.*
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

@Remote("com.kgboard.rgb.probe.BuildProbe", plugin = "com.kgboard.rgb.ui-probe")
interface EditorProbeRef {
    fun openTextFixture()
    fun openImageFixture()
    fun closeEditors()
    fun deleteTextFixture()
    fun getEditorStatus(): String
}

class EditorLifecycleUiTest {
    @Test
    fun `native text image close and delete invalidate prior editor facts`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            val context = IdeTestContext.create("kgboard-editor-lifecycle", sdk.endpoint)
            IdeTestContext.installProbe(context)
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                val probe = withContext(OnDispatcher.EDT) { service<EditorProbeRef>(singleProject()) }
                val observations = mutableListOf<String>()
                fun awaitStatus(expected: String) {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    var actual = ""
                    while (System.nanoTime() < until) {
                        actual = withContext(OnDispatcher.EDT) { probe.getEditorStatus() }
                        if (actual.startsWith(expected)) {
                            observations.add(actual); return
                        }
                        Thread.sleep(50)
                    }
                    fail<Unit>("Expected $expected, actual $actual")
                }
                withContext(OnDispatcher.EDT) { probe.closeEditors() }
                awaitStatus("none|false|false|NotApplicable")
                withContext(OnDispatcher.EDT) { probe.openTextFixture() }
                awaitStatus("text|true|true|")
                withContext(OnDispatcher.EDT) { probe.openImageFixture() }
                awaitStatus("nontext|false|false|NotApplicable")
                withContext(OnDispatcher.EDT) { probe.closeEditors() }
                awaitStatus("none|false|false|NotApplicable")
                withContext(OnDispatcher.EDT) { probe.openTextFixture() }
                awaitStatus("text|true|true|")
                withContext(OnDispatcher.EDT) { probe.deleteTextFixture() }
                awaitStatus("none|false|false|NotApplicable")
                assertEquals(0, sdk.recordedWriteCount)
                val file = Path.of("build/reports/kgboard-ui/editor-native.txt")
                Files.createDirectories(file.parent); Files.write(file, observations + "SDK writes=0")
            }
        }
    }
}
