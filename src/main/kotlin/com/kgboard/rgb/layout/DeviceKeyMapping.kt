package com.kgboard.rgb.layout

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.runtime.frozenMap

data class DeviceKeys(val ledNames: Map<String, Int>, val keyNames: Map<String, Int>, val keyCodes: Map<Int, Int>)

object DeviceKeyMapping {
    fun build(device: RgbDeviceInfo): DeviceKeys {
        val raw = device.ledNames.withIndex().filter { it.index < device.numLeds }.groupBy({ it.value }, { it.index })
        val names = mutableMapOf<String, MutableSet<Int>>()
        val codes = mutableMapOf<Int, MutableSet<Int>>()
        device.ledNames.forEachIndexed { index, ledName ->
            if (index >= device.numLeds) return@forEachIndexed
            val name = KeyNames.normalize(ledName)
            if (!ledName.startsWith("Key:", ignoreCase = true) && KeyCodes.forName(name) == null) return@forEachIndexed
            names.getOrPut(name) { linkedSetOf() }.add(index)
            KeyCodes.forName(name)?.let { code -> codes.getOrPut(code) { linkedSetOf() }.add(index) }
        }
        return DeviceKeys(
            frozenMap(raw.mapNotNull { (key, values) -> values.singleOrNull()?.let { key to it } }
            .toMap()),
            frozenMap(names.mapNotNull { (key, values) -> values.singleOrNull()?.let { key to it } }.toMap()),
            frozenMap(codes.mapNotNull { (key, values) -> values.singleOrNull()?.let { key to it } }.toMap())
        )
    }
}
