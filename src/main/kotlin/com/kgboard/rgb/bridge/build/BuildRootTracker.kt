package com.kgboard.rgb.bridge.build

import com.kgboard.rgb.bridge.OperationIdentityRegistry
import com.kgboard.rgb.runtime.*

/** A root emits one running and one terminal revision. Retired SDK keys remain weak. */
class BuildRootTracker(
    private val emit: (OperationState) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) : AutoCloseable {
    private val identities = OperationIdentityRegistry()
    private val active = mutableMapOf<Any, OperationId>()
    private var closed = false
    @Synchronized
    fun accept(event: BuildObservation) {
        if (closed) return
        when (event) {
            is BuildObservation.Started -> {
                if (event.id in active) return
                val id = identities.id(event.id) ?: return
                active[event.id] = id
                emit(
                    OperationState(
                        id, OperationKind.BUILD, OperationPhase.RUNNING,
                        revision = identities.nextRevision(id)!!, changedAtMs = now()
                    )
                )
            }

            is BuildObservation.Finished -> {
                val id = active.remove(event.id) ?: return
                emit(
                    OperationState(
                        id, OperationKind.BUILD, OperationPhase.TERMINATED, event.outcome,
                        revision = identities.nextRevision(id)!!, changedAtMs = now()
                    )
                )
                identities.release(id)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        active.values.forEach { id ->
            emit(
                OperationState(
                    id, OperationKind.BUILD, OperationPhase.TERMINATED,
                    OperationOutcome.NOT_EXECUTED, revision = identities.nextRevision(id)!!, changedAtMs = now()
                )
            )
        }
        active.clear()
        identities.clear()
    }
}
