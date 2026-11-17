package com.kgboard.rgb.settings

import com.kgboard.rgb.preset.KeyGroupService
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.ScenarioCatalog
import com.kgboard.rgb.target.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Properties

class SettingsDraftTest {
    @Test
    fun `editing and resetting application draft leaves managed state detached`() {
        val managed = KgBoardSettings.State()
        val original = RuntimeSettings.capture(managed)
        val draft = AppDraft.capture(managed)
        draft.rules["build.failure"] =
            draft.rules.getValue("build.failure").copy(target = TargetSpec.NamedKeys(listOf("Esc")))
        assertEquals(original, RuntimeSettings.capture(managed))
        draft.reset(managed)
        assertEquals(TargetSpec.All, draft.rules.getValue("build.failure").target)
        assertEquals(original, RuntimeSettings.capture(managed))
    }

    @Test
    fun `group rename preserves identity and delete clears only referencing draft targets`() {
        val managed = KeyGroupService.GroupState()
        val draft = AppDraft.capture(KgBoardSettings.State())
        draft.groups = managed.copyDeep()
        val groups = GroupDraft(draft)
        val first = groups.create("Build keys", TargetSpec.NamedKeys(listOf("Esc")))
        val second = groups.create("Build keys", TargetSpec.Group(first))
        assertNotEquals(first, second)
        draft.rules["build.progress"] = draft.rules.getValue("build.progress").copy(target = TargetSpec.Group(first))
        groups.rename(first, "Renamed")
        assertEquals(first, groups.definitions().getValue(first).id)
        assertEquals("Renamed", groups.definitions().getValue(first).name)
        assertEquals(setOf("build.progress"), groups.delete(first))
        assertEquals(TargetSpec.Unassigned, draft.rules.getValue("build.progress").target)
        assertEquals(TargetSpec.Unassigned, groups.definitions().getValue(second).target)
        assertEquals(TargetSpec.All, draft.rules.getValue("build.failure").target)
        assertTrue(managed.userGroups.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { groups.rename("builtin:function", "Bad") }
        assertThrows(IllegalArgumentException::class.java) { groups.delete("builtin:function") }
    }

    @Test
    fun `all scenario and preview messages exist in both locales without duplicate keys`() {
        fun bundle(suffix: String): Properties =
            javaClass.getResourceAsStream("/messages/KgBoardBundle$suffix.properties")!!.reader(Charsets.UTF_8)
                .use { reader ->
                    val text = reader.readText()
                    val keys = text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }
                        .map { it.substringBefore('=') }.toList()
                    assertEquals(keys.size, keys.distinct().size)
                    Properties().also { it.load(text.reader()) }
                }

        val english = bundle("");
        val russian = bundle("_ru")
        assertEquals(english.stringPropertyNames(), russian.stringPropertyNames())
        ScenarioCatalog.rules.forEach { assertTrue(english.containsKey(it.labelKey), it.labelKey) }
        assertTrue(english.containsKey("preview.staleDevice"))
    }
}
