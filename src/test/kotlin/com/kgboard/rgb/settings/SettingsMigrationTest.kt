package com.kgboard.rgb.settings

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.target.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsMigrationTest {
    @Test
    fun `blank targets migrate according to actual legacy behavior without modifying source`() {
        val source = KgBoardSettings.State()
        val migrated = SettingsMigration.app(source)
        val rules = RuntimeSettings.capture(migrated).rules
        for (id in listOf(
            "build.progress", "run.active", "test.failure", "fileSave", "debug.breakpoint",
            "pomodoro.work", "lowMemory"
        )) assertEquals(TargetSpec.All, rules.getValue(id).target, id)
        for (id in listOf("indexing", "todo")) assertEquals(TargetSpec.Unassigned, rules.getValue(id).target, id)
        assertEquals(0, source.schemaVersion)
        assertTrue(source.ruleRecords.isEmpty())
        assertEquals(migrated, SettingsMigration.app(migrated))
        val project = ProjectSettingsSnapshot.capture(SettingsMigration.project(KgBoardProjectSettings.State())).rules
        assertEquals(TargetSpec.Unassigned, project.getValue("git.conflict").target)
        assertEquals(TargetSpec.Unassigned, project.getValue("analysis.error").target)
        assertEquals(TargetSpec.All, project.getValue("git.branch").target)
    }

    @Test
    fun `malformed legacy raw indices remain editable but cannot resolve or apply`() {
        val source = KgBoardSettings.State(buildLedIndices = "0,,1")
        val migrated = SettingsMigration.app(source)
        val runtime = RuntimeSettings.capture(migrated)
        assertEquals("0,,1", (runtime.rules.getValue("build.progress").target as TargetSpec.RawIndices).text)
        assertTrue(runtime.bindings.isEmpty())
        var persisted = false
        assertThrows(IllegalArgumentException::class.java) {
            SettingsTransaction.applyApp(migrated, { persisted = true }, RuntimeEvents { })
        }
        assertFalse(persisted)
        assertEquals(0, source.schemaVersion)
    }

    @Test
    fun `unknown and duplicate rule records round trip without execution or data loss`() {
        val unknown = RuleRecord(id = "future.rule", target = TargetRecord(kind = "future-target", raw = "opaque"))
        val duplicate = RuleRecord(id = "build.failure", color = "#123456")
        val state = KgBoardSettings.State(
            schemaVersion = 1,
            ruleRecords = mutableListOf(unknown, duplicate, duplicate.copy(color = "#ABCDEF"))
        )
        val snapshot = RuntimeSettings.capture(state)
        assertFalse(snapshot.rules.containsKey("future.rule"))
        assertFalse(snapshot.rules.containsKey("build.failure"))
        assertEquals(state, snapshot.toState())
        unknown.target.raw = "external mutation"
        assertEquals("opaque", snapshot.toState().ruleRecords.first().target.raw)
    }

    @Test
    fun `draft resets retain bound containers and never mutate persisted settings`() {
        val source = KgBoardSettings.State()
        val draft = AppDraft.capture(source)
        val state = draft.state
        val rules = draft.rules
        draft.rules["build.failure"] =
            draft.rules.getValue("build.failure").copy(target = TargetSpec.NamedKeys(listOf("Esc")))
        draft.state.host = "changed"
        assertEquals("127.0.0.1", source.host)
        assertTrue(source.ruleRecords.isEmpty())
        draft.reset(source)
        assertSame(state, draft.state)
        assertSame(rules, draft.rules)
        assertEquals(TargetSpec.All, draft.rules.getValue("build.failure").target)
    }

    @Test
    fun `legacy palette and flags survive migration of every scenario family`() {
        val source = KgBoardSettings.State(
            buildFailureColor = "#102030", runColor = "#223344",
            notifyIndexingColor = "#334455", notifyLowMemoryColor = "#445566", fileSaveFlashEnabled = true,
            notifyTodoEnabled = true, pomodoroWorkColor = "#556677"
        )
        val rules = RuntimeSettings.capture(SettingsMigration.app(source)).rules
        assertEquals(java.awt.Color(0x102030), rules.getValue("build.failure").style.color)
        assertEquals(java.awt.Color(0x223344), rules.getValue("run.active").style.color)
        assertEquals(java.awt.Color(0x334455), rules.getValue("indexing").style.color)
        assertEquals(java.awt.Color(0x445566), rules.getValue("lowMemory").style.color)
        assertEquals(java.awt.Color(0x556677), rules.getValue("pomodoro.work").style.color)
        assertTrue(rules.getValue("fileSave").enabled)
        assertTrue(rules.getValue("todo").enabled)
        val project = KgBoardProjectSettings.State(
            shortcutHighlightEnabled = true,
            shortcutContexts = mutableListOf("vcs", "editing")
        )
        val projectRules = ProjectSettingsSnapshot.capture(SettingsMigration.project(project)).rules
        assertEquals(java.awt.Color.decode(project.gitUnpushedColor), projectRules.getValue("git.ahead").style.color)
        assertEquals(
            java.awt.Color.decode(project.shortcutSearchColor),
            projectRules.getValue("shortcuts.search").style.color
        )
        assertTrue(projectRules.getValue("shortcuts.vcs").enabled)
        assertTrue(projectRules.getValue("shortcuts.editing").enabled)
        assertFalse(projectRules.getValue("shortcuts.debug").enabled)
    }

    @Test
    fun `legacy form color edit preserves a typed target`() {
        val baseline = SettingsMigration.app(KgBoardSettings.State())
        baseline.ruleRecords.single { it.id == "build.failure" }.target =
            TargetRecord.from(TargetSpec.NamedKeys(listOf("Esc")))
        val draft = RuntimeSettings.capture(baseline).toState().apply { buildFailureColor = "#102030" }
        val result = RuntimeSettings.capture(LegacyEditorBridge.app(draft, baseline)).rules.getValue("build.failure")
        assertEquals(TargetSpec.NamedKeys(listOf("Esc")), result.target)
        assertEquals(java.awt.Color(0x102030), result.style.color)
    }

    @Test
    fun `apply cannot discard serial distrust learned while dialog was open`() {
        val settings = KgBoardSettings()
        val draft = RuntimeSettings.capture(settings.state).toState()
        val key = com.kgboard.rgb.device.SerialKey(1, "V", "duplicate")
        settings.mergeObservedSerials(setOf(key))
        settings.loadState(draft)
        assertEquals(setOf(key), settings.state.untrustedSerials.map { it.snapshot() }.toSet())
        val original = RuntimeState(settings = RuntimeSettings(untrustedSerials = setOf(key)))
        val applied = RuntimeReducer().reduce(original, RuntimeEvent.SettingsApplied(RuntimeSettings()), 0).state
        assertEquals(setOf(key), applied.settings.untrustedSerials)
    }

    @Test
    fun `opening a draft preserves duplicate binding records as unavailable`() {
        val id = "00000000-0000-4000-8000-000000000001"
        val first = BindingRecord(id = id, kind = "serial", serial = "one")
        val source =
            KgBoardSettings.State(schemaVersion = 1, bindingRecords = mutableListOf(first, first.copy(serial = "two")))
        val draft = AppDraft.capture(source)
        assertTrue(draft.bindings.isEmpty())
        assertEquals(source.bindingRecords, draft.toState().bindingRecords)
    }
}
