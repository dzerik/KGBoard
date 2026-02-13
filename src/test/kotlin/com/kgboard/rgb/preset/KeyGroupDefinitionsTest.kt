package com.kgboard.rgb.preset

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for KeyGroupDefinitions static data integrity.
 */
class KeyGroupDefinitionsTest {

    @Test
    fun `all built-in groups have non-empty key lists`() {
        for ((groupId, keys) in KeyGroupDefinitions.BUILT_IN_GROUPS) {
            assertTrue(keys.isNotEmpty(), "Group '$groupId' should not be empty")
        }
    }

    @Test
    fun `all built-in groups have display names`() {
        for (groupId in KeyGroupDefinitions.BUILT_IN_GROUPS.keys) {
            assertNotNull(
                KeyGroupDefinitions.DISPLAY_NAMES[groupId],
                "Group '$groupId' should have a display name"
            )
        }
    }

    @Test
    fun `f-row has 12 keys`() {
        assertEquals(12, KeyGroupDefinitions.BUILT_IN_GROUPS["f-row"]?.size)
    }

    @Test
    fun `arrows has 4 keys`() {
        assertEquals(4, KeyGroupDefinitions.BUILT_IN_GROUPS["arrows"]?.size)
    }

    @Test
    fun `wasd has correct keys`() {
        assertEquals(listOf("W", "A", "S", "D"), KeyGroupDefinitions.BUILT_IN_GROUPS["wasd"])
    }

    @Test
    fun `hjkl has correct keys`() {
        assertEquals(listOf("H", "J", "K", "L"), KeyGroupDefinitions.BUILT_IN_GROUPS["hjkl"])
    }

    @Test
    fun `no duplicate group IDs between groups and display names`() {
        val groupIds = KeyGroupDefinitions.BUILT_IN_GROUPS.keys
        val displayIds = KeyGroupDefinitions.DISPLAY_NAMES.keys
        assertEquals(groupIds, displayIds, "Group IDs and display name IDs should match")
    }

    @Test
    fun `nav-cluster has 6 keys`() {
        assertEquals(6, KeyGroupDefinitions.BUILT_IN_GROUPS["nav-cluster"]?.size)
    }
}
