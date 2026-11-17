package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.device.*
import com.kgboard.rgb.effect.*
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.settings.SettingsPreviewSession
import com.kgboard.rgb.target.TargetSpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Color

class LightingDecisionTest {
    private val owner = ProjectId("p")
    private val policy = LightingPolicy()
    private val idle = Color(0x102030)

    @Test
    fun `ambiguous named keys retain only unambiguous pixels with an explicit reason`() {
        var snapshot = state(layer("build", StaticEffect(Color.BLUE), rule = "build.progress"))
            .editRule("build.progress", TargetSpec.NamedKeys(listOf("Esc", "A")))
        val device =
            (snapshot.connection as ConnectionState.Ready).devices.single().copy(ledNames = listOf("Esc", "A", "A"))
        snapshot = snapshot.copy(connection = ConnectionState.Ready(SessionId(1), listOf(device)))
        val result = decide(snapshot).devices.single()
        assertEquals(listOf(Color.BLUE, idle, idle), result.frame!!.colors)
        assertTrue(SuppressionReason.AMBIGUOUS_TARGET in result.rules.single { it.source == "build" }.reasons)
    }

    @Test
    fun `disabled binding and empty device explain why no ordinary frame is offered`() {
        val snapshot = state(layer("base", StaticEffect(Color.BLUE)))
        val disabled = snapshot.copy(
            settings = snapshot.settings.copy(
                bindingRecords = BindingRecords.from(
                    snapshot.settings.bindings.map { it.copy(enabled = false) })
            )
        )
        val decision = decide(disabled).devices.single()
        assertNull(decision.frame)
        assertEquals("device.disabled", decision.idleReasonKey)
        val empty = (snapshot.connection as ConnectionState.Ready).devices.single()
            .copy(numLeds = 0, ledNames = emptyList(), colors = emptyList())
        val noLeds =
            decide(snapshot.copy(connection = ConnectionState.Ready(SessionId(1), listOf(empty)))).devices.single()
        assertNull(noLeds.frame)
        assertEquals("target.emptyDevice", noLeds.idleReasonKey)
    }

    @Test
    fun `static preview covering an animated source removes animation wakes until its deadline`() {
        RuntimeHarness().use { h ->
            h.project()
            h.send(RuntimeEvent.LayerChanged(layer("pulse", PulseEffect(Color.BLUE))))
            SettingsPreviewSession(h.runtime).use { session ->
                h.drain(); session.previewColor(h.snapshotForTest(), 0, Color.RED); h.drain()
                val state = h.snapshotForTest()
                assertEquals(state.preview!!.deadlineMs, state.lastDecision!!.nextWakeAtMs)
                assertTrue(state.preview.deadlineMs > 33)
                val count = h.writer.envelopes.size
                h.advanceBy(33)
                assertEquals(count, h.writer.envelopes.size)
            }
        }
    }

    @Test
    fun `rebinding the same UUID publishes old device retirement and new device frame independently`() {
        RuntimeHarness().use { h ->
            h.project()
            h.send(RuntimeEvent.LayerChanged(layer("base", StaticEffect(Color.BLUE))))
            val original = h.snapshotForTest().settings
            val binding = original.bindings.single()
            val changed =
                binding.copy(binding = binding.binding.copy(selector = DeviceSelector.Serial(0, "Vendor", "test-1")))
            h.send(RuntimeEvent.SettingsApplied(original.copy(bindingRecords = BindingRecords.from(listOf(changed)))))
            val frames = h.snapshotForTest().lastDecision!!.devices.mapNotNull { it.frame }
            assertEquals(setOf(0, 1), frames.map { it.deviceIndex }.toSet())
            assertEquals(frames, h.writer.envelopes.takeLast(2))
            assertTrue(frames.single { it.deviceIndex == 1 }.colors.all { it == Color.BLUE })
            val count = h.writer.envelopes.size
            h.send(RuntimeEvent.FocusChanged(true))
            assertTrue(h.writer.envelopes.drop(count).none { it.deviceIndex == 0 })
        }
    }

    private fun layer(source: String, effect: RgbEffect, project: ProjectId? = owner, rule: String? = null) =
        EffectLayer(EffectKey(project, source), effect, 0, ruleId = rule)

