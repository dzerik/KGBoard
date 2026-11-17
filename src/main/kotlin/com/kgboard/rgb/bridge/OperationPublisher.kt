package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.*

/** Shared identity/revision rules; optional adapters supply a lease-fenced emitter. */
internal class OperationPublisher(
    private val binding: ProjectRuntimeBinding,
    private val emit: (RuntimeEvent) -> Unit = binding::emit
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun change(
        key: Any, kind: OperationKind, phase: OperationPhase,
        outcome: OperationOutcome? = null, parent: OperationId? = null, expected: Long? = null
    ) {
        if (closed) return
        val registry = binding.operations
        val id = registry.id(key) ?: return
        val revision = registry.nextRevision(id, expected) ?: return
        emit(RuntimeEvent.OperationChanged(binding.id, OperationState(id, kind, phase, outcome, parent, revision)))
        if (phase == OperationPhase.TERMINATED) registry.release(id)
    }

    @Synchronized
    override fun close() {
        closed = true
    }
}
