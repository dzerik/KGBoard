package com.kgboard.rgb.target

sealed interface TargetSpec {
    data object All : TargetSpec
    data object Unassigned : TargetSpec
    data class NamedKeys(val names: List<String>) : TargetSpec
    data class Group(val id: String) : TargetSpec
    data class RawIndices(val text: String, val fingerprint: String?) : TargetSpec
}
enum class ResolutionStatus { RESOLVED, PARTIAL, UNAVAILABLE, AMBIGUOUS }
data class TargetResolution(val indices: List<Int>, val missing: List<String>,
    val status: ResolutionStatus, val reason: String?)
data class GroupDefinition(val id: String, val name: String, val target: TargetSpec)
