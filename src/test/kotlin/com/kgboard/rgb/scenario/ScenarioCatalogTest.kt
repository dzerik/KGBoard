package com.kgboard.rgb.scenario

import com.kgboard.rgb.effect.*
import com.kgboard.rgb.target.TargetSpec
import java.awt.Color
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScenarioCatalogTest {
    @Test fun `catalog covers every existing signal with independent state IDs`() {
        val expected = buildSet {
            for (group in listOf("build", "test", "run", "debug")) {
                add("$group.${if (group in listOf("build","test")) "progress" else "active"}")
                for (state in listOf("success","failure","cancelled","notExecuted")) add("$group.$state")
            }
            addAll(listOf("debug.breakpoint","fileSave","indexing","lowMemory","todo"))
            addAll(listOf("work","break","longBreak","transition").map { "pomodoro.$it" })
            addAll(listOf("conflict","dirty","ahead","behind","diverged","synced","branch").map { "git.$it" })
            addAll(listOf("error","warning","clean").map { "analysis.$it" })
            addAll(listOf("debug","search","vcs","editing").map { "shortcuts.$it" })
        }
        assertEquals(expected, ScenarioCatalog.rules.map { it.id }.toSet())
        assertEquals(expected.size, ScenarioCatalog.rules.size)
        assertNull(ScenarioCatalog.byId("future.rule"))
        val rules = ScenarioCatalog.defaults(SettingsScope.APP).toMutableMap()
        rules["build.progress"] = rules.getValue("build.progress").copy(enabled = false)
        assertTrue(rules.getValue("build.failure").enabled)
        assertEquals(TargetSpec.Unassigned, rules.getValue("indexing").target)
    }

    @Test fun `effect factory preserves fixed priority and supplied target for every style`() {
        val target = EffectTarget.LedSet(listOf(1))
        for (kind in EffectStyle.entries) {
            val effect = ScenarioEffects.create("build.failure", RuleStyle(Color.RED, kind, 1500, 500), target, .5f)
            assertEquals(9, effect.priority)
            assertEquals(target, effect.target)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScenarioEffects.create("unknown", RuleStyle(Color.RED, EffectStyle.STATIC, 1, 1), target)
        }
    }
}
