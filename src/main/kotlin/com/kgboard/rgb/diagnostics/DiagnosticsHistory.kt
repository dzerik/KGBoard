package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.runtime.frozenList

/** Actor-confined, bounded, in-memory history. No frame colors or clock ticks are recorded. */
class DiagnosticsHistory {
    private val entries = ArrayDeque<HistoryEntry>()
    fun record(entry: HistoryEntry) {
        val previous = entries.lastOrNull()
        if (previous?.key == entry.key && previous.reasonKey == entry.reasonKey) {
            entries.removeLast()
            entries.addLast(
                previous.copy(
                    lastSeenMs = entry.lastSeenMs,
                    count = (previous.count.toLong() + entry.count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            )
        } else {
            entries.addLast(entry)
            if (entries.size > 100) entries.removeFirst()
        }
    }

    fun entries(): List<HistoryEntry> = frozenList(entries.toList())
    fun clear() = entries.clear()
}
