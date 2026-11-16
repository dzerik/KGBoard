package com.kgboard.rgb.client

import java.awt.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Bounded protocol0–5 controller parser. Never publishes partly decoded identity/geometry. */
internal class ControllerDataReader(payload: ByteBuffer, private val version: Int) {
    private val data = payload.slice().order(ByteOrder.LITTLE_ENDIAN)

    fun read(): RgbDeviceInfo = try {
        check(version in 0..5) { "Unsupported controller protocol" }
        val size = data.remaining()
        check(int("data size") == size) { "Controller data size mismatch" }
        val type = int("type")
        val name = text("name")
        val vendor = if (version >= 1) text("vendor") else ""
        val description = text("description")
        val deviceVersion = text("version")
        val serial = text("serial")
        val location = text("location")
        val modes = count("modes")
        int("active mode")
        repeat(modes) { mode() }
        val zones = List(count("zones")) { zone() }
        val leds = List(count("LEDs")) { text("LED name").also { int("LED value") } }
        val colorCount = count("colors")
        need(colorCount * 4, "colors")
        val colors = List(colorCount) {
            val r = data.get().toInt() and 255
            val g = data.get().toInt() and 255
            val b = data.get().toInt() and 255
            data.get()
            Color(r,g,b)
        }
        val displayNames = if (version >= 5) {
            List(count("display names")) { text("display name") }.also { int("controller flags") }
        } else emptyList()
        check(!data.hasRemaining()) { "Unexpected controller trailing data" }
        RgbDeviceInfo(type, name, vendor, description, leds.size, leds, zones, colors,
            serial, location, displayNames, deviceVersion)
    } catch (error: Exception) {
        throw OpenRgbException("Malformed controller data: ${error.message ?: error.javaClass.simpleName}", error)
    }

    private fun need(bytes: Int, field: String) {
        check(bytes >= 0 && bytes <= data.remaining()) { "Incomplete $field" }
    }
    private fun int(field: String): Int { need(4, field); return data.int }
    private fun count(field: String): Int { need(2, field); return data.short.toInt() and 65535 }
    private fun text(field: String): String {
        val size = count("$field length")
        need(size, field)
        if (size == 0) return ""
        val bytes = ByteArray(size).also(data::get)
        check(bytes.last() == 0.toByte()) { "Unterminated $field" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, size - 1)).toString()
    }

    private fun mode() {
        text("mode name")
        int("mode value"); int("mode flags"); int("speed min"); int("speed max")
        if (version >= 3) { int("brightness min"); int("brightness max") }
        int("colors min"); int("colors max"); int("speed")
        if (version >= 3) int("brightness")
        int("direction"); int("color mode")
        val bytes = count("mode colors") * 4
        need(bytes, "mode colors"); data.position(data.position() + bytes)
    }

    private fun zone(): RgbZoneInfo {
        val name = text("zone name")
        val type = int("zone type")
        int("zone min"); int("zone max")
        val leds = int("zone LEDs")
        check(leds >= 0) { "Negative zone LED count" }
        val matrixSize = count("matrix length")
        var height = 0; var width = 0
        val map = if (matrixSize == 0) null else {
            need(matrixSize, "matrix")
            check(matrixSize >= 8) { "Invalid matrix length" }
            height = int("matrix height"); width = int("matrix width")
            val cells = height.toLong() * width
            check(height >= 0 && width >= 0 && 8L + cells * 4 == matrixSize.toLong()) { "Invalid matrix dimensions" }
            List(cells.toInt()) { int("matrix cell") }
        }
        if (version >= 4) repeat(count("segments")) {
            text("segment name"); int("segment type"); int("segment start"); int("segment LEDs")
        }
        if (version >= 5) int("zone flags")
        return RgbZoneInfo(name, type, leds, height, width, map)
    }
}
