package com.kgboard.rgb.runtime

enum class CapabilityStatus { AVAILABLE, UNAVAILABLE, ERROR }
data class ProviderLease(val project: ProjectId, val capability: String, val generation: Long)
data class CapabilitySnapshot(val id: String, val status: CapabilityStatus, val reasonKey: String?)

internal fun defaultCapabilities(): Map<String, CapabilitySnapshot> = frozenMap(
    listOf("git", "tests.sm", "build.roots", "debug").associateWith {
        CapabilitySnapshot(it, CapabilityStatus.UNAVAILABLE, "capability.notInstalled")
    })

/** Providers report facts only. Commands, nested envelopes and foreign-project facts are rejected. */
internal fun providerFactAllowed(lease: ProviderLease, fact: RuntimeEvent): Boolean = when (fact) {
    is RuntimeEvent.OperationChanged -> fact.project == lease.project
    is RuntimeEvent.GitChanged -> fact.project == lease.project && lease.capability == "git"
    else -> false
}

/** Safe transport code, independent of provider-supplied labels or exception text. */
internal fun CapabilitySnapshot.exportCode(): String =
    if (id in setOf(
            "git",
            "tests.sm",
            "build.roots",
            "debug"
        )
    ) "capability.$id.${status.name.lowercase(java.util.Locale.ROOT)}"
    else "capability.unknown"
