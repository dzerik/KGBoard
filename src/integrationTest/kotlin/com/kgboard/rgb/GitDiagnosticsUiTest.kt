package com.kgboard.rgb

import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.*
import com.intellij.driver.sdk.ui.components.elements.*
import com.intellij.driver.sdk.ui.components.common.*
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.kgboard.rgb.testing.ControllerFixtureSpec
import com.kgboard.rgb.testing.OpenRgbSdkSimulator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

class GitDiagnosticsUiTest {
    @Test
    fun `native Git rows show actual ahead and dirty facts and Copy omits branch and path`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            lateinit var project: Path
            val context = IdeTestContext.create("kgboard-git-diagnostics", sdk.endpoint, prepareProject = { root ->
                project = root
                Files.writeString(root.resolve(".gitignore"), ".idea/\nout/\n*.iml\n")
                Files.writeString(
                    root.resolve(".idea/vcs.xml"),
                    """<project version="4"><component name="VcsDirectoryMappings"><mapping directory="${'$'}PROJECT_DIR${'$'}" vcs="Git" /></component></project>"""
                )
                git(root, "init", "-b", "native-fixture-secret")
                git(root, "add", "."); git(root, "commit", "-m", "Initial fixture")
                val remote = Files.createTempDirectory("kgboard-ui-bare-")
                git(remote, "init", "--bare")
                git(root, "remote", "add", "origin", remote.toString()); git(root, "push", "-u", "origin", "HEAD")
                Files.writeString(root.resolve("ahead.txt"), "local only\n")
                git(root, "add", "ahead.txt"); git(root, "commit", "-m", "Ahead fixture")
            })
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                val driver = this
                ideFrame {
                    invokeAction("KGBoard.OpenSettings")
                    settingsDialog {
                        x {
                            byAttribute(
                                "name",
                                "connection.diagnostics"
                            )
                        }.apply { setFocus(); shouldBe(focusOwner); keyboard { space() } }
                        driver.ui.dialog(title = "Diagnostics") {
                            val tabs = tabbedPane { byAttribute("name", "diagnostics.sections") }
                            tabs.setFocus(); tabs.shouldBe(focusOwner); tabs.keyboard { right(); right(); right(); right(); right() }
                            assertEquals("Git repositories", tabs.selectedTabName)
                            val rows = x { byAttribute("name", "diagnostics.git") }
                            rows.waitContainsText("Ahead"); rows.waitContainsText("+1 / −0")
                            Files.writeString(project.resolve("ahead.txt"), "changed while IDE is open\n")
                            rows.waitContainsText("Uncommitted changes")
                            x {
                                byAttribute(
                                    "name",
                                    "diagnostics.copy"
                                )
                            }.apply { setFocus(); shouldBe(focusOwner); keyboard { space() } }
                            var copied = ""
                            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                            while (System.nanoTime() < until) {
                                copied =
                                    runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrDefault(
                                        ""
                                    )
                                if (copied.contains("git[1].state=dirty")) break
                                Thread.sleep(50)
                            }
                            assertTrue(copied.contains("git[1].state=dirty"), copied)
                            assertFalse(copied.contains("native-fixture-secret")); assertFalse(copied.contains(project.toString()))
                            assertFalse(copied.contains("ahead.txt"))
                            val evidence = Path.of("build/reports/kgboard-ui/git-native.txt")
                            Files.createDirectories(evidence.parent)
                            Files.writeString(
                                evidence,
                                "Actual Git +1/−0 Ahead, then Dirty displayed; privacy-filtered Copy checked; SDK writes=${sdk.recordedWriteCount}\n"
                            )
                            x { and(byType("javax.swing.JButton"), byVisibleText("OK")) }.apply {
                                setFocus(); shouldBe(
                                focusOwner
                            ); keyboard { space() }
                            }
                        }
                        keyboard { escape() }
                    }
                }
                assertEquals(0, sdk.recordedWriteCount)
            }
        }
    }

    private fun git(root: Path, vararg args: String) {
        val command = listOf(
            "git",
            "-c",
            "user.name=KGBoard fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "-c",
            "commit.gpgSign=false",
            "-c",
            "core.hooksPath=/dev/null"
        ) + args
        val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
        builder.environment()["GIT_CONFIG_NOSYSTEM"] = "1"; builder.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        val process = builder.start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Local Git fixture timed out")
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
    }
}
