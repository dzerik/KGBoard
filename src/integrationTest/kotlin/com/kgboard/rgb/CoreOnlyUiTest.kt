package com.kgboard.rgb

import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.*
import com.intellij.driver.sdk.ui.components.common.*
import com.intellij.driver.sdk.ui.components.elements.*
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

@Remote("com.intellij.openapi.extensions.PluginId")
interface PluginIdentityRef

@Remote("com.intellij.openapi.extensions.PluginId")
interface PluginIdentityFactoryRef {
    fun getId(id: String): PluginIdentityRef
}

// Read-only test observation; this internal SDK API is excluded from plugin packaging.
@Remote("com.intellij.ide.plugins.PluginManagerCore")
interface PluginDisabledStateRef {
    fun isDisabled(id: PluginIdentityRef): Boolean
    fun isLoaded(id: PluginIdentityRef): Boolean
}

class CoreOnlyUiTest {
    data class Case(
        val id: String, val disabled: List<String>, val unavailable: List<String>,
        val requiredByProduct: List<String> = emptyList()
    ) {
        override fun toString() = id
    }

    companion object {
        private fun productCode(): String {
            val info =
                Files.readString(java.nio.file.Path.of(System.getProperty("kgboard.ui.idePath"), "product-info.json"))
            val product = Regex("\"productCode\"\\s*:\\s*\"([^\"]+)\"").findAll(info).single().groupValues[1]
            return product
        }

        @JvmStatic
        fun cases(): List<Case> {
            // Pinned IDEA declares Java essential; use products without Java for the absence check.
            val java = if (productCode() in setOf("IU", "IC")) emptyList() else listOf("com.intellij.java")
            val cases = mutableListOf(Case("all-enabled", emptyList(), emptyList()))
            if (java.isNotEmpty()) cases.add(Case("no-java", java, emptyList()))
            cases.add(Case("no-git", listOf("Git4Idea"), listOf("Git")))
            // Pinned WS/PY essential language backends require smRunner; the loader retains it.
            val smRequired =
                if (productCode() in setOf("WS", "PY")) listOf("com.intellij.platform.testRunner") else emptyList()
            val smUnavailable = if (smRequired.isEmpty()) listOf("Tests (SM)") else emptyList()
            cases.add(
                Case(
                    if (smRequired.isEmpty()) "no-sm" else "sm-required-by-product",
                    listOf("com.intellij.platform.testRunner"), smUnavailable, smRequired
                )
            )
            cases.add(
                Case(
                    if (smRequired.isEmpty()) "no-optional" else "no-removable-integrations",
                    java + listOf("Git4Idea", "com.intellij.platform.testRunner"),
                    listOf("Git") + smUnavailable, smRequired
                )
            )
            return cases.filter { System.getenv("KGBOARD_CORE_CASE")?.let { filter -> it.id == filter } ?: true }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `core settings and diagnostics load with optional plugins disabled`(case: Case) {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            val context = IdeTestContext.create("kgboard-core-${case.id}", sdk.endpoint)
            val disabledFile =
                java.nio.file.Path.of("build/reports/kgboard-ui/core-${case.id}-disabled.txt").toAbsolutePath()
            Files.createDirectories(disabledFile.parent)
            Files.writeString(disabledFile, case.disabled.joinToString("\n", postfix = "\n"))
            context.applyVMOptionsPatch {
                addSystemProperty("disabled.plugins.file.path", disabledFile.toString())
                addSystemProperty("idea.ignore.disabled.plugins", false)
            }
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                for (id in case.disabled) {
                    val identity = utility<PluginIdentityFactoryRef>().getId(id)
                    assertTrue(
                        utility<PluginDisabledStateRef>().isDisabled(identity),
                        "$id must retain the requested disabled flag"
                    )
                    assertEquals(
                        id in case.requiredByProduct,
                        utility<PluginDisabledStateRef>().isLoaded(identity),
                        "$id actual loading must match the pinned product's essential dependency constraint"
                    )
                }
                if (productCode() !in setOf("IU", "IC")) {
                    assertFalse(
                        utility<PluginDisabledStateRef>().isLoaded(
                            utility<PluginIdentityFactoryRef>().getId("com.intellij.java")
                        ),
                        "The launched core must actually have no loaded Java plugin"
                    )
                }
                val driver = this
                ideFrame {
                    invokeAction("KGBoard.OpenSettings")
                    settingsDialog {
                        x { byAttribute("name", "kgboard.navigation") }.shouldBe(present)
                        x { byAttribute("name", "connection.diagnostics") }.activate()
                        driver.ui.dialog(title = "Diagnostics") {
                            val sections = tabbedPane { byAttribute("name", "diagnostics.sections") }
                            sections.setFocus(); sections.shouldBe(focusOwner)
                            sections.keyboard { right(); right(); right(); right() }
                            assertEquals("Integrations", sections.selectedTabName)
                            val capabilities = x { byAttribute("name", "diagnostics.capabilities") }
                            capabilities.waitContainsText("Debugger: Available")
                            case.unavailable.forEach { capabilities.waitContainsText("$it: Unavailable") }
                            listOf("Git", "Tests (SM)").filterNot { it in case.unavailable }
                                .forEach { capabilities.waitContainsText("$it: Available") }
                            x { and(byType("javax.swing.JButton"), byVisibleText("OK")) }.activate()
                        }
                        keyboard { escape() }
                    }
                }
                assertEquals(0, sdk.recordedWriteCount, "Core-only launch must not send unassigned lighting frames")
            }
        }
    }

    private fun UiComponent.activate() {
        shouldBe(enabled); setFocus(); shouldBe(focusOwner); keyboard { space() }
    }

}
