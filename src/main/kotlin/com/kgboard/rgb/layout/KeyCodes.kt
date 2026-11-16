package com.kgboard.rgb.layout

import java.awt.event.KeyEvent

/** Pure Java key-code vocabulary shared by mapping and per-device rendering. */
object KeyCodes {
    fun forName(name: String): Int? = KEY_NAME_TO_VK[name]
    private val KEY_NAME_TO_VK = buildMap {
        // Letters
        ('A'..'Z').forEach { put(it.toString(), KeyEvent.VK_A + (it - 'A')) }
        // Digits
        ('0'..'9').forEach { put(it.toString(), KeyEvent.VK_0 + (it - '0')) }
        // F-keys
        (1..12).forEach { put("F$it", KeyEvent.VK_F1 + (it - 1)) }
        // Modifiers
        put("LSHIFT", KeyEvent.VK_SHIFT)
        put("RSHIFT", KeyEvent.VK_SHIFT)
        put("LCONTROL", KeyEvent.VK_CONTROL)
        put("RCONTROL", KeyEvent.VK_CONTROL)
        put("LALT", KeyEvent.VK_ALT)
        put("RALT", KeyEvent.VK_ALT)
        put("LWIN", KeyEvent.VK_WINDOWS)
        put("RWIN", KeyEvent.VK_WINDOWS)
        // Navigation
        put("ENTER", KeyEvent.VK_ENTER)
        put("SPACE", KeyEvent.VK_SPACE)
        put("TAB", KeyEvent.VK_TAB)
        put("ESCAPE", KeyEvent.VK_ESCAPE)
        put("BACKSPACE", KeyEvent.VK_BACK_SPACE)
        put("DELETE", KeyEvent.VK_DELETE)
        put("INSERT", KeyEvent.VK_INSERT)
        put("HOME", KeyEvent.VK_HOME)
        put("END", KeyEvent.VK_END)
        put("PAGE UP", KeyEvent.VK_PAGE_UP)
        put("PAGE DOWN", KeyEvent.VK_PAGE_DOWN)
        put("UP", KeyEvent.VK_UP)
        put("DOWN", KeyEvent.VK_DOWN)
        put("LEFT", KeyEvent.VK_LEFT)
        put("RIGHT", KeyEvent.VK_RIGHT)
        // Punctuation
        put("CAPS LOCK", KeyEvent.VK_CAPS_LOCK)
        put("NUM LOCK", KeyEvent.VK_NUM_LOCK)
        put("SCROLL LOCK", KeyEvent.VK_SCROLL_LOCK)
        put("PRINT SCREEN", KeyEvent.VK_PRINTSCREEN)
        put("PAUSE", KeyEvent.VK_PAUSE)
        put("MINUS", KeyEvent.VK_MINUS)
        put("EQUALS", KeyEvent.VK_EQUALS)
        put("OPEN BRACKET", KeyEvent.VK_OPEN_BRACKET)
        put("CLOSE BRACKET", KeyEvent.VK_CLOSE_BRACKET)
        put("SEMICOLON", KeyEvent.VK_SEMICOLON)
        put("QUOTE", KeyEvent.VK_QUOTE)
        put("BACKSLASH", KeyEvent.VK_BACK_SLASH)
        put("COMMA", KeyEvent.VK_COMMA)
        put("PERIOD", KeyEvent.VK_PERIOD)
        put("SLASH", KeyEvent.VK_SLASH)
        put("BACK QUOTE", KeyEvent.VK_BACK_QUOTE)
    }
}