    private fun state(vararg layers: EffectLayer, role: String = "primary") = RuntimeState(
        settings = RuntimeSettings(
            idleColor = "#102030", multiDeviceEnabled = true,
            deviceConfigs = listOf(DeviceSettings(0, role = role))
        ).withTestBindings(),
        projects = mapOf(owner to ProjectState()), activeProject = owner, focused = true,
        connection = ConnectionState.Ready(
            SessionId(1),
            listOf(testDevice().copy(ledNames = listOf("Esc", "A", "F5")))
        ),
        layers = layers.associateBy { it.key })

    private fun RuntimeState.editRule(id: String, target: TargetSpec, enabled: Boolean = true): RuntimeState {
        val rule = settings.rules.getValue(id).copy(
            enabled = enabled, target = target,
            style = settings.rules.getValue(id).style.copy(color = Color.BLUE, effect = EffectStyle.STATIC)
        )
        return copy(settings = settings.copy(ruleRecords = settings.ruleRecords.withEditedRules(mapOf(id to rule))))
    }

    private fun decide(state: RuntimeState) = policy.decide(state, 10, RenderRevision(7))

    @Test
    fun `partial coverage agrees with pixels and stable tie break`() {
        val low = CompositionLayer("build.progress", owner, 1, 0, "a", listOf(0, 1), listOf(Color.BLUE, Color.BLUE))
        val high = CompositionLayer("debug.breakpoint", owner, 2, 0, "b", listOf(1), listOf(Color.RED))
        val result = DecisionComposer.compose(Color.BLACK, 3, listOf(high, low))
        assertEquals(listOf(Color.BLUE, Color.RED, Color.BLACK), result.colors)
        val covered = result.visibility.single { it.ruleId == "build.progress" }
        assertEquals(listOf(0), covered.visible)
        assertEquals(setOf(SuppressionReason.COVERED), covered.reasons)
        val tie = high.copy(priority = 1, indices = listOf(0), source = "z")
        assertEquals(Color.RED, DecisionComposer.compose(Color.BLACK, 3, listOf(low, tie)).colors[0])
        assertEquals(
            DecisionComposer.compose(Color.BLACK, 3, listOf(tie, low)),
            DecisionComposer.compose(Color.BLACK, 3, listOf(low, tie))
        )
    }

    @Test
    fun `parallel sources sharing a rule keep independent visibility`() {
        val a = CompositionLayer("build.progress", owner, 1, 0, "build:a", listOf(0, 1), listOf(Color.BLUE, Color.BLUE))
        val b = a.copy(source = "build:b", indices = listOf(1), colors = listOf(Color.RED))
        val result = DecisionComposer.compose(idle, 3, listOf(b, a))
        assertEquals(2, result.visibility.size)
        assertEquals(listOf(0), result.visibility.single { it.source == "build:a" }.visible)
        assertEquals(listOf(1), result.visibility.single { it.source == "build:b" }.visible)
    }

    @ParameterizedTest
    @ValueSource(strings = ["primary", "mirror", "indicator", "ambient"])
    fun `device roles explain the actual pixels`(role: String) {
        val global = layer("global", StaticEffect(Color.RED, priority = 2))
        val point = layer("point", StaticEffect(Color.BLUE, priority = 1, target = EffectTarget.SingleLed(0)))
        val result = decide(state(global, point, role = role)).devices.single()
        val colors = result.frame!!.colors
        when (role) {
            "indicator" -> {
                assertEquals(listOf(Color.BLUE, idle, idle), colors)
                assertTrue(result.rules.single { it.source == "global" }.reasons.contains(SuppressionReason.ROLE))
                assertEquals(listOf(0), result.rules.single { it.source == "point" }.visible)
            }

            "ambient" -> {
                assertEquals(List(3) { idle }, colors)
                assertTrue(result.rules.all { SuppressionReason.ROLE in it.reasons && it.visible.isEmpty() })
            }

            else -> {
                assertEquals(List(3) { Color.RED }, colors)
                assertTrue(SuppressionReason.COVERED in result.rules.single { it.source == "point" }.reasons)
            }
        }
    }

