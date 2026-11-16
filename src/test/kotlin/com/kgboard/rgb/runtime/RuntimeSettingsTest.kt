package com.kgboard.rgb.runtime

import com.kgboard.rgb.settings.KgBoardSettings
import com.kgboard.rgb.settings.KgBoardProjectSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class RuntimeSettingsTest {
    @Test fun `app snapshot preserves every serialized field`() {
        val source = KgBoardSettings.State(deviceConfigs = mutableListOf(KgBoardSettings.DeviceConfig(2, "keys")))
        val snapshot = RuntimeSettings.capture(source)
        val sourceNames = source.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        val names = snapshot.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(sourceNames, names)
        assertEquals(source, snapshot.toState())
        source.deviceConfigs[0].role = "ambient"
        source.deviceConfigs.clear()
        assertEquals("primary", snapshot.deviceConfigs.single().role)
    }

    @Test fun `project snapshot freezes rules and context lists`() {
        val source = KgBoardProjectSettings.State(gitBranchColorRules = mutableListOf(KgBoardProjectSettings.GitColorRule("main", "#123456")))
        val snapshot = ProjectSettingsSnapshot.capture(source)
        assertEquals(source, snapshot.toState())
        source.gitBranchColorRules[0].pattern = ".*"
        source.shortcutContexts.clear()
        assertEquals("main", snapshot.gitBranchColorRules.single().pattern)
        assertEquals(listOf("debug", "search"), snapshot.shortcutContexts)
        snapshot.toState().gitBranchColorRules.clear()
        assertEquals(1, snapshot.gitBranchColorRules.size)
    }
}
