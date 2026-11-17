package com.kgboard.rgb.runtime

import com.kgboard.rgb.diagnostics.*
import com.kgboard.rgb.effect.*
import com.kgboard.rgb.settings.SettingsPreviewSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

class ResumeRuntimeTest {
    @Test
    fun `eight hour sleep fences inventory before any offer and discards transient facts`() {
        RuntimeHarness(RuntimeSettings(pomodoroEnabled = true, pomodoroWorkMinutes = 1).withTestBindings()).use { h ->
            val p = ProjectId("p"); h.project(p)
            h.send(RuntimeEvent.PomodoroStart(p))
            h.send(RuntimeEvent.DocumentsSaved(p, 1))
            h.send(RuntimeEvent.LowMemory)
            h.send(RuntimeEvent.LayerChanged(EffectLayer(EffectKey(p, "flash"), StaticEffect(Color.RED), 0, 5000)))
            val preview = SettingsPreviewSession(h.runtime)
            preview.previewColor(h.snapshotForTest(), 1, Color.BLUE, listOf(0)); h.drain()
            val before = h.snapshotForTest()
            val oldFrame = h.writer.envelopes.last()
            val offers = h.writer.offers
            h.clock.wallValue += 8 * 60 * 60 * 1000L
            h.send(RuntimeEvent.FocusChanged(false))
            val resumed = h.snapshotForTest()
            assertNotEquals(before.inventory()?.token?.epoch, resumed.connectionEpoch)
            assertNull(resumed.inventory())
            assertEquals(offers, h.writer.offers)
            assertTrue(resumed.layers.isEmpty())
            assertNull(resumed.preview)
            assertNull(resumed.lowMemoryAtMs)
            assertNull(resumed.projects.getValue(p).savedAtMs)
            val pomodoro = resumed.projects.getValue(p).pomodoro
            assertEquals(PomodoroPhase.BREAK, pomodoro.phase)
            assertEquals(300_000L, pomodoro.deadline?.remaining(h.clock.sample()))
            h.send(RuntimeEvent.Connected(oldFrame.token.session, testDevices()))
            h.send(RuntimeEvent.DeliveryReported(DeliveryReceipt.Sent(oldFrame, 99)))
            assertNull(h.snapshotForTest().inventory())
            assertEquals(offers, h.writer.offers)
            val connecting = resumed.connection as ConnectionState.Connecting
            h.send(RuntimeEvent.Connected(connecting.session, testDevices()))
            assertTrue(h.writer.offers > offers)
            assertTrue(h.writer.envelopes.drop(offers).all { it.token.epoch == resumed.connectionEpoch })
            assertTrue(h.snapshotForTest().diagnostics.history.any { it.reasonKey == "diagnostics.clockChanged" })
            preview.close(); h.drain()
        }
    }

    @Test
    fun `manual disconnect survives resume without network work`() {
        RuntimeHarness().use { h ->
            h.project(); h.send(RuntimeEvent.DisconnectRequested)
            val connects = h.writer.connects.size
            h.clock.wallValue += 28_800_000
            h.send(RuntimeEvent.ResumeDetected)
            assertTrue(h.snapshotForTest().manuallyDisconnected)
            assertEquals(ConnectionState.Disconnected, h.snapshotForTest().connection)
            assertEquals(connects, h.writer.connects.size)
        }
    }

    @Test
    fun `stop before resume cannot resurrect expired pomodoro phase`() {
        RuntimeHarness(RuntimeSettings(pomodoroEnabled = true).withTestBindings()).use { h ->
            val p = ProjectId("p"); h.project(p)
            h.send(RuntimeEvent.PomodoroStart(p)); h.send(RuntimeEvent.PomodoroStop(p))
            val generation = h.snapshotForTest().projects.getValue(p).pomodoro.generation
            h.clock.wallValue += 28_800_000
            h.send(RuntimeEvent.FocusChanged(true))
            assertEquals(PomodoroPhase.IDLE, h.snapshotForTest().projects.getValue(p).pomodoro.phase)
            assertEquals(generation, h.snapshotForTest().projects.getValue(p).pomodoro.generation)
        }
    }

    @Test
    fun `backward wall jump never extends running phase`() {
        RuntimeHarness(RuntimeSettings(pomodoroEnabled = true, pomodoroWorkMinutes = 1).withTestBindings()).use { h ->
            val p = ProjectId("p"); h.project(p); h.send(RuntimeEvent.PomodoroStart(p))
            h.clock.value = 30_000; h.clock.wallValue = -100_000
            h.send(RuntimeEvent.FocusChanged(false))
            val timer = h.snapshotForTest().projects.getValue(p).pomodoro
            assertEquals(PomodoroPhase.WORK, timer.phase)
            assertEquals(30, timer.remainingSeconds(h.clock.sample()))
        }
    }
}