    @Test
    fun `background operation facts are retained as suppressed explanations`() {
        val other = ProjectId("q")
        val activeOp = OperationState(OperationId("a"), OperationKind.RUN, OperationPhase.RUNNING)
        val backgroundOp = OperationState(OperationId("b"), OperationKind.BUILD, OperationPhase.RUNNING)
        val initial = state().copy(
            projects = mapOf(
                owner to ProjectState(operations = mapOf(activeOp.id to activeOp)),
                other to ProjectState(operations = mapOf(backgroundOp.id to backgroundOp))
            )
        )
        val result = decide(initial).devices.single()
        val hidden = result.rules.single { it.owner == other && it.ruleId == "build.progress" }
        assertTrue(SuppressionReason.BACKGROUND_PROJECT in hidden.reasons)
        assertTrue(hidden.visible.isEmpty())
        assertTrue(result.rules.any { it.owner == owner && it.visible.isNotEmpty() })
    }

    @Test
    fun `missing and ambiguous bindings explain without producing a frame`() {
        val initial = state(layer("base", StaticEffect(Color.BLUE)))
        val missing =
            decide(initial.copy(connection = ConnectionState.Ready(SessionId(2), emptyList()))).devices.single()
        assertNull(missing.frame)
        assertTrue(SuppressionReason.MISSING_DEVICE in missing.rules.single().reasons)
        val device = (initial.connection as ConnectionState.Ready).devices.single()
        val ambiguous = decide(
            initial.copy(
                connection = ConnectionState.Ready(
                    SessionId(2),
                    listOf(device, device)
                )
            )
        ).devices.single()
        assertNull(ambiguous.frame)
        assertTrue(SuppressionReason.AMBIGUOUS_DEVICE in ambiguous.rules.single().reasons)
    }

    @Test
    fun `disabled rule and unassigned or stale targets never fall back to all`() {
        val base = state(layer("build", StaticEffect(Color.BLUE), rule = "build.progress"))
        val variants = listOf(
            base.editRule("build.progress", TargetSpec.All, false) to SuppressionReason.DISABLED_RULE,
            base.editRule("build.progress", TargetSpec.Unassigned) to SuppressionReason.UNASSIGNED,
            base.editRule("build.progress", TargetSpec.RawIndices("0", "obsolete")) to SuppressionReason.STALE_LAYOUT
        )
        for ((snapshot, reason) in variants) {
            val result = decide(snapshot).devices.single()
            assertEquals(List(3) { idle }, result.frame!!.colors)
            assertTrue(reason in result.rules.single { it.source == "build" }.reasons)
            assertEquals(
                when (reason) {
                    SuppressionReason.DISABLED_RULE -> "scenario.disabled"
                    SuppressionReason.STALE_LAYOUT -> "target.confirmLayout"
                    else -> "target.unassigned"
                }, result.idleReasonKey
            )
            assertTrue(result.rules.all { it.visible.isEmpty() })
        }
    }

    @Test
    fun `partial named target paints and reports only its available LEDs`() {
        val snapshot = state(layer("build", StaticEffect(Color.BLUE), rule = "build.progress"))
            .editRule("build.progress", TargetSpec.NamedKeys(listOf("Esc", "missing")))
        val result = decide(snapshot).devices.single()
        assertEquals(listOf(Color.BLUE, idle, idle), result.frame!!.colors)
        assertEquals(listOf(0), result.rules.single { it.source == "build" }.visible)
        assertEquals(listOf(0), result.rules.single { it.source == "build" }.requested)
        assertTrue(SuppressionReason.PARTIAL_TARGET in result.rules.single { it.source == "build" }.reasons)
    }

    @ParameterizedTest
    @ValueSource(strings = ["disabled", "unfocused", "shutdown"])
    fun `global policy overrides explain suppression and stop animation`(mode: String) {
        var snapshot = state(layer("pulse", PulseEffect(Color.BLUE)))
        val reason = when (mode) {
            "disabled" -> {
                snapshot = snapshot.copy(settings = snapshot.settings.copy(enabled = false)); SuppressionReason.DISABLED
            }

            "unfocused" -> {
                snapshot = snapshot.copy(focused = false); SuppressionReason.FOCUS_DIM
            }

            else -> {
                snapshot = snapshot.copy(shuttingDown = true); SuppressionReason.SHUTDOWN
            }
        }
        val result = decide(snapshot)
        assertEquals(
            policy.retiredFrame(snapshot, (snapshot.connection as ConnectionState.Ready).devices.single()),
            result.devices.single().frame!!.colors
        )
        assertTrue(reason in result.devices.single().rules.single { it.source == "pulse" }.reasons)
        assertTrue(result.devices.single().rules.all { it.visible.isEmpty() })
        assertNull(result.nextWakeAtMs)
    }

