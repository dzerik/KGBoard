package com.kgboard.rgb.device

import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.settings.BindingRecord
import java.util.UUID

data class StoredBinding(
    val id: String, val enabled: Boolean, val role: String, val kind: String,
    val host: String, val port: Int, val type: Int, val vendor: String, val serial: String,
    val name: String, val location: String, val session: Long, val revision: Long, val epoch: String, val index: Int
) {
    fun toRecord() = BindingRecord(
        id,
        enabled,
        role,
        kind,
        host,
        port,
        type,
        vendor,
        serial,
        name,
        location,
        session,
        revision,
        epoch,
        index
    )

    fun configuration(): ConfiguredBinding? {
        if (runCatching { UUID.fromString(id) }.isFailure) return null
        val selector = when (kind) {
            "serial" -> DeviceSelector.Serial(type, vendor, serial)
            "port" -> DeviceSelector.Port(type, vendor, name, location)
            "session" -> DeviceSelector.SessionOnly(InventoryToken(SessionId(session), revision, epoch), index)
            else -> return null
        }
        return ConfiguredBinding(DeviceBinding(id, Endpoint(host, port), selector), enabled, role)
    }

    companion object {
        fun capture(record: BindingRecord) = with(record) {
            StoredBinding(
                id,
                enabled,
                role,
                kind,
                host,
                port,
                type,
                vendor,
                serial,
                name,
                location,
                session,
                revision,
                epoch,
                index
            )
        }
    }
}

/** Lossless immutable records plus a cached, unique-ID executable view. */
data class BindingRecords private constructor(val entries: List<StoredBinding>) {
    val configurations: List<ConfiguredBinding> = run {
        val counts = entries.groupingBy { it.id }.eachCount()
        frozenList(entries.filter { counts[it.id] == 1 }.mapNotNull { it.configuration() })
    }

    fun toRecords() = entries.map { it.toRecord() }.toMutableList()
    fun withConfigurations(configurations: List<ConfiguredBinding>): BindingRecords {
        require(configurations.map { it.binding.id }.distinct().size == configurations.size) { "Duplicate binding IDs" }
        val edits = configurations.associateBy { it.binding.id }
        val editable = this.configurations.map { it.binding.id }.toSet()
        val seen = mutableSetOf<String>()
        val records = entries.mapNotNull { stored ->
            val edited = edits[stored.id]
            when {
                edited != null -> if (seen.add(stored.id)) BindingRecord.from(edited, stored.toRecord()) else null
                stored.id !in editable -> stored.toRecord()
                else -> null
            }
        } + configurations.filter { it.binding.id !in seen }.map { BindingRecord.from(it) }
        return capture(records)
    }

    companion object {
        val EMPTY = BindingRecords(emptyList())
        fun capture(records: Collection<BindingRecord>) =
            BindingRecords(frozenList(records.map(StoredBinding::capture)))

        fun from(configurations: List<ConfiguredBinding>) = capture(configurations.map { BindingRecord.from(it) })
    }
}
