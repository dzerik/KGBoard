package com.kgboard.rgb.bridge.build

import com.intellij.build.events.*
import com.intellij.openapi.progress.ProcessCanceledException
import com.kgboard.rgb.runtime.OperationOutcome
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

fun buildObservation(event: BuildEvent): BuildObservation? {
    if (event.parentId != null) return null
    return when (event) {
        is StartBuildEvent -> BuildObservation.Started(event.id)
        is FinishBuildEvent -> BuildObservation.Finished(event.id, buildOutcome(event.result))
        else -> null
    }
}

fun buildOutcome(result: EventResult?): OperationOutcome = when (result) {
    is FailureResult -> if (confirmedCancellation(result.failures)) OperationOutcome.CANCELLED else OperationOutcome.FAILURE
    is SuccessResult -> OperationOutcome.SUCCESS
    else -> OperationOutcome.NOT_EXECUTED
}

private fun confirmedCancellation(roots: List<Failure>): Boolean {
    val pending = ArrayDeque(roots)
    val failures = Collections.newSetFromMap(IdentityHashMap<Failure, Boolean>())
    val errors = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    while (pending.isNotEmpty()) {
        val failure = pending.removeFirst(); if (!failures.add(failure)) continue
        var error = failure.error
        while (error != null && errors.add(error)) {
            if (error is CancellationException || error is ProcessCanceledException) return true
            error = error.cause
        }
        pending.addAll(failure.causes)
    }
    return false
}
