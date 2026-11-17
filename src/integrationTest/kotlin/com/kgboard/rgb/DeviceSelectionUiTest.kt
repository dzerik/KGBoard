package com.kgboard.rgb

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.*
import com.intellij.driver.sdk.ui.components.common.*
import com.intellij.driver.sdk.ui.components.elements.*
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

class DeviceSelectionUiTest {
    @Test
    fun `saved port device remains selected after OK and Cancel reopen with an older missing assignment`() {
        OpenRgbSdkSimulator(
            5,
            listOf(ControllerFixtureSpec(serial = "", ledNames = listOf("Esc", "F5", "A")))
        ).use { sdk ->
            val context = IdeTestContext.create("kgboard-device-selection-reopen", sdk.endpoint)
            val settings = context.paths.configDir.resolve("options/kgboard.xml")
            val records = listOf("usb/old", "usb1").mapIndexed { index, location ->
                """<BindingRecord>
                  <option name="id" value="00000000-0000-4000-8000-00000000000${index + 1}" />
                  <option name="kind" value="port" />
                  <option name="port" value="${sdk.endpoint.port}" />
                  <option name="type" value="1" />
                  <option name="vendor" value="Vendor" />
                  <option name="name" value="Board" />
                  <option name="location" value="$location" />
                </BindingRecord>""".trimIndent()
            }.joinToString("\n")
            Files.writeString(
                settings, Files.readString(settings).replace(
                    "</component>",
                    """<option name="schemaVersion" value="1" />
                <option name="bindingRecords"><list>$records</list></option></component>"""
                )
            )
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                ideFrame {
                    for ((round, close) in listOf("OK", "Cancel", "OK").withIndex()) {
                        invokeAction("KGBoard.OpenSettings")
                        settingsDialog {
                            setBounds(0, 0, 1200, 900)
                            val connect = x { byAttribute("name", "connection.connect") }
                            connect.shouldBe(enabled)
                            connect.setFocus()
                            connect.shouldBe(focusOwner)
                            connect.keyboard { space() }
                            val devices = comboBox { byAttribute("name", "device.selection") }
                            devices.shouldBe { devices.listValues().contains("Board (3 LED)") }
                            if (round == 0) devices.selectItem("Board (3 LED)")
                            devices.shouldBe { devices.getSelectedItem() == "Board (3 LED)" }
                            x { byAttribute("name", "connection.device") }.waitContainsText("Board")
                            x { and(byType("javax.swing.JButton"), byVisibleText(close)) }.click()
                        }
                    }
                }
            }
            val saved = Files.readString(settings)
            assertTrue(saved.contains("usb/old"), "Unavailable assignments remain saved")
            assertTrue(saved.contains("usb1"), "The current assignment remains saved")
        }
    }
}
