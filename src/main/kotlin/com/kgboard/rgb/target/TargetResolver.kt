package com.kgboard.rgb.target

import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.device.LayoutFingerprint
import com.kgboard.rgb.layout.KeyNames
import com.kgboard.rgb.settings.SettingsValidation

/** Pure per-device target resolution. Missing/invalid never means all LEDs. */
object TargetResolver {
    private fun unavailable(reason: String, missing: List<String> = emptyList()) =
        TargetResolution(emptyList(), missing, ResolutionStatus.UNAVAILABLE, reason)

    fun resolve(target: TargetSpec, device: RgbDeviceInfo,
                groups: Map<String, GroupDefinition>): TargetResolution {
        if (device.numLeds <= 0) return unavailable("target.emptyDevice")
        var current = target
        val visited = mutableSetOf<String>()
        while (current is TargetSpec.Group) {
            if (!visited.add(current.id)) return unavailable("target.cyclicGroup")
            val group = groups[current.id] ?: return unavailable("target.missingGroup", listOf(current.id))
            current = group.target
        }
        return when (val resolved = current) {
            TargetSpec.All -> TargetResolution((0 until device.numLeds).toList(), emptyList(), ResolutionStatus.RESOLVED, null)
            TargetSpec.Unassigned -> unavailable("target.unassigned")
            is TargetSpec.Group -> unavailable("target.cyclicGroup")
            is TargetSpec.RawIndices -> {
                if (resolved.fingerprint == null || resolved.fingerprint != LayoutFingerprint.of(device))
                    return unavailable("target.confirmLayout")
                if (resolved.text.isBlank()) return unavailable("target.emptyIndices")
                val indices = try { SettingsValidation.indices(resolved.text, device.numLeds) }
                    catch (_: IllegalArgumentException) { return unavailable("target.invalidIndices") }
                TargetResolution(indices.sorted(), emptyList(), ResolutionStatus.RESOLVED, null)
            }
            is TargetSpec.NamedKeys -> named(resolved.names, device)
        }
    }

    private fun named(names: List<String>, device: RgbDeviceInfo): TargetResolution {
        if (names.isEmpty()) return unavailable("target.emptyKeys")
        val exact = device.ledNames.withIndex().filter { it.index < device.numLeds }.groupBy({ it.value }, { it.index })
        val aliases = mutableMapOf<String, MutableSet<Int>>()
        fun alias(name: String, index: Int) {
            if (name.isNotBlank() && index < device.numLeds) aliases.getOrPut(KeyNames.normalize(name)) { linkedSetOf() }.add(index)
        }
        device.ledNames.forEachIndexed { i, name -> alias(name, i) }
        device.displayNames.forEachIndexed { i, name -> alias(name, i) }
        val indices = mutableSetOf<Int>(); val missing = mutableListOf<String>(); var ambiguous = false
        names.distinct().forEach { name ->
            val matches = exact[name] ?: aliases[KeyNames.normalize(name)]?.toList().orEmpty()
            when (matches.size) {
                0 -> missing.add(name)
                1 -> indices.add(matches.single())
                else -> { ambiguous = true; missing.add(name) }
            }
        }
        val status = when {
            ambiguous -> ResolutionStatus.AMBIGUOUS
            indices.isEmpty() -> ResolutionStatus.UNAVAILABLE
            missing.isNotEmpty() -> ResolutionStatus.PARTIAL
            else -> ResolutionStatus.RESOLVED
        }
        val reason = when (status) {
            ResolutionStatus.RESOLVED -> null
            ResolutionStatus.AMBIGUOUS -> "target.ambiguousKeys"
            ResolutionStatus.PARTIAL -> "target.partialKeys"
            ResolutionStatus.UNAVAILABLE -> "target.missingKeys"
        }
        return TargetResolution(indices.sorted(), missing, status, reason)
    }
}
