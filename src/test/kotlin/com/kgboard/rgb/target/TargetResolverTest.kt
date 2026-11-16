package com.kgboard.rgb.target

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.device.LayoutFingerprint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TargetResolverTest {
    private fun device(vararg names: String) = RgbDeviceInfo(1, "Board", "V", "", names.size,
        names.toList(), emptyList(), emptyList())

    @Test fun `raw indices require valid nonempty input and confirmed ordered layout`() {
        val device = device("Esc", "A")
        val fingerprint = LayoutFingerprint.of(device)
        for (raw in listOf("", "-1", "2147483648", "0,,1", "2")) {
            val result = TargetResolver.resolve(TargetSpec.RawIndices(raw, fingerprint), device, emptyMap())
            assertTrue(result.indices.isEmpty(), raw)
            assertNotNull(result.reason)
        }
        val target = TargetSpec.RawIndices("0", fingerprint)
        assertEquals(listOf(0), TargetResolver.resolve(target, device, emptyMap()).indices)
        assertTrue(TargetResolver.resolve(target, device.copy(ledNames = listOf("A", "Esc")), emptyMap()).indices.isEmpty())
        assertTrue(TargetResolver.resolve(target.copy(fingerprint = null), device, emptyMap()).indices.isEmpty())
    }

    @Test fun `named keys follow reorder and partial targets keep only unique matches`() {
        val target = TargetSpec.NamedKeys(listOf("Esc", "Missing"))
        val resolved = TargetResolver.resolve(target, device("A", "Esc"), emptyMap())
        assertEquals(listOf(1), resolved.indices)
        assertEquals(listOf("Missing"), resolved.missing)
        assertEquals(ResolutionStatus.PARTIAL, resolved.status)
        assertTrue(TargetResolver.resolve(TargetSpec.NamedKeys(listOf("Missing")), device("Esc"), emptyMap()).indices.isEmpty())
        assertTrue(TargetResolver.resolve(TargetSpec.NamedKeys(emptyList()), device("Esc"), emptyMap()).indices.isEmpty())
    }

    @Test fun `aliases and duplicate raw names never choose an arbitrary LED`() {
        val device = device("LEFT SHIFT", "LSHIFT", "Esc")
        val result = TargetResolver.resolve(TargetSpec.NamedKeys(listOf("lshift", "Esc")), device, emptyMap())
        assertEquals(listOf(2), result.indices)
        assertEquals(ResolutionStatus.AMBIGUOUS, result.status)
        assertEquals(listOf(1), TargetResolver.resolve(TargetSpec.NamedKeys(listOf("LSHIFT")), device, emptyMap()).indices)
        assertEquals(listOf(0), TargetResolver.resolve(TargetSpec.NamedKeys(listOf("Esc")), device("Key: Escape"), emptyMap()).indices)
        assertTrue(TargetResolver.resolve(TargetSpec.NamedKeys(listOf("A")), device("A", "A"), emptyMap()).indices.isEmpty())
    }

    @Test fun `deleted and cyclic groups remain unavailable while rename preserves identity`() {
        val id = "user:00000000-0000-4000-8000-000000000001"
        val group = GroupDefinition(id, "Before", TargetSpec.NamedKeys(listOf("Esc")))
        val target = TargetSpec.Group(id)
        assertTrue(TargetResolver.resolve(target, device("Esc"), emptyMap()).indices.isEmpty())
        assertEquals(listOf(0), TargetResolver.resolve(target, device("Esc"), mapOf(id to group.copy(name = "After"))).indices)
        assertTrue(TargetResolver.resolve(target, device("Esc"), mapOf(id to group.copy(target = target))).indices.isEmpty())
    }

    @Test fun `all and unassigned have explicit different meanings`() {
        assertEquals(listOf(0,1), TargetResolver.resolve(TargetSpec.All, device("A", "B"), emptyMap()).indices)
        assertTrue(TargetResolver.resolve(TargetSpec.Unassigned, device("A", "B"), emptyMap()).indices.isEmpty())
    }

    @Test fun `long group reference chain resolves without recursion or widening`() {
        val groups = (0 until 5000).associate { index ->
            val id = "g$index"
            id to GroupDefinition(id, id, if (index == 4999) TargetSpec.NamedKeys(listOf("Esc"))
                else TargetSpec.Group("g${index+1}"))
        }
        assertEquals(listOf(0), TargetResolver.resolve(TargetSpec.Group("g0"), device("Esc"), groups).indices)
    }

    @Test fun `unknown target record preserves its fields without executing`() {
        val record = TargetRecord(kind = "future-target", raw = "bad", names = mutableListOf("Esc"))
        assertNull(record.toSpec())
        assertEquals("future-target", record.copyDeep().kind)
        val named = TargetSpec.NamedKeys(listOf("Key: Esc"))
        assertEquals(named, TargetRecord.from(named).toSpec())
    }
}
