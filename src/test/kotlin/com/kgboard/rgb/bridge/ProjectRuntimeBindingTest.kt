package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectRuntimeBindingTest {
    @Test fun `lifetime closes once and rejects queued callbacks`() {
        val harness = RuntimeHarness()
        repeat(20) {
            val binding = ProjectRuntimeBinding(RuntimeEvents { harness.send(it) }, ProjectSettingsSnapshot())
            binding.start(); binding.start()
            assertEquals(1, harness.snapshotForTest().projects.size)
            binding.dispose(); binding.dispose()
            binding.emit(RuntimeEvent.LayerChanged(EffectLayer(EffectKey(binding.id, "late"), com.kgboard.rgb.effect.StaticEffect(java.awt.Color.RED), 0)))
            assertTrue(harness.snapshotForTest().projects.isEmpty())
            assertTrue(harness.snapshotForTest().layers.isEmpty())
        }
    }
}
