package com.kgboard.rgb

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.*
import com.intellij.driver.sdk.ui.*
import com.intellij.driver.sdk.ui.components.common.ideFrame
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
interface BuildProbeRef {
    fun prepare(jdkHome: String)
    fun enableAutoMake()
    fun editSource()
    fun importGradle(gradleHome: String)
    fun runGradleTask(task: String)
    fun editGradleSource()
    fun runMavenValidate()
    fun getObservations(): List<String>
}

class BuildPipelinesUiTest {
    @Test
    fun `observe actual manual and automatic JPS build root coverage`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            val context = IdeTestContext.create("kgboard-build-jps", sdk.endpoint, lightingEnabled = true)
            IdeTestContext.installProbe(context)
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                val probe =
                    withContext(OnDispatcher.EDT) { service<BuildProbeRef>(singleProject()).also { it.prepare("") } }
                waitForIndicators(2.minutes)
                val before = probe.getObservations().size
                ideFrame { invokeAction("CompileDirty") }
                await(probe, before, "manual JPS completion") { rows -> rows.any { it.startsWith("COMPILER|manual|") } }
                waitForIndicators(2.minutes)
                val manual = probe.getObservations().drop(before)
                evidence("jps-manual", manual)
                assertRuntimePair(manual, "SUCCESS")
                val automaticStart = probe.getObservations().size
                withContext(OnDispatcher.EDT) { probe.enableAutoMake(); probe.editSource() }
                ideFrame { invokeAction("SaveAll") }
                await(
                    probe,
                    automaticStart,
                    "automatic JPS completion"
                ) { rows -> rows.any { it.startsWith("COMPILER|auto|") } }
                waitForIndicators(2.minutes)
                val automatic = probe.getObservations().drop(automaticStart)
                evidence("jps-auto", automatic)
                assertRuntimePair(automatic, "NOT_EXECUTED")
                assertEquals(
                    1,
                    manual.count { it.startsWith("BVM|start|") && it.contains("|parent=0|") },
                    manual.joinToString("\n")
                )
                assertEquals(
                    1,
                    manual.count { it.startsWith("BVM|finish|") && it.contains("|parent=0|") },
                    manual.joinToString("\n")
                )
                assertEquals(
                    0,
                    automatic.count { it.startsWith("BVM|start|") },
                    "Pinned JPS automatic path has no BuildView lifecycle"
                )
                assertEquals(
                    1,
                    automatic.count { it.startsWith("MANAGER|start|") && it.contains("|auto=true") },
                    automatic.joinToString("\n")
                )
                assertEquals(
                    1,
                    automatic.count { it.startsWith("MANAGER|finish|") && it.contains("|auto=true") },
                    automatic.joinToString("\n")
                )
                assertEquals(0, sdk.recordedWriteCount, "Observation fixture has disabled/unassigned lighting")
            }
        }
    }

    @Test
    fun `observe actual Gradle standalone delegated and import pipelines`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            val context = IdeTestContext.create(
                "kgboard-build-gradle",
                sdk.endpoint,
                templateName = "gradle",
                lightingEnabled = true
            )
            IdeTestContext.installProbe(context)
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                val probe =
                    withContext(OnDispatcher.EDT) { service<BuildProbeRef>(singleProject()).also { it.prepare("") } }
                val importing = probe.getObservations().size
                withContext(OnDispatcher.EDT) { probe.importGradle(System.getProperty("kgboard.ui.gradleHome")) }
                await(probe, importing, "Gradle import") { it.any { row -> row.startsWith("CONTROL|gradle-import|") } }
                waitForIndicators(2.minutes)
                val imported = probe.getObservations().drop(importing); evidence("gradle-import", imported)
                assertTrue(imported.contains("CONTROL|gradle-import|true"), imported.joinToString("\n"))
                assertTrue(
                    imported.none { it.startsWith("BVM|start|") },
                    "Import must not be a build: " + imported.joinToString("\n")
                )
                val standaloneStart = probe.getObservations().size
                withContext(OnDispatcher.EDT) { probe.runGradleTask("classes") }
                await(
                    probe,
                    standaloneStart,
                    "standalone Gradle"
                ) { it.any { row -> row.startsWith("CONTROL|gradle-task|") } }
                waitForIndicators(2.minutes)
                val standalone = probe.getObservations().drop(standaloneStart); evidence(
                "gradle-standalone",
                standalone
            )
                assertTrue(
                    standalone.contains("CONTROL|gradle-task|success"),
                    standalone.joinToString("\n")
                ); assertPair(standalone)
                val delegatedStart = probe.getObservations().size
                withContext(OnDispatcher.EDT) { probe.editGradleSource() }
                ideFrame { invokeAction("SaveAll"); invokeAction("CompileDirty") }
                await(probe, delegatedStart, "delegated Gradle") { it.any { row -> row.startsWith("TASK|finish|") } }
                waitForIndicators(2.minutes)
                val delegated = probe.getObservations().drop(delegatedStart); evidence("gradle-delegated", delegated)
                assertPair(delegated)
                assertTrue(
                    delegated.any { it.startsWith("EXTERNAL|start|") && it.contains("|GRADLE|EXECUTE_TASK") },
                    "Must actually execute Gradle: " + delegated.joinToString("\n")
                )
                assertEquals(0, sdk.recordedWriteCount)
            }
        }
    }

    @Test
    fun `observe actual offline Maven goal build roots`() {
        OpenRgbSdkSimulator(5, listOf(ControllerFixtureSpec())).use { sdk ->
            val context = IdeTestContext.create(
                "kgboard-build-maven",
                sdk.endpoint,
                templateName = "maven",
                lightingEnabled = true
            )
            IdeTestContext.installProbe(context)
            context.runIdeWithDriver().useDriverAndCloseIde {
                waitForIndicators(2.minutes)
                val probe =
                    withContext(OnDispatcher.EDT) { service<BuildProbeRef>(singleProject()).also { it.prepare("") } }
                val start = probe.getObservations().size
                withContext(OnDispatcher.EDT) { probe.runMavenValidate() }
                await(probe, start, "offline Maven validate") { it.any { row -> row == "CONTROL|maven|finished" } }
                waitForIndicators(2.minutes)
                val rows = probe.getObservations().drop(start); evidence("maven-validate", rows); assertPair(rows)
                assertTrue(rows.any {
                    (it.startsWith("BVM|finish|") || it.startsWith("RUNVIEW|finish|")) && it.contains(
                        "SuccessResult"
                    )
                }, rows.joinToString("\n"))
                assertEquals(0, sdk.recordedWriteCount)
            }
        }
    }

    private fun assertPair(rows: List<String>) {
        assertRuntimePair(rows, "SUCCESS")
        fun ids(phase: String) =
            rows.filter { (it.startsWith("BVM|$phase|") || it.startsWith("RUNVIEW|$phase|")) && it.contains("|parent=0|") }
                .map { it.split('|')[2] }.toSet()
        assertEquals(1, ids("start").size, rows.joinToString("\n"))
        assertEquals(ids("start"), ids("finish"), rows.joinToString("\n"))
    }

    private fun assertRuntimePair(rows: List<String>, outcome: String) {
        val operations = rows.filter { it.startsWith("RUNTIME|") }.map { it.split('|') }
        assertEquals(2, operations.size, rows.joinToString("\n"))
        assertEquals(1, operations.map { it[1] }.toSet().size, rows.joinToString("\n"))
        assertTrue(operations.any { it[2] == "RUNNING" && it[4] == "1" }, rows.joinToString("\n"))
        assertTrue(
            operations.any { it[2] == "TERMINATED" && it[3] == outcome && it[4] == "2" },
            rows.joinToString("\n")
        )
    }

    private fun await(
        probe: BuildProbeRef,
        from: Int,
        label: String,
        complete: (List<String>) -> Boolean
    ): List<String> {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        var rows = emptyList<String>()
        while (System.nanoTime() < deadline) {
            rows = probe.getObservations().drop(from)
            if (complete(rows)) return rows
            Thread.sleep(100)
        }
        evidence(label.replace(' ', '-') + "-timeout", rows)
        fail<Unit>("No $label. Observed:\n" + rows.joinToString("\n")); return rows
    }

    private fun evidence(name: String, rows: List<String>) {
        val path = Path.of("build/reports/kgboard-ui/build-$name.txt")
        Files.createDirectories(path.parent); Files.write(path, rows)
    }
}
