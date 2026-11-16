package com.kgboard.rgb.target

/** XML DTO retains unknown kinds; only recognized values convert to executable targets. */
data class TargetRecord(var kind: String = "unassigned", var names: MutableList<String> = mutableListOf(),
    var groupId: String = "", var raw: String = "", var fingerprint: String? = null) {
    fun copyDeep() = copy(names = names.toMutableList())
    fun toSpec(): TargetSpec? = when (kind) {
        "all" -> TargetSpec.All
        "unassigned" -> TargetSpec.Unassigned
        "named" -> TargetSpec.NamedKeys(java.util.Collections.unmodifiableList(names.toList()))
        "group" -> TargetSpec.Group(groupId)
        "raw" -> TargetSpec.RawIndices(raw, fingerprint)
        else -> null
    }
    companion object {
        fun from(spec: TargetSpec): TargetRecord = when (spec) {
            TargetSpec.All -> TargetRecord(kind = "all")
            TargetSpec.Unassigned -> TargetRecord()
            is TargetSpec.NamedKeys -> TargetRecord(kind = "named", names = spec.names.toMutableList())
            is TargetSpec.Group -> TargetRecord(kind = "group", groupId = spec.id)
            is TargetSpec.RawIndices -> TargetRecord(kind = "raw", raw = spec.text, fingerprint = spec.fingerprint)
        }
    }
}
