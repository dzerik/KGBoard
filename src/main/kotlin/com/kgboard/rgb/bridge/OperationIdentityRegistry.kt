package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.*
import java.util.WeakHashMap
import java.util.UUID

/** Weak retired keys reject duplicate callbacks without retaining completed handlers. */
class OperationIdentityRegistry {
    private data class Entry(val id: OperationId, var retired: Boolean = false)
    private val identities = WeakHashMap<Any, Entry>()
    private val revisions = mutableMapOf<OperationId, Long>()
    val activeCount: Int get() = synchronized(this) { revisions.size }
    @Synchronized fun id(key: Any): OperationId? {
        val entry = identities.getOrPut(key) { Entry(OperationId(UUID.randomUUID().toString())) }
        if (entry.retired) return null
        revisions.putIfAbsent(entry.id, 0)
        return entry.id
    }
    @Synchronized fun revision(id: OperationId): Long? = revisions[id]
    @Synchronized fun nextRevision(id: OperationId, expected: Long? = null): Long? = revisions[id]?.let {
        if (expected != null && it != expected) null else (it + 1).also { revision -> revisions[id] = revision }
    }
    @Synchronized fun release(id: OperationId) {
        identities.values.firstOrNull { it.id == id }?.retired = true
        revisions.remove(id)
    }
    @Synchronized fun clear() { identities.clear(); revisions.clear() }
}

fun testOutcome(cancelled: Boolean, totalLeaves: Int, ignored: Int, failures: Int): OperationOutcome = when {
    cancelled -> OperationOutcome.CANCELLED
    failures > 0 -> OperationOutcome.FAILURE
    totalLeaves - ignored <= 0 -> OperationOutcome.NOT_EXECUTED
    else -> OperationOutcome.SUCCESS
}

/** SDK suites may fail before any discovered leaf is launched. */
fun outcome(root: com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy): OperationOutcome {
    val all = root.allTests
    val leaves = all.filter { it !== root && !it.isSuite }
    val launched = leaves.filter { it.wasLaunched() }
    return testOutcome(root.wasTerminated(), launched.size, launched.count { it.isIgnored },
        (all + root).count { it.isDefect && !it.isIgnored })
}
