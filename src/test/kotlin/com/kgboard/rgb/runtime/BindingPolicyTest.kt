package com.kgboard.rgb.runtime

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.device.*
import com.kgboard.rgb.settings.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BindingPolicyTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val endpoint = Endpoint("127.0.0.1", 6742)
    private fun device(serial: String) =
        RgbDeviceInfo(1, "B", "V", "", 2, listOf("Esc", "A"), emptyList(), emptyList(), serial = serial)

    private fun settings(): RuntimeSettings {
        val state = SettingsMigration.app(KgBoardSettings.State())
        state.bindingRecords =
            mutableListOf(BindingRecord(id = id, kind = "serial", type = 1, vendor = "V", serial = "selected"))
        return RuntimeSettings.capture(state)
    }

    @Test
    fun `no confirmed binding produces no writes for active idle disabled and shutdown`() {
        RuntimeHarness(RuntimeSettings()).use { h ->
            h.project()
            h.send(
                RuntimeEvent.OperationChanged(
                    ProjectId("p"),
                    OperationState(OperationId("build"), OperationKind.BUILD, OperationPhase.RUNNING)
                )
            )
            h.send(RuntimeEvent.SettingsApplied(RuntimeSettings(enabled = false)))
            h.close()
            assertTrue(h.writer.history.isEmpty())
        }
    }

    @Test
    fun `serial binding follows reorder and never uses old device index`() {
        val state = RuntimeState(
            settings = settings(), focused = true,
            connection = ConnectionState.Ready(SessionId(1), listOf(device("other"), device("selected")))
        )
        assertEquals(setOf(1), LightingPolicy().frames(state, 0).keys)
        assertTrue(
            LightingPolicy().frames(
                state.copy(
                    connection = ConnectionState.Ready(
                        SessionId(2),
                        listOf(device("other"))
                    )
                ), 0
            ).isEmpty()
        )
        assertTrue(
            LightingPolicy().frames(
                state.copy(
                    connection = ConnectionState.Ready(
                        SessionId(2),
                        listOf(device("selected"), device("selected"))
                    )
                ), 0
            ).isEmpty()
        )
    }

    @Test
    fun `duplicate roles resolving same device fail apply and cannot write even if loaded`() {
        val source = settings().toState()
        source.bindingRecords.add(source.bindingRecords.single().copy(id = "00000000-0000-4000-8000-000000000002"))
        val snapshot = RuntimeSettings.capture(source)
        val inventory = InventorySnapshot(InventoryToken(SessionId(1), 1), endpoint, listOf(device("selected")))
        assertThrows(IllegalArgumentException::class.java) { SettingsValidation.app(snapshot, inventory = inventory) }
        val state = RuntimeState(
            settings = snapshot, focused = true,
            connection = ConnectionState.Ready(SessionId(1), inventory.devices)
        )
        assertTrue(LightingPolicy().frames(state, 0).isEmpty())
    }

    @Test
    fun `session-only selection is not reconfirmed by repeated counter after restart`() {
        val binding =
            DeviceBinding(id, endpoint, DeviceSelector.SessionOnly(InventoryToken(SessionId(1), 1, "old-runtime"), 0))
        val record = BindingRecord.from(ConfiguredBinding(binding))
        val loaded = RuntimeSettings.capture(KgBoardSettings.State(bindingRecords = mutableListOf(record)))
        val inventory = InventorySnapshot(InventoryToken(SessionId(1), 1, "new-runtime"), endpoint, listOf(device("")))
        assertEquals(
            DeviceResolution.ConfirmationRequired,
            DeviceResolver.resolve(loaded.bindings.single().binding, inventory, emptySet())
        )
    }

    @Test
    fun `mirror shortcut hints resolve key codes against each device geometry`() {
        val first = device("selected").copy(ledNames = listOf("F5", "A"))
        val second = device("second").copy(ledNames = listOf("A", "F5"))
        val source = settings().toState()
        source.bindingRecords.add(
            source.bindingRecords.single().copy(
                id = "00000000-0000-4000-8000-000000000002",
                serial = "second", role = "mirror"
            )
        )
        val project = ProjectId("p")
        val state = RuntimeState(
            settings = RuntimeSettings.capture(source), focused = true,
            connection = ConnectionState.Ready(SessionId(1), listOf(first, second)), activeProject = project,
            projects = mapOf(
                project to ProjectState(
                    settings = ProjectSettingsSnapshot(shortcutHighlightEnabled = true),
                    shortcutContexts = setOf("debug"),
                    shortcutKeyCodes = mapOf("debug" to listOf(java.awt.event.KeyEvent.VK_F5))
                )
            )
        )
        val frames = LightingPolicy().frames(state, 0)
        val color = LightingPolicy.color("#651FFF")
        assertEquals(color, frames.getValue(0)[0])
        assertNotEquals(color, frames.getValue(0)[1])
        assertEquals(color, frames.getValue(1)[1])
        assertNotEquals(color, frames.getValue(1)[0])
    }

    @Test
    fun `preview from previous server never overlays a new confirmed binding`() {
        val base = RuntimeSettings().withTestBindings()
        val records = base.bindingRecords.toRecords()
        records.add(records.single().copy(id = "00000000-0000-4000-8000-000000000002", host = "other"))
        RuntimeHarness(base.copy(bindingRecords = BindingRecords.capture(records))).use { h ->
            h.project()
            val preview = com.kgboard.rgb.settings.SettingsPreviewSession(h.runtime)
            h.drain()
            preview.previewColor(h.snapshotForTest(), 0, java.awt.Color.RED, listOf(0)); h.drain()
            assertEquals(java.awt.Color.RED, h.lastFrame()[0])
            h.send(RuntimeEvent.ConnectRequested(Endpoint("other", 6742)))
            val session = (h.snapshotForTest().connection as ConnectionState.Connecting).session
            h.send(RuntimeEvent.Connected(session, testDevices()))
            assertEquals(List(3) { LightingPolicy.color(base.idleColor) }, h.lastFrame())
            preview.close(); h.drain()
        }
    }
}
