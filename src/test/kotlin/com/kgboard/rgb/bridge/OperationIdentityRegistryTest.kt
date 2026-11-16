package com.kgboard.rgb.bridge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class OperationIdentityRegistryTest {
    @Test fun `initial reconciliation cannot overwrite a newer callback`() {
        val registry = OperationIdentityRegistry(); val key = Any(); val id = registry.id(key)!!
        val beforeRead = registry.revision(id)!!
        assertEquals(1L, registry.nextRevision(id))
        assertNull(registry.nextRevision(id, beforeRead))
    }
    @Test fun `identity survives rename and retired identities reject late callbacks`() {
        val registry = OperationIdentityRegistry()
        val handler = Any()
        val id = registry.id(handler)!!
        assertEquals(id, registry.id(handler))
        assertEquals(1L, registry.nextRevision(id))
        registry.release(id)
        assertNull(registry.id(handler))
        assertNull(registry.nextRevision(id))
        repeat(1000) { val next = registry.id(Any())!!; registry.release(next) }
        assertEquals(0, registry.activeCount)
    }
}
