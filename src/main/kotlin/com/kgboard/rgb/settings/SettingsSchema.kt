package com.kgboard.rgb.settings

import com.kgboard.rgb.device.*
import com.kgboard.rgb.runtime.*

object SettingsSchema {
    const val VERSION = 1
}

data class SerialRecord(var type: Int = 0, var vendor: String = "", var serial: String = "") {
    fun snapshot() = SerialKey(type, vendor, serial)

    companion object {
        fun from(key: SerialKey) = SerialRecord(key.type, key.vendor, key.serial)
    }
}

/** Mutable XML DTO. Unknown selectors remain stored but have no executable binding. */
data class BindingRecord(
    var id: String = "", var enabled: Boolean = true, var role: String = "primary",
    var kind: String = "unconfirmed", var host: String = "127.0.0.1", var port: Int = 6742,
    var type: Int = 0, var vendor: String = "", var serial: String = "", var name: String = "",
    var location: String = "", var session: Long = 0, var revision: Long = 0,
    var epoch: String = "", var index: Int = 0
) {
    companion object {
        fun from(configured: ConfiguredBinding, previous: BindingRecord? = null): BindingRecord {
            val binding = configured.binding
            val record = previous?.copy() ?: BindingRecord()
            record.id = binding.id; record.enabled = configured.enabled; record.role = configured.role
            record.host = binding.endpoint.host; record.port = binding.endpoint.port
            when (val selector = binding.selector) {
                is DeviceSelector.Serial -> {
                    record.kind = "serial"; record.type = selector.type; record.vendor = selector.vendor
                    record.serial = selector.serial
                }

                is DeviceSelector.Port -> {
                    record.kind = "port"; record.type = selector.type; record.vendor = selector.vendor
                    record.name = selector.name; record.location = selector.location
                }

                is DeviceSelector.SessionOnly -> {
                    record.kind = "session"; record.session = selector.token.session.value
                    record.revision = selector.token.revision; record.epoch = selector.token.epoch
                    record.index = selector.index
                }
            }
            return record
        }
    }
}
