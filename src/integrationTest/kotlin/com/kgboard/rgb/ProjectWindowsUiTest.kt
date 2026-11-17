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
import java.awt.Robot
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

@Remote("com.kgboard.rgb.probe.BuildProbe", plugin = "com.kgboard.rgb.ui-probe")
interface ProjectWindowsProbeRef {
    fun openSecondaryProject()
    fun arrangeProjectWindows()
    fun getProjectWindowStatus(): String
    fun closeSecondaryProject()
}

class ProjectWindowsUiTest {
    @Test
    fun `actual project windows switch owner and closing background preserves active project`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            val context = IdeTestContext.create("kgboard-project-windows", sdk.endpoint)
            IdeTestContext.installProbe(context)
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                val probe = withContext(OnDispatcher.EDT) { service<ProjectWindowsProbeRef>(singleProject()) }
                val observations = mutableListOf<String>()
                fun awaitStatus(expected: String, exact: Boolean = true) {
                    val until = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
                    var actual = ""
                    while (System.nanoTime() < until) {
                        actual = withContext(OnDispatcher.EDT) { probe.getProjectWindowStatus() }
                        if (if (exact) actual == expected else actual.startsWith(expected)) {
                            observations.add(actual); return
                        }
                        check(!actual.startsWith("error|")) { actual }
                        Thread.sleep(100)
                    }
                    fail<Unit>("Expected project windows $expected, actual $actual")
                }
                withContext(OnDispatcher.EDT) { probe.openSecondaryProject() }
                awaitStatus("2|", false)
                withContext(OnDispatcher.EDT) { probe.arrangeProjectWindows() }
                val robot = Robot()
                fun click(x: Int, y: Int) {
                    robot.mouseMove(
                        x,
                        y
                    ); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                }
                click(550, 600); awaitStatus("2|true|primary")
                click(1800, 600); awaitStatus("2|true|secondary")
                click(550, 600); awaitStatus("2|true|primary")
                withContext(OnDispatcher.EDT) { probe.closeSecondaryProject() }
                awaitStatus("1|true|primary")
                assertEquals(0, sdk.recordedWriteCount)
                val file = Path.of("build/reports/kgboard-ui/project-windows-native.txt")
                Files.createDirectories(file.parent); Files.write(
                file,
                observations + "SDK writes=0; two actual project frames"
            )
            }
        }
    }
}
