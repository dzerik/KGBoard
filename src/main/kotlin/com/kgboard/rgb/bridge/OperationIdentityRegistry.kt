package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.*
import java.util.WeakHashMap
import java.util.UUID

/** Weak retired keys reject duplicate callbacks without retaining completed handlers. */
class OperationIdentityRegistry {
    private data class Entry(val id: OperationId, var retired: Boolean = false)

    private val identities = WeakHashMap<Any, Entry>()
    private val revisions = mutableMapOf<OperationId, Long>()
    private val pending = mutableMapOf<Long, Any>()
    private val processes = WeakHashMap<Any, Any>()
    private val executionOwned = WeakHashMap<Any, Boolean>()
    private val detached = WeakHashMap<Any, Boolean>()

    @Synchronized
    fun reserveExecution(executionId: Long): Any = pending.getOrPut(executionId) { Any() }.also {
        executionOwned[it] = true
    }

    @Synchronized
    fun cancelExecution(executionId: Long): Any? = pending.remove(executionId)

    @Synchronized
    fun processKey(handler: Any, executionId: Long = 0): Any =
        processes.getOrPut(handler) { pending.remove(executionId) ?: Any() }

    @Synchronized
    fun executionKey(handler: Any, executionId: Long): Any = processKey(handler, executionId).also {
        executionOwned[it] = true
    }

    @Synchronized
    fun isExecutionOwned(key: Any): Boolean = executionOwned[key] == true

    @Synchronized
    fun markDetached(key: Any) {
        detached[key] = true
    }

    @Synchronized
    fun executionOutcome(key: Any, exitCode: Int): OperationOutcome = when {
        detached[key] == true -> OperationOutcome.NOT_EXECUTED
        exitCode == 0 -> OperationOutcome.SUCCESS
        else -> OperationOutcome.FAILURE
    }

    val activeCount: Int get() = synchronized(this) { revisions.size }

    @Synchronized
    fun id(key: Any): OperationId? {
        val entry = identities.getOrPut(key) { Entry(OperationId(UUID.randomUUID().toString())) }
        if (entry.retired) return null
        revisions.putIfAbsent(entry.id, 0)
        return entry.id
    }

    @Synchronized
    fun revision(id: OperationId): Long? = revisions[id]

    @Synchronized
    fun nextRevision(id: OperationId, expected: Long? = null): Long? = revisions[id]?.let {
        if (expected != null && it != expected) null else (it + 1).also { revision -> revisions[id] = revision }
    }

    @Synchronized
    fun release(id: OperationId) {
        identities.entries.firstOrNull { it.value.id == id }?.let { (key, entry) ->
            entry.retired = true
            executionOwned.remove(key)
            detached.remove(key)
        }
        revisions.remove(id)
    }

    @Synchronized
    fun clear() {
        identities.clear(); revisions.clear(); pending.clear(); processes.clear(); executionOwned.clear(); detached.clear()
    }
}

fun testOutcome(cancelled: Boolean, totalLeaves: Int, ignored: Int, failures: Int): OperationOutcome = when {
    cancelled -> OperationOutcome.CANCELLED
    failures > 0 -> OperationOutcome.FAILURE
    totalLeaves - ignored <= 0 -> OperationOutcome.NOT_EXECUTED
    else -> OperationOutcome.SUCCESS
}
