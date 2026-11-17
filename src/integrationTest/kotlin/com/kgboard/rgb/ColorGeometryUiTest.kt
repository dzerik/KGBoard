package com.kgboard.rgb

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.*
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.common.*
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

@Remote("com.kgboard.rgb.probe.BuildProbe", plugin = "com.kgboard.rgb.ui-probe")
interface ColorGeometryProbeRef {
    fun getVisibleColorMetrics(): String
}

class ColorGeometryUiTest {
    @ParameterizedTest
    @EnumSource(value = IdeTheme::class, names = ["DARK", "LIGHT"])
    fun `native color value fits actual font and insets at 200 percent`(theme: IdeTheme) {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            val context = IdeTestContext.create("kgboard-color-geometry-${theme.name.lowercase()}", sdk.endpoint, 2f)
            IdeTestContext.installProbe(context)
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes); changeTheme(theme)
                val probe = withContext(OnDispatcher.EDT) { service<ColorGeometryProbeRef>(singleProject()) }
                val measurements = mutableListOf<String>()
                ideFrame {
                    invokeAction("KGBoard.OpenSettings")
                    settingsDialog {
                        val navigation = tabbedPane { byAttribute("name", "kgboard.navigation") }
                        navigation.setFocus(); navigation.shouldBe(focusOwner); navigation.keyboard { right() }
                        assertEquals("Lighting scenarios", navigation.selectedTabName)
                        for ((width, height) in listOf(900 to 700, 1200 to 900)) {
                            setBounds(0, 0, width * 2, height * 2)
                            x { byAttribute("name", "color.value") }.shouldBe(present)
                            val metrics = withContext(OnDispatcher.EDT) { probe.getVisibleColorMetrics() }.split('|')
                                .map(String::toInt)
                            measurements.add("$theme ${width}x$height available=${metrics[0]} needed=${metrics[1]}")
                            val file = Path.of("build/reports/kgboard-ui/color-geometry-${theme.name.lowercase()}.txt")
                            Files.createDirectories(file.parent); Files.write(file, measurements)
                            assertTrue(metrics[0] >= metrics[1], measurements.last())
                        }
                        keyboard { escape() }
                    }
                }
                assertEquals(0, sdk.recordedWriteCount)
            }
        }
    }
}
