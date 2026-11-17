package com.kgboard.rgb

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.*
import com.intellij.driver.sdk.ui.components.common.*
import com.intellij.driver.sdk.ui.components.elements.*
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

@Remote("com.kgboard.rgb.settings.KgBoardSettings", plugin = "com.kgboard.rgb")
interface ApplicationSettingsRef {
    fun getState(): ApplicationStateRef
}

@Remote("com.kgboard.rgb.settings.KgBoardSettings\$State", plugin = "com.kgboard.rgb")
interface ApplicationStateRef {
    fun getEnabled(): Boolean
    fun getBindingRecords(): List<BindingRecordRef>
}

@Remote("com.kgboard.rgb.settings.BindingRecord", plugin = "com.kgboard.rgb")
interface BindingRecordRef {
    fun getKind(): String
}

/** Public user actions plus read-only service observations; all RGB output goes to a fake SDK. */
class SettingsWorkflowUiTest {
    @Test
    fun `identify and keyboard preview preserve the idle baseline and Cancel discards binding`() {
        fixture("kgboard-settings-cancel") { sdk, _ ->
            ideFrame {
                invokeAction("KGBoard.OpenSettings")
                settingsDialog {
                    setBounds(0, 0, 1200, 900)
                    checkBox { byAttribute("name", "app.enabled") }.check()
                    applyChanges()
                    awaitCondition("Enable must be applied") {
                        service<ApplicationSettingsRef>().getState().getEnabled()
                    }
                    connectAndSelect(sdk)
                    x { byAttribute("name", "device.test") }.activateWithKeyboard()
                    assertEquals(List(3) { Color(77, 77, 77) }, sdk.awaitWrite(5000).colors)
                    val preview = configureNamedPreview()
                    preview.activateWithKeyboard()
                    awaitFrame(sdk, listOf(Color(0xFFD600), idle, idle))
                    x { and(byType("javax.swing.JButton"), byVisibleText("Cancel")) }.click()
                }
                assertTrue(
                    service<ApplicationSettingsRef>().getState().getBindingRecords().none { it.getKind() == "serial" },
                    "Cancel leaves the managed binding unchanged"
                )
            }
        }
    }

    @Test
    fun `Apply commits binding and Disconnect and Disable revoke preview`() {
        fixture("kgboard-settings-apply") { sdk, context ->
            ideFrame {
                invokeAction("KGBoard.OpenSettings")
                settingsDialog {
                    setBounds(0, 0, 1200, 900)
                    checkBox { byAttribute("name", "app.enabled") }.check()
                    applyChanges()
                    awaitCondition("Enable must be applied") {
                        service<ApplicationSettingsRef>().getState().getEnabled()
                    }
                    connectAndSelect(sdk)
                    val preview = configureNamedPreview()
                    preview.activateWithKeyboard()
                    awaitFrame(sdk, listOf(Color(0xFFD600), idle, idle))
                    applyChanges()
                    awaitCondition("Apply must commit the confirmed binding") {
                        service<ApplicationSettingsRef>().getState().getBindingRecords()
                            .any { it.getKind() == "serial" }
                    }
                    currentApply().shouldBe(notEnabled)
                    awaitFrame(sdk, List(3) { idle })
                    preview.activateWithKeyboard()
                    x { byAttribute("name", "connection.disconnect") }.activateWithKeyboard()
                    preview.shouldBe(notEnabled)
                    x { byAttribute("name", "connection.connect") }.activateWithKeyboard()
                    preview.activateWithKeyboard()
                    val integration = checkBox { byAttribute("name", "app.enabled") }
                    assertTrue(integration.isSelected())
                    integration.activateWithKeyboard()
                    integration.shouldBe { !isSelected() }
                    applyChanges()
                    awaitCondition("Disable must be applied") {
                        !service<ApplicationSettingsRef>().getState().getEnabled()
                    }
                    preview.shouldBe(notEnabled)
                    x { and(byType("javax.swing.JButton"), byVisibleText("Cancel")) }.click()
                }
                invokeAction("SaveAll")
                awaitCondition("Applied binding must reach isolated settings XML") {
                    Files.readString(context.paths.configDir.resolve("options/kgboard.xml")).contains("serial-1")
                }
            }
            val xml = Files.readString(context.paths.configDir.resolve("options/kgboard.xml"))
            assertTrue(xml.contains("name=\"enabled\" value=\"false\""))
        }
    }

    private fun fixture(name: String, body: Driver.(OpenRgbSdkSimulator, IDETestContext) -> Unit) {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec(ledNames = listOf("Esc", "F5", "A")))).use { sdk ->
            val context = IdeTestContext.create(name, sdk.endpoint)
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                body(this, sdk, context)
            }
        }
    }

    private fun UiComponent.currentApply() = x { and(byType("javax.swing.JButton"), byVisibleText("Apply")) }
    private fun UiComponent.applyChanges() {
        currentApply().shouldBe(enabled).click()
    }

    private fun UiComponent.connectAndSelect(sdk: OpenRgbSdkSimulator) {
        x { byAttribute("name", "connection.connect") }.activateWithKeyboard()
        val device = comboBox { byAttribute("name", "device.selection") }
        awaitCondition("SDK inventory must appear at ${sdk.endpoint.port}") {
            device.listValues().contains("Board (3 LED)")
        }
        device.selectItem("Board (3 LED)")
    }

    private fun UiComponent.configureNamedPreview(): UiComponent {
        val navigation = tabbedPane {
            and(byAccessibleName("Lighting scenarios"), byType("com.intellij.ui.components.JBTabbedPane"))
        }
        navigation.setFocus()
        navigation.shouldBe(focusOwner)
        navigation.keyboard { right() }
        assertEquals("Lighting scenarios", navigation.selectedTabName)
        comboBox { byAttribute("name", "scenario.effect") }.selectItem("Static color")
        comboBox { byAttribute("name", "scenario.target.kind") }.selectItem("Named LEDs")
        jBlist(xQuery { byAttribute("name", "scenario.target.keys") }).clickItem("Esc")
        return x { byAttribute("name", "scenario.preview") }
    }

    private fun UiComponent.activateWithKeyboard() {
        shouldBe(enabled)
        setFocus()
        shouldBe(focusOwner)
        keyboard { space() }
    }

    private fun awaitFrame(sdk: OpenRgbSdkSimulator, expected: List<Color>) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var colors: List<Color>
        do {
            colors = sdk.awaitWrite(5000).colors
        } while (colors != expected && System.nanoTime() < deadline)
        assertEquals(expected, colors)
    }

    private fun awaitCondition(reason: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { reason }
            Thread.sleep(50)
        }
    }

    private val idle = Color(0x263238)
}
