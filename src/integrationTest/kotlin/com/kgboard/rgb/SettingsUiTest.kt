package com.kgboard.rgb

import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.changeTheme
import com.intellij.driver.sdk.IdeTheme
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Robot
import javax.imageio.ImageIO
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.*
import com.intellij.driver.sdk.ui.components.common.*
import com.intellij.driver.sdk.ui.components.elements.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.time.Duration.Companion.minutes

@Remote("com.intellij.ui.scale.JBUIScale")
interface UiScaleRef {
    fun scale(value: Float): Float
}

class SettingsUiTest {
    data class UiCase(val theme: IdeTheme, val scale: Float) {
        override fun toString() = "${theme.name.lowercase()}-${(scale * 100).toInt()}-en"
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(IdeTheme.DARK, IdeTheme.LIGHT).flatMap { theme ->
            listOf(1f, 1.5f, 2f).map { scale -> UiCase(theme, scale) }
        }.filter { System.getenv("KGBOARD_UI_CASE")?.let { filter -> it.toString().contains(filter) } ?: true }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `public settings action opens the native scenario editor`(case: UiCase) {
        com.kgboard.rgb.testing.OpenRgbSdkSimulator(
            5, listOf(
                com.kgboard.rgb.testing.ControllerFixtureSpec(ledNames = listOf("Key: Esc", "Key: F5", "Key: A"))
            )
        ).use { sdk ->
            IdeTestContext.create("kgboard-settings-$case", sdk.endpoint, case.scale).runIdeWithDriver()
                .useDriverAndCloseIde {
                    waitForIndicators(2.minutes)
                    changeTheme(case.theme)
                    assertEquals(case.scale, utility<UiScaleRef>().scale(1f), "Actual IDE scale")
                    ideFrame {
                        invokeAction("KGBoard.OpenSettings")
                        settingsDialog {
                            setBounds(0, 0, (900 * case.scale).toInt(), (700 * case.scale).toInt())
                            val navigation = tabbedPane {
                                and(
                                    byAccessibleName("Lighting scenarios"),
                                    byType("com.intellij.ui.components.JBTabbedPane")
                                )
                            }
                            navigation.setFocus()
                            navigation.shouldBe(focusOwner)
                            navigation.keyboard { right() }
                            assertEquals("Lighting scenarios", navigation.selectedTabName)
                            jBlist(xQuery {
                                and(
                                    byAccessibleName("Lighting scenarios"),
                                    byType("com.intellij.ui.components.JBList")
                                )
                            }).shouldBe(present)
                            for ((width, height) in listOf(900 to 700, 1200 to 900)) {
                                // X11 uses IDE-managed scale: dimensions here are AWT pixels, not logical UI units.
                                val expected =
                                    java.awt.Dimension((width * case.scale).toInt(), (height * case.scale).toInt())
                                setBounds(0, 0, expected.width, expected.height)
                                assertEquals(expected, component.getBounds().size, "Actual settings viewport")
                                val preview =
                                    x(xQuery {
                                        and(
                                            byAccessibleName("Preview"),
                                            byType("javax.swing.JButton")
                                        )
                                    }).component
                                val previewBounds = java.awt.Rectangle(
                                    preview.getLocationOnScreen(),
                                    java.awt.Dimension(preview.width, preview.height)
                                )
                                org.junit.jupiter.api.Assertions.assertTrue(
                                    component.getBounds().contains(previewBounds),
                                    "Preview must remain visible without scrolling the scenario form"
                                )
                                val output = Path.of("build/reports/kgboard-ui/settings-$case-${width}x$height.png")
                                Files.createDirectories(output.parent)
                                ImageIO.write(
                                    Robot().createScreenCapture(component.getBounds()),
                                    "png",
                                    output.toFile()
                                )
                            }
                            cancelButton.click()
                        }
                    }
                }
        }
    }
}
