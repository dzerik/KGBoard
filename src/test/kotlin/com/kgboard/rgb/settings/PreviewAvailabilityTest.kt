package com.kgboard.rgb.settings

import com.kgboard.rgb.device.*
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.target.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

class PreviewAvailabilityTest {
    private fun ready(): RuntimeState = RuntimeReducer().reduce(
        RuntimeState(
            focused = true, connection = ConnectionState.Ready(SessionId(1), testDevices())
        ),
        RuntimeEvent.BeginSettings(1), 0
    ).state

    @Test
    fun `unavailable previews explain the failing prerequisite without substituting all LEDs`() {
        val state = ready()
        val choice = state.capturedChoice()
        val request = PreviewRequest(1, choice, PreviewContent.DeviceTest)
        val appearance = ScenarioCatalog.byId("build.progress")!!.default
        fun scenario(
            rule: String = "build.progress", style: RuleStyle = appearance.style,
            target: TargetSpec = TargetSpec.All
        ) = request.copy(
            content = PreviewContent.Scenario(
                rule,
                appearance.copy(target = target, style = style)
            )
        )

        val empty = testDevice(0)
        val emptyState = state.copy(connection = ConnectionState.Ready(SessionId(1), listOf(empty)))
        val duplicate = testDevice().copy(location = "same-port")
        val duplicateState = state.copy(connection = ConnectionState.Ready(SessionId(1), listOf(duplicate, duplicate)))
        val cases = listOf(
            Triple(state.copy(settingsSession = null), request, "preview.settingsClosed"),
            Triple(state.copy(settings = state.settings.copy(enabled = false)), request, "preview.applyEnableFirst"),
            Triple(state.copy(shuttingDown = true), request, "preview.shuttingDown"),
            Triple(state.copy(connection = ConnectionState.Disconnected), request, "preview.notConnected"),
            Triple(state, request.copy(choice = choice.copy(index = 99)), "preview.selectDevice"),
            Triple(emptyState, request.copy(choice = emptyState.capturedChoice()), "preview.emptyDevice"),
            Triple(state, request.copy(choice = choice.copy(fingerprint = "old")), "preview.staleLayout"),
            Triple(
                state,
                request.copy(choice = choice.copy(token = choice.token.copy(revision = 2))),
                "preview.staleDevice"
            ),
            Triple(duplicateState, request.copy(choice = duplicateState.capturedChoice()), "preview.ambiguousDevice"),
            Triple(state, scenario("unknown"), "preview.unknownScenario"),
            Triple(state, scenario("git.dirty"), "preview.wrongScope"),
            Triple(state, scenario(style = appearance.style.copy(periodMs = 0)), "preview.invalidTiming"),
            Triple(
                state,
                scenario(style = appearance.style.copy(effect = EffectStyle.FLASH, durationMs = 0)),
                "preview.emptyDuration"
            )
        )
        cases.forEach { (snapshot, value, reason) ->
            assertEquals(PreviewAvailability(false, reason), PreviewAvailability.evaluate(snapshot, value), reason)
        }
        for (target in listOf(TargetSpec.Unassigned, TargetSpec.RawIndices("1", null), TargetSpec.Group("missing"))) {
            assertFalse(PreviewAvailability.evaluate(state, scenario(target = target)).allowed, target.toString())
        }
        val project = state.copy(
            settingsSession = state.settingsSession!!.copy(
                owners = mapOf(1L to SettingsOwner(SettingsScope.PROJECT, ProjectId("closed")))
            )
        )
        assertEquals("preview.projectClosed", PreviewAvailability.evaluate(project, request).reasonKey)
    }

    @Test
    fun `device test is white thirty percent for exactly two seconds and creates no ordinary binding`() {
        RuntimeHarness(RuntimeSettings(idleColor = "#102030")).use { h ->
            h.project()
            val session = SettingsPreviewSession(RuntimeEvents { h.send(it) })
            session.preview(h.snapshotForTest().capturedChoice(1), PreviewContent.DeviceTest)
            assertEquals(List(3) { Color(77, 77, 77) }, h.lastFrame(1))
            assertTrue(h.snapshotForTest().settings.bindings.isEmpty())
            assertEquals(2000L, LightingPolicy().nextWakeAt(h.snapshotForTest(), 0))
            h.advanceBy(1999)
            assertEquals(Color(77, 77, 77), h.lastFrame(1)[0])
            h.advanceBy(1)
            assertNull(h.snapshotForTest().preview)
            assertEquals(List(3) { Color(0x10, 0x20, 0x30) }, h.lastFrame(1))
            session.close()
        }
    }

