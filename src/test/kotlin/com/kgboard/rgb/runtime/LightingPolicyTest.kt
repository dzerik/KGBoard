package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.client.RgbZoneInfo
import com.kgboard.rgb.effect.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

internal fun testDevice(leds: Int = 3, zones: List<RgbZoneInfo> = listOf(RgbZoneInfo("all", 0, leds))) =
    RgbDeviceInfo(0, "Keyboard", "Vendor", "Device", leds, zones = zones, colors = List(leds) { Color.BLACK })

class LightingPolicyTest {
    private val policy = LightingPolicy()
    private val p = ProjectId("p")
    private fun layer(name: String, effect: RgbEffect, expires: Long? = null, created: Long = 0) =
        EffectLayer(EffectKey(p, name), effect, created, expires)
    private fun state(vararg layers: EffectLayer) = RuntimeState(
        settings = RuntimeSettings(idleColor = "#102030"),
        projects = mapOf(p to ProjectState()), activeProject = p, focused = true,
        connection = ConnectionState.Ready(SessionId(1), listOf(testDevice(), testDevice())),
        layers = layers.associateBy { it.key }
    )

    @Test fun `static expiry schedules wake without animation and reveals underlying state`() {
        val background = layer("debug", StaticEffect(Color.BLUE))
        val failure = layer("failure", StaticEffect(Color.RED, priority = 10), expires = 3000)
        val state = state(background, failure)
        assertEquals(3000L, policy.nextWakeAt(state, 0))
        assertTrue(policy.frames(state, 2999).getValue(0).all { it == Color.RED })
        assertTrue(policy.frames(state, 3000).getValue(0).all { it == Color.BLUE })
    }

    @Test fun `focus dim holds while pulse advances and restores current frame`() {
        val state = state(layer("pulse", PulseEffect(Color.GREEN)))
        val blurred = state.copy(focused = false)
        val dim = policy.frames(blurred, 100).getValue(0)
        assertEquals(dim, policy.frames(blurred, 700).getValue(0))
        assertNotEquals(dim, policy.frames(state, 700).getValue(0))
        assertNull(policy.nextWakeAt(blurred, 100))
    }

    @Test fun `static focus regain reproduces original full brightness frame`() {
        val state = state(layer("static", StaticEffect(Color.RED)))
        assertNotEquals(policy.frames(state, 0), policy.frames(state.copy(focused = false), 1))
        assertEquals(policy.frames(state, 0), policy.frames(state.copy(focused = true), 2))
    }

    @Test fun `indicator updates even if primary is masked by global effect`() {
        var state = state(layer("global", StaticEffect(Color.RED, priority = 100)))
        state = state.copy(settings = state.settings.copy(multiDeviceEnabled = true,
            deviceConfigs = listOf(DeviceSettings(0), DeviceSettings(1, role = "indicator"))))
        val targeted = layer("git", StaticEffect(Color.BLUE, priority = 1, target = EffectTarget.SingleLed(0)))
        val after = state.copy(layers = state.layers + (targeted.key to targeted))
        assertEquals(policy.frames(state, 0).getValue(0), policy.frames(after, 0).getValue(0))
        assertNotEquals(policy.frames(state, 0).getValue(1), policy.frames(after, 0).getValue(1))
        assertEquals(Color.BLUE, policy.frames(after, 0).getValue(1)[0])
    }

    @Test fun `indicator follows priority regardless of map insertion order`() {
        val high = layer("a-high", StaticEffect(Color.BLUE, priority = 8, target = EffectTarget.SingleLed(0)))
        val low = layer("z-low", StaticEffect(Color.RED, priority = 1, target = EffectTarget.SingleLed(0)))
        val state = state(high, low).copy(settings = RuntimeSettings(multiDeviceEnabled = true,
            deviceConfigs = listOf(DeviceSettings(1, role = "indicator"))))
        assertEquals(Color.BLUE, policy.frames(state, 0).getValue(1)[0])
    }

    @Test fun `equal priorities use creation time then key consistently`() {
        val old = layer("z", StaticEffect(Color.BLUE), created = 1)
        val newer = layer("a", StaticEffect(Color.RED), created = 2)
        assertEquals(policy.frames(state(old, newer), 10), policy.frames(state(newer, old), 10))
        assertEquals(Color.RED, policy.frames(state(newer, old), 10).getValue(0)[0])
    }

    @Test fun `background project never contributes to the foreground frame`() {
        val q = ProjectId("q")
        val foreground = layer("active", StaticEffect(Color.BLUE))
        val background = layer("background", StaticEffect(Color.RED, priority = 100)).copy(key = EffectKey(q, "background"))
        val state = state(foreground, background).copy(projects = mapOf(p to ProjectState(), q to ProjectState()))
        assertEquals(Color.BLUE, policy.frames(state, 0).getValue(0)[0])
        assertEquals(Color.RED, policy.frames(state.copy(activeProject = q), 0).getValue(0)[0])
    }

    @Test fun `zone geometry is resolved separately for each device`() {
        val state = state(layer("zone", StaticEffect(Color.RED, target = EffectTarget.Zone(1)))).copy(
            settings = RuntimeSettings(multiDeviceEnabled = true, deviceConfigs = listOf(DeviceSettings(0), DeviceSettings(1))),
            connection = ConnectionState.Ready(SessionId(1), listOf(
                testDevice(3, listOf(RgbZoneInfo("a", 0, 2), RgbZoneInfo("b", 0, 1))),
                testDevice(3, listOf(RgbZoneInfo("a", 0, 1), RgbZoneInfo("b", 0, 2)))
            )))
        assertEquals(1, policy.frames(state, 0).getValue(0).count { it == Color.RED })
        assertEquals(2, policy.frames(state, 0).getValue(1).count { it == Color.RED })
    }
}
