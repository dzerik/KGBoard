package com.kgboard.rgb.testing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ControllerFixtureSpec(
    val name: String = "Board",
    val vendor: String = "Vendor",
    val serial: String = "serial-1",
    val location: String = "usb1",
    val ledNames: List<String> = listOf("Esc", "A"),
    val displayNames: List<String> = emptyList()
)

/** Independent minimal controller encoder, following OpenRGBSDK.md (not production parser). */
object ControllerFixture {
    fun encode(version: Int, spec: ControllerFixtureSpec): ByteArray {
        require(version in 0..5)
        require(spec.ledNames.size <= 65535 && spec.displayNames.size <= 65535)
        val out = ByteArrayOutputStream()
        fun int(value: Int) { repeat(4) { out.write(value ushr (8 * it) and 255) } }
        fun short(value: Int) { require(value in 0..65535); out.write(value and 255); out.write(value ushr 8) }
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            short(bytes.size)
            out.write(bytes)
        }
        int(0) // Filled after encoding: data_size includes itself.
        int(1)
        string(spec.name)
        if (version >= 1) string(spec.vendor)
        string("Fixture controller")
        string("1.0")
        string(spec.serial)
        string(spec.location)
        short(0); int(0) // No modes, active mode zero.
        short(0) // No geometry: named LED list is the fallback.
        short(spec.ledNames.size)
        spec.ledNames.forEachIndexed { index, name -> string(name); int(index) }
        short(spec.ledNames.size)
        repeat(spec.ledNames.size) { int(0) }
        if (version >= 5) {
            short(spec.displayNames.size)
            spec.displayNames.forEach(::string)
            int(0)
        }
        return out.toByteArray().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(it.size)
        }
    }
}