    @Test
    fun `preview has its own visibility and suppression matches the offered frame`() {
        RuntimeHarness(RuntimeSettings(idleColor = "#102030").withTestBindings()).use { h ->
            h.project()
            h.send(RuntimeEvent.LayerChanged(layer("base", StaticEffect(Color.BLUE, priority = Int.MAX_VALUE))))
            SettingsPreviewSession(h.runtime).use { session ->
                h.drain()
                session.previewColor(h.snapshotForTest(), 0, Color.RED, listOf(0)); h.drain()
                val decision = h.snapshotForTest().lastDecision!!
                val device = decision.devices.single { it.deviceIndex == 0 }
                assertEquals(listOf(Color.RED, Color.BLUE, Color.BLUE), device.frame!!.colors)
                assertEquals(h.lastFrame(), device.frame.colors)
                assertEquals(listOf(0), device.rules.single { it.ruleId == "preview" }.visible)
                assertEquals(listOf(1, 2), device.rules.single { it.source == "base" }.visible)
                assertTrue(SuppressionReason.PREVIEW in device.rules.single { it.source == "base" }.reasons)
            }
        }
    }

    @Test
    fun `fully covered animation does not request animation wakes`() {
        val result = decide(
            state(
                layer("pulse", PulseEffect(Color.BLUE, priority = 1)),
                layer("cover", StaticEffect(Color.RED, priority = 2))
            )
        )
        assertNull(result.nextWakeAtMs)
        assertTrue(SuppressionReason.COVERED in result.devices.single().rules.single { it.source == "pulse" }.reasons)
    }

    @Test
    fun `preview only retirement is published once and never retained as an output owner`() {
        RuntimeHarness().use { h ->
            h.project()
            SettingsPreviewSession(h.runtime).use { session ->
                h.drain()
                session.previewColor(h.snapshotForTest(), 1, Color.RED); h.drain()
                session.clear(); h.drain()
                val decision = h.snapshotForTest().lastDecision!!
                assertTrue(decision.devices.any { it.deviceIndex == 1 && it.frame != null })
                val frames = decision.devices.mapNotNull { it.frame }
                assertEquals(frames, h.writer.envelopes.takeLast(frames.size))
                val count = h.writer.envelopes.size
                h.send(RuntimeEvent.FocusChanged(true))
                assertTrue(h.writer.envelopes.drop(count).none { it.deviceIndex == 1 })
            }
        }
    }

    @Test
    fun `idle analysis distinctions never invent a clean analysis signal`() {
        val variants = listOf(
            EditorState(applicable = true, analysis = AnalysisResult.Unknown) to "analysis.unknown",
            EditorState(analysis = AnalysisResult.NotApplicable) to "analysis.notApplicable"
        )
        for ((editor, reason) in variants) {
            val result = decide(state().copy(projects = mapOf(owner to ProjectState(editor = editor)))).devices.single()
            assertEquals(reason, result.idleReasonKey)
            assertFalse(result.rules.any { it.ruleId == "analysis.clean" })
        }
        val indexing = decide(
            state().copy(
                projects = mapOf(
                    owner to ProjectState(
                        indexing = true,
                        editor = EditorState(applicable = true, analysis = AnalysisResult.Ready(false, false))
                    )
                )
            )
        ).devices.single()
        assertFalse(indexing.rules.any { it.ruleId == "analysis.clean" && it.visible.isNotEmpty() })
    }

    @Test
    fun `actor offers immutable envelopes from the published decision with increasing revisions`() {
        RuntimeHarness().use { h ->
            h.project()
            val before = h.snapshotForTest().lastDecision!!.revision
            h.send(RuntimeEvent.LayerChanged(layer("blue", StaticEffect(Color.BLUE))))
            val current = h.snapshotForTest().lastDecision!!
            assertTrue(current.revision.value > before.value)
            assertEquals(
                current.devices.mapNotNull { it.frame },
                h.writer.envelopes.takeLast(current.devices.count { it.frame != null })
            )
            val colors = current.devices.first().frame!!.colors
            assertThrows(UnsupportedOperationException::class.java) { (colors as MutableList<Color>).clear() }
            assertTrue(h.errors.isEmpty())
        }
    }
}
