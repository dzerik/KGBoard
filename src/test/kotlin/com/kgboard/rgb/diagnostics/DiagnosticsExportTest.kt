package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.device.InventoryToken
import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color

class DiagnosticsExportTest {
    @Test
    fun `export includes safe counters and reasons but rejects arbitrary text at every seam`() {
        val secret = "/home/private/branch-secret serial-secret location-secret file-content"
        val frame =
            FrameEnvelope(InventoryToken(SessionId(4), 5, secret), secret, 0, RenderRevision(6), listOf(Color.RED))
        val rule = RuleVisibility("build.success", ProjectId(secret), secret, listOf(0), listOf(0), emptySet())
        val decision = LightingDecision(
            RenderRevision(6), frame.token,
            listOf(DeviceDecision(secret, 0, frame, listOf(rule), secret)), null
        )
        val snapshot = DiagnosticsSnapshot(
            decision, mapOf(secret to DeliveryState(lastSent = RenderRevision(6), sentAtMs = 20, error = secret)),
            listOf(HistoryEntry(secret, secret, 1, 2, 3), HistoryEntry(secret, "transport.failed", 4, 5, 2))
        )
        val text = DiagnosticsExport.copyText(
            snapshot,
            mapOf("plugin" to "3.5.1", "ide" to "IU-263.7307.200", secret to secret, "java" to secret), listOf(secret)
        )
        assertTrue(text.contains("3.5.1"))
        assertTrue(text.contains("transport.failed"))
        assertTrue(text.contains("diagnostics.unknown"))
        for (privateText in listOf(
            "/home/private",
            "branch-secret",
            "serial-secret",
            "location-secret",
            "file-content"
        ))
            assertFalse(text.contains(privateText), text)
        assertTrue(text.contains("session=4"))
        assertTrue(text.contains("revision=6"))
    }

    @Test
    fun `export cannot copy private data through plausible reason prefixes`() {
        val snapshot = DiagnosticsSnapshot(
            history = listOf(
                HistoryEntry("network", "transport.failed /tmp/private", 1, 1, 1),
                HistoryEntry("network", "diagnostics.unknown\nprivate", 2, 2, 1)
            )
        )
        val text = DiagnosticsExport.copyText(snapshot, emptyMap(), emptyList())
        assertFalse(text.contains("private"))
        assertFalse(text.contains("/tmp"))
    }
}
