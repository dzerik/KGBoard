package com.kgboard.rgb

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.IdeTheme
import com.intellij.driver.sdk.changeTheme
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.*
import com.intellij.driver.sdk.ui.components.common.*
import com.intellij.driver.sdk.ui.components.elements.*
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.client.service
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes

class DiagnosticsUiTest {
    companion object {
        @JvmStatic
        fun themes() = listOf(IdeTheme.DARK, IdeTheme.LIGHT)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("themes")
    fun `native diagnostics popup and twenty panel lifetimes do not send extra frames`(theme: IdeTheme) {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec(ledNames = listOf("Esc", "F5", "A")))).use { sdk ->
            IdeTestContext.create("kgboard-diagnostics-${theme.name.lowercase()}", sdk.endpoint).runIdeWithDriver()
                .useDriverAndCloseIde {
                    waitForIndicators(2.minutes); changeTheme(theme)
                    val driver = this
                    ideFrame {
                        invokeAction("KGBoard.OpenSettings")
                        settingsDialog {
                            setBounds(0, 0, 1200, 900)
                            checkBox { byAttribute("name", "app.enabled") }.check()
                            x { and(byType("javax.swing.JButton"), byVisibleText("Apply")) }.activate()
                            awaitCondition("Enable must be applied") {
                                service<ApplicationSettingsRef>().getState().getEnabled()
                            }
                            x { byAttribute("name", "connection.connect") }.activate()
                            val choice = comboBox { byAttribute("name", "device.selection") }
                            awaitCondition("SDK inventory must appear") {
                                choice.listValues().contains("Board (3 LED)")
                            }
                            choice.setFocus()
                            choice.keyboard { space(); down(); enter(); tab() }
                            x { and(byType("javax.swing.JButton"), byVisibleText("Apply")) }.activate()
                            awaitCondition("Binding must be applied") {
                                service<ApplicationSettingsRef>().getState().getBindingRecords()
                                    .any { it.getKind() == "serial" }
                            }
                            // Modal Settings must expose a usable child diagnostics view without discarding its draft.
                            x { byAttribute("name", "connection.diagnostics") }.click()
                            driver.ui.dialog(title = "Diagnostics") {
                                val area = x { byAttribute("name", "diagnostics.devices") }
                                area.shouldBe(present); area.setFocus(); area.shouldBe(focusOwner)
                                area.waitContainsText("No active signals")
                                setBounds(0, 0, 900, 600)
                                robot.waitForIdle()
                                val output =
                                    Path.of("build/reports/kgboard-ui/diagnostics-child-${theme.name.lowercase()}.png")
                                Files.createDirectories(output.parent)
                                val captured = Robot().createScreenCapture(
                                    java.awt.Rectangle(
                                        component.getLocationOnScreen(),
                                        java.awt.Dimension(component.width, component.height)
                                    )
                                )
                                val colors = (0 until captured.width step 12).flatMap { x ->
                                    (0 until captured.height step 12).map { y -> captured.getRGB(x, y) }
                                }.toSet()
                                assertTrue(colors.size > 10, "Diagnostic screenshot must contain rendered controls")
                                ImageIO.write(captured, "png", output.toFile())
                                x { and(byType("javax.swing.JButton"), byVisibleText("OK")) }.activate()
                            }
                            cancelButton.click()
                        }
                        sdk.awaitWrite(5000)
                        invokeAction("KGBoard.OpenConnectionPopup")
                        x { byAttribute("name", "diagnostics.popup") }.shouldBe(present)
                        keyboard { escape() }
                        val before = sdk.recordedWriteCount
                        for (cycle in 0 until 20) {
                            invokeAction("KGBoard.OpenDiagnostics")
                            val devices = x { byAttribute("name", "diagnostics.devices") }
                            devices.shouldBe(present)
                            if (cycle == 0) {
                                x { byAttribute("name", "diagnostics.copy") }.activate()
                                var copied = ""
                                awaitCondition("Copy must reach the isolated X11 clipboard") {
                                    copied = runCatching {
                                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                            .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
                                    }.getOrDefault("")
                                    copied.startsWith("KGBoard diagnostics")
                                }
                                val artifactVersion =
                                    Path.of(System.getProperty("kgboard.ui.pluginZip")).fileName.toString()
                                        .removePrefix("KGBoard-").removeSuffix(".zip")
                                assertTrue(
                                    copied.contains("plugin=$artifactVersion"),
                                    "Copy must report the actual installed artifact version"
                                )
                                assertFalse(copied.contains("serial-1"))
                                assertFalse(copied.contains("kgboard-ui-project-"))
                                x { byAttribute("name", "diagnostics.clear") }.activate()
                            }
                            devices.setFocus(); devices.shouldBe(focusOwner)
                            invokeAction("HideActiveWindow")
                        }
                        assertEquals(
                            before,
                            sdk.recordedWriteCount,
                            "Opening and closing diagnostics must not write frames"
                        )
                        invokeAction("KGBoard.OpenDiagnostics")
                        x { byAttribute("name", "diagnostics.clear") }.activate()
                        assertEquals(before, sdk.recordedWriteCount)
                    }
                }
        }
    }

    private fun UiComponent.activate() {
        shouldBe(enabled); setFocus(); shouldBe(focusOwner); keyboard { space() }
    }

    private fun awaitCondition(reason: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { reason }; Thread.sleep(50)
        }
    }
}
