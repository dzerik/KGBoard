package com.kgboard.rgb.preset

/**
 * Static definitions of built-in key groups.
 * Device-independent: groups are defined by key names (not LED indices).
 * Resolution to actual LED indices happens at runtime via KeyboardLayoutService.
 */
object KeyGroupDefinitions {

    /**
     * Built-in group: group ID -> list of key names.
     * Key names should match OpenRGB "Key: X" naming (e.g., "F1", "A", "Enter").
     */
    val BUILT_IN_GROUPS: Map<String, List<String>> = mapOf(
        "f-row" to (1..12).map { "F$it" },

        "number-row" to listOf(
            "Back Tick", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "Minus", "Equals"
        ),

        "arrows" to listOf("Up", "Down", "Left", "Right"),

        "nav-cluster" to listOf(
            "Insert", "Delete", "Home", "End", "Page Up", "Page Down"
        ),

        "wasd" to listOf("W", "A", "S", "D"),

        "hjkl" to listOf("H", "J", "K", "L"),

        "modifiers-left" to listOf(
            "Left Shift", "Left Control", "Left Alt", "Left Windows"
        ),

        "modifiers-right" to listOf(
            "Right Shift", "Right Control", "Right Alt", "Right Windows"
        ),

        "top-row" to listOf(
            "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
            "Open Bracket", "Close Bracket"
        ),

        "home-row" to listOf(
            "A", "S", "D", "F", "G", "H", "J", "K", "L",
            "Semicolon", "Quote"
        ),

        "numpad" to listOf(
            "Number Pad 0", "Number Pad 1", "Number Pad 2", "Number Pad 3",
            "Number Pad 4", "Number Pad 5", "Number Pad 6", "Number Pad 7",
            "Number Pad 8", "Number Pad 9", "Number Pad /", "Number Pad *",
            "Number Pad -", "Number Pad +", "Number Pad Enter", "Number Pad ."
        )
    )

    /** Human-readable display names for UI. */
    val DISPLAY_NAMES: Map<String, String> = mapOf(
        "f-row" to "F-row (F1–F12)",
        "number-row" to "Number row (` 1-0 - =)",
        "arrows" to "Arrow keys",
        "nav-cluster" to "Navigation cluster (Ins/Del/Home/End/PgUp/PgDn)",
        "wasd" to "WASD",
        "hjkl" to "HJKL (Vim navigation)",
        "modifiers-left" to "Left modifiers (Shift/Ctrl/Alt/Win)",
        "modifiers-right" to "Right modifiers (Shift/Ctrl/Alt/Win)",
        "top-row" to "Top row (QWERTY...)",
        "home-row" to "Home row (ASDF...)",
        "numpad" to "Numpad"
    )
}
