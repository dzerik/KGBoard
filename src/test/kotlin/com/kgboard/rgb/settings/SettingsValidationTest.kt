package com.kgboard.rgb.settings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class SettingsValidationTest {
    @Test fun `malformed indices never mean all LEDs and known range is enforced`() {
        for (raw in listOf(",,", "1,", ",1", "-1", "2147483648", "a", "1,,2", "3"))
            assertThrows(IllegalArgumentException::class.java) { SettingsValidation.indices(raw, 3) }
        assertTrue(SettingsValidation.indices("  ", 3).isEmpty())
        assertEquals(listOf(0, 2), SettingsValidation.indices("0, 2,0", 3))
        assertEquals(listOf(100), SettingsValidation.indices("100", null))
    }
}
