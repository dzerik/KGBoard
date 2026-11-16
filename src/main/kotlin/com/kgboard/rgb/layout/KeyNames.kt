package com.kgboard.rgb.layout

import java.util.Locale

/** Shared aliases; collision detection belongs to the caller, never to normalization. */
object KeyNames {
    fun extract(raw: String): String = raw.trim().let {
        if (it.startsWith("Key:", ignoreCase = true)) it.substring(4).trim() else it
    }
    fun normalize(raw: String): String {
        val normalized = extract(raw).uppercase(Locale.ROOT).replace("LEFT ", "L")
            .replace("RIGHT ", "R").replace("NUMBER PAD ", "NUM").replace("NUMPAD ", "NUM")
        return when (normalized) {
            "ESC" -> "ESCAPE"
            "CTRL" -> "CONTROL"
            "LCTRL" -> "LCONTROL"
            "RCTRL" -> "RCONTROL"
            else -> normalized
        }
    }
}
