package com.kgboard.rgb.runtime

object ResumeDetector {
    fun observe(previous: TimeSample, current: TimeSample): Boolean {
        val monoElapsed = safeDifference(current.monotonicMs, previous.monotonicMs)
        val wallElapsed = safeDifference(current.wallMs, previous.wallMs)
        val gap = safeDifference(wallElapsed, monoElapsed)
        return gap > 10_000 || gap < -10_000
    }
}
