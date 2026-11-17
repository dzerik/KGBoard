package com.kgboard.rgb.runtime

data class TimeSample(val monotonicMs: Long, val wallMs: Long)

data class RuntimeDeadline(val monotonicAtMs: Long, val wallAtMs: Long, val generation: Long) {
    fun remaining(now: TimeSample): Long = maxOf(
        0, minOf(
            safeDifference(monotonicAtMs, now.monotonicMs), safeDifference(wallAtMs, now.wallMs)
        )
    )

    fun wakeAt(now: TimeSample): Long = expiryAt(now.monotonicMs, remaining(now))

    companion object {
        fun after(durationMs: Long, now: TimeSample, generation: Long) = RuntimeDeadline(
            expiryAt(now.monotonicMs, durationMs), expiryAt(now.wallMs, durationMs), generation
        )
    }
}

internal fun safeDifference(target: Long, now: Long): Long = try {
    Math.subtractExact(target, now)
} catch (_: ArithmeticException) {
    if (target >= now) Long.MAX_VALUE else Long.MIN_VALUE
}

/** Explicit synchronized-clock adapter for pure legacy fixtures, never samples a real wall clock. */
internal fun RuntimeState.timeAt(monotonicMs: Long): TimeSample = lastTime ?: TimeSample(monotonicMs, monotonicMs)
internal fun EffectLayer.expiryAt(now: TimeSample): Long? = deadline?.wakeAt(now) ?: expiresAtMs
