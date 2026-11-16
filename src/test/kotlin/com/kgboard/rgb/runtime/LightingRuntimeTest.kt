package com.kgboard.rgb.runtime

import com.kgboard.rgb.effect.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import kotlin.random.Random

class LightingRuntimeTest {
    private val p = ProjectId("p")
    private val key = EffectKey(p, "static")

    @Test fun `inventory change never retires a frame with invalid old LED geometry`() {
        val h = RuntimeHarness(); h.project(ProjectId("p"))
        val before = h.writer.history.size
        h.send(RuntimeEvent.InventoryChanged(SessionId(1)))
        assertEquals(before, h.writer.history.size)
        val session = (h.snapshotForTest().connection as ConnectionState.Connecting).session
        h.send(RuntimeEvent.Connected(session, listOf(testDevice(7))))
        assertEquals(7, h.lastFrame(0).size)
    }

    @Test fun `deadline alone restores physical idle after a static effect`() {
        RuntimeHarness().use { h ->
            h.project()
            h.send(RuntimeEvent.LayerChanged(EffectLayer(key, StaticEffect(Color.RED), 0, 100)))
            assertEquals(Color.RED, h.lastFrame()[0])
            h.advanceBy(99)
            assertEquals(Color.RED, h.lastFrame()[0])
            h.advanceBy(1)
            assertEquals(Color.decode("#263238"), h.lastFrame()[0])
            assertFalse(h.snapshotForTest().layers.containsKey(key))
        }
    }

    @Test fun `focus regain sends a real restoration frame after dim`() {
        RuntimeHarness().use { h ->
            h.project()
            h.send(RuntimeEvent.LayerChanged(EffectLayer(key, StaticEffect(Color.RED), 0)))
            h.send(RuntimeEvent.FocusChanged(false))
            assertNotEquals(Color.RED, h.lastFrame()[0])
            h.send(RuntimeEvent.FocusChanged(true))
            assertEquals(Color.RED, h.lastFrame()[0])
        }
    }

    @Test fun `disable does not replay a transient when reenabled`() {
        RuntimeHarness().use { h ->
            h.project()
            h.send(RuntimeEvent.LayerChanged(EffectLayer(key, FlashEffect(Color.RED), 0, 1000)))
            h.send(RuntimeEvent.SettingsApplied(RuntimeSettings(enabled = false)))
            h.send(RuntimeEvent.SettingsApplied(RuntimeSettings()))
            assertEquals(Color.decode("#263238"), h.lastFrame()[0])
        }
    }

    @Test fun `late wake after shutdown cannot send frames or connect`() {
        val h = RuntimeHarness()
        h.project()
        h.send(RuntimeEvent.Shutdown)
        val before = h.snapshotForTest()
        val count = h.writer.history.size
        h.send(RuntimeEvent.Wake(Long.MIN_VALUE))
        h.send(RuntimeEvent.FocusChanged(true))
        h.close()
        assertEquals(before, h.snapshotForTest())
        assertEquals(count, h.writer.history.size)
        assertTrue(h.writer.closed)
    }

    @Test fun `effect target cannot be changed by its original caller`() {
        RuntimeHarness().use { h ->
            h.project()
            val indices = mutableListOf(0)
            h.send(RuntimeEvent.LayerChanged(EffectLayer(key, StaticEffect(Color.BLUE, target = EffectTarget.LedSet(indices)), 0)))
            indices[0] = 1
            h.send(RuntimeEvent.FocusChanged(true))
            assertEquals(Color.BLUE, h.lastFrame()[0])
            assertNotEquals(Color.BLUE, h.lastFrame()[1])
        }
    }

    @Test fun `a disposed or failing subscriber cannot stop rendering`() {
        RuntimeHarness().use { h ->
            var notifications = 0
            val cancelled = h.runtime.subscribe { notifications++ }
            cancelled.cancel()
            h.runtime.subscribe { error("disposed view") }
            h.project()
            h.send(RuntimeEvent.LayerChanged(EffectLayer(key, StaticEffect(Color.RED), 0)))
            assertEquals(0, notifications)
            assertEquals(Color.RED, h.lastFrame()[0])
            assertTrue(h.errors.isNotEmpty())
        }
    }

    @Test fun `independent layer sequences commute across 100 deterministic interleavings`() {
        repeat(100) { seed ->
            RuntimeHarness().use { h ->
                h.project()
                val blue = EffectKey(p, "blue")
                val sequences = mutableListOf(
                    ArrayDeque<RuntimeEvent>(listOf(RuntimeEvent.LayerChanged(EffectLayer(key, StaticEffect(Color.RED), 0)), RuntimeEvent.LayerRemoved(key))),
                    ArrayDeque<RuntimeEvent>(listOf(RuntimeEvent.LayerChanged(EffectLayer(blue, StaticEffect(Color.BLUE, priority = 8), 0))))
                )
                val random = Random(seed)
                while (sequences.isNotEmpty()) {
                    val sequence = sequences[random.nextInt(sequences.size)]
                    h.send(sequence.removeFirst())
                    if (sequence.isEmpty()) sequences.remove(sequence)
                }
                assertEquals(Color.BLUE, h.lastFrame()[0], "seed=$seed")
            }
        }
    }
}
