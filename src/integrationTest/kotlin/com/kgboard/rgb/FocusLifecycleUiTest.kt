package com.kgboard.rgb

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.*
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Robot
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.minutes

@Remote("com.kgboard.rgb.probe.BuildProbe", plugin = "com.kgboard.rgb.ui-probe")
interface FocusProbeRef {
    fun getFocusStatus(): String
}

class FocusLifecycleUiTest {
    @Test
    fun `native modal retains project and external window loses IDE focus`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            val context = IdeTestContext.create("kgboard-focus-lifecycle", sdk.endpoint)
            IdeTestContext.installProbe(context)
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                val probe = withContext(OnDispatcher.EDT) { service<FocusProbeRef>(singleProject()) }
                val observations = mutableListOf<String>()
                fun awaitFocus(expected: String) {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    var actual = ""
                    while (System.nanoTime() < until) {
                        actual = probe.getFocusStatus()
                        if (actual == expected) {
                            observations.add(actual); return
                        }
                        Thread.sleep(50)
                    }
                    fail<Unit>("Expected focus $expected, actual $actual")
                }
                ideFrame { setBounds(400, 200, 1000, 800); click() }
                awaitFocus("true|true")
                ideFrame {
                    invokeAction("KGBoard.OpenSettings")
                    settingsDialog { awaitFocus("true|true"); keyboard { escape() } }
                }
                var external: JFrame? = null
                try {
                    SwingUtilities.invokeAndWait {
                        external = JFrame("KGBoard isolated focus fixture").apply {
                            setBounds(20, 20, 250, 180); isAlwaysOnTop = true; isVisible = true
                        }
                    }
                    val robot = Robot()
                    robot.mouseMove(100, 100); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(
                        InputEvent.BUTTON1_DOWN_MASK
                    )
                    awaitFocus("false|true")
                    SwingUtilities.invokeAndWait { external?.dispose() }; external = null
                    robot.mouseMove(900, 500); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(
                        InputEvent.BUTTON1_DOWN_MASK
                    )
                    awaitFocus("true|true")
                } finally {
                    SwingUtilities.invokeAndWait { external?.dispose() }
                }
                assertEquals(0, sdk.recordedWriteCount)
                val file = Path.of("build/reports/kgboard-ui/focus-native.txt")
                Files.createDirectories(file.parent); Files.write(
                file,
                observations + "SDK writes=0; actual X11 window focus only"
            )
            }
        }
    }
}