    @Test
    fun `scenario preview renders draft pulse and flash targets using draft groups`() {
        RuntimeHarness(RuntimeSettings(idleColor = "#000000")).use { h ->
            h.project()
            val session = SettingsPreviewSession(RuntimeEvents { h.send(it) })
            val choice = h.snapshotForTest().capturedChoice()
            val appearance = ScenarioCatalog.byId("build.progress")!!.default.copy(
                target = TargetSpec.Group("draft"), style = RuleStyle(Color.RED, EffectStyle.PULSE, 2000, 1000)
            )
            session.preview(
                choice, PreviewContent.Scenario("build.progress", appearance), mapOf(
                    "draft" to GroupDefinition("draft", "Draft", TargetSpec.RawIndices("1", choice.fingerprint))
                )
            )
            val initial = h.lastFrame()
            assertEquals(Color.BLACK, initial[0]); assertTrue(initial[1].red in 1..254)
            assertEquals(33L, LightingPolicy().nextWakeAt(h.snapshotForTest(), 0))
            h.advanceBy(500)
            assertTrue(h.lastFrame()[1].red > initial[1].red)
            assertEquals(Color.BLACK, h.lastFrame()[2])
            val flash = appearance.copy(
                target = TargetSpec.RawIndices("2", choice.fingerprint),
                style = RuleStyle(Color.BLUE, EffectStyle.FLASH, 100, 1000)
            )
            session.preview(choice, PreviewContent.Scenario("build.success", flash))
            assertEquals(Color.BLUE, h.lastFrame()[2]); assertEquals(Color.BLACK, h.lastFrame()[1])
            h.advanceBy(100)
            assertEquals(List(3) { Color.BLACK }, h.lastFrame())
            assertNull(h.snapshotForTest().preview)
            session.close()
        }
    }

    @Test
    fun `rejected stale selection cannot replace another owners valid preview`() {
        RuntimeHarness().use { h ->
            h.project()
            val first = SettingsPreviewSession(RuntimeEvents { h.send(it) }, 1)
            val second = SettingsPreviewSession(RuntimeEvents { h.send(it) }, 2)
            val choice = h.snapshotForTest().capturedChoice()
            second.preview(choice, PreviewContent.DeviceTest)
            first.preview(choice.copy(token = choice.token.copy(revision = 99)), PreviewContent.DeviceTest)
            assertEquals(2L, h.snapshotForTest().preview?.owner)
            assertEquals("preview.staleDevice", h.snapshotForTest().previewFeedback[1]?.reasonKey)
            first.close()
            assertEquals(Color(77, 77, 77), h.lastFrame()[0])
            second.close()
        }
    }

    @Test
    fun `queued preview freezes draft names and groups before the editor changes them`() {
        val state = ready().copy(
            connection = ConnectionState.Ready(
                SessionId(1), listOf(
                    testDevice().copy(ledNames = listOf("A", "B", "C"))
                )
            )
        )
        val pending = mutableListOf<RuntimeEvent>()
        val session = SettingsPreviewSession(RuntimeEvents { pending.add(it) }, 1)
        val names = mutableListOf("A")
        val groups = mutableMapOf("draft" to GroupDefinition("draft", "Draft", TargetSpec.NamedKeys(names)))
        val draft = ScenarioCatalog.byId("build.progress")!!.default.copy(target = TargetSpec.Group("draft"))
        session.preview(state.capturedChoice(), PreviewContent.Scenario("build.progress", draft), groups)
        names[0] = "C"; groups.clear()
        val accepted = RuntimeReducer().reduce(state, pending.last(), 0).state
        assertEquals(listOf(0), accepted.preview?.indices)
        session.close()
    }
}
