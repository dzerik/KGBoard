package com.kgboard.rgb.bridge.git

import java.util.concurrent.atomic.AtomicReference

/** Cancellation sees this slot before the SDK assigns its own Process field. */
class GitProcessSlot(private val cancellation: GitCancellation) {
    private val process = AtomicReference<Process?>()

    init {
        cancellation.onCancel { process.get()?.destroyForcibly() }
    }

    fun exitCode(): Int? = process.get()?.exitValue()
    fun attach(created: Process) {
        check(process.compareAndSet(null, created))
        if (cancellation.cancelled) created.destroyForcibly()
    }
}
