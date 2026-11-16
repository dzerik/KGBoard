package com.kgboard.rgb.scenario

import com.kgboard.rgb.target.TargetSpec
import java.awt.Color
import java.util.Collections

/** Stable rule IDs and fixed rendering priorities, independent of UI construction. */
object ScenarioCatalog {
    private fun rule(id: String, priority: Int, color: Int, effect: EffectStyle = EffectStyle.STATIC,
        scope: SettingsScope = SettingsScope.APP, enabled: Boolean = true, target: TargetSpec = TargetSpec.All,
        channel: BrightnessChannel = BrightnessChannel.PERSISTENT, duration: Long = 3000, period: Long = 1000) =
        ScenarioDescriptor(id, id.substringBefore('.'), scope, "scenario.$id", channel, priority,
            RuleAppearance(enabled, target, RuleStyle(Color(color), effect, duration, period)))

    val rules: List<ScenarioDescriptor> = Collections.unmodifiableList(buildList {
        for (group in listOf("build", "test", "run", "debug")) {
            val progress = group in listOf("build","test")
            val color = when(group) { "build" -> 0xFFD600; "test" -> 0x2979FF; "run" -> 0x00E676; else -> 0x651FFF }
            val priority = when(group) { "run" -> 4; "build" -> 5; else -> 6 }
            add(rule("$group.${if (progress) "progress" else "active"}", priority, color,
                if (group == "run") EffectStyle.STATIC else EffectStyle.PULSE))
            add(rule("$group.success", 7, 0x00C853, EffectStyle.FLASH, enabled = progress,
                channel = BrightnessChannel.NOTIFICATION))
            add(rule("$group.failure", if(group == "build") 9 else 8,
                if(progress) 0xFF1744 else 0xFF9100, channel = BrightnessChannel.NOTIFICATION))
            for (state in listOf("cancelled","notExecuted")) add(rule("$group.$state", 7, 0xFF9100,
                enabled = false, channel = BrightnessChannel.NOTIFICATION))
        }
        add(rule("debug.breakpoint",8,0xFF1744, enabled = false, channel = BrightnessChannel.NOTIFICATION))
        add(rule("fileSave",3,0xFFFFFF,EffectStyle.FLASH,enabled = false,channel = BrightnessChannel.NOTIFICATION,duration = 300))
        add(rule("indexing",3,0xFF9100,EffectStyle.PULSE,target = TargetSpec.Unassigned,period = 1500))
        add(rule("lowMemory",10,0xFF1744,EffectStyle.FLASH,channel = BrightnessChannel.NOTIFICATION,duration = 2000))
        add(rule("todo",1,0xFFD600,enabled = false,target = TargetSpec.Unassigned))
        add(rule("pomodoro.work",1,0x00C853,EffectStyle.GRADIENT,duration = 25*60*1000L))
        add(rule("pomodoro.break",1,0x2979FF,EffectStyle.PULSE,period = 1200))
        add(rule("pomodoro.longBreak",1,0x2979FF,EffectStyle.PULSE,period = 2000))
        add(rule("pomodoro.transition",5,0xFFFFFF,EffectStyle.FLASH,channel = BrightnessChannel.NOTIFICATION,duration = 600))
        for ((state,color) in mapOf("conflict" to 0xFF1744,"dirty" to 0xFFD600,"ahead" to 0xFF9100,
            "behind" to 0x00BCD4,"diverged" to 0xFF9100,"synced" to 0x00C853)) {
            add(rule("git.$state",if(state == "conflict") 3 else if(state == "synced") 1 else 2,color,
                if(state == "conflict") EffectStyle.PULSE else EffectStyle.STATIC,SettingsScope.PROJECT,
                target = TargetSpec.Unassigned,period = if(state == "conflict") 800 else 1000))
        }
        add(rule("git.branch",0,0xFFFFFF,scope = SettingsScope.PROJECT))
        for ((state,color) in mapOf("error" to 0xFF1744,"warning" to 0xFFD600,"clean" to 0x00C853))
            add(rule("analysis.$state",2,color,scope = SettingsScope.PROJECT,target = TargetSpec.Unassigned))
        for ((context,color) in mapOf("debug" to 0x651FFF,"search" to 0xFFD600,"vcs" to 0xFF9100,"editing" to 0x00E676))
            add(rule("shortcuts.$context",1,color,scope = SettingsScope.PROJECT,enabled = false,target = TargetSpec.Unassigned))
    })
    private val byId = rules.associateBy { it.id }
    fun byId(id: String): ScenarioDescriptor? = byId[id]
    fun defaults(scope: SettingsScope): Map<String, RuleAppearance> = Collections.unmodifiableMap(
        rules.filter { it.scope == scope }.associate { it.id to it.default })
}
