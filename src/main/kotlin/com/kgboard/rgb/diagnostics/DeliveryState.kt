package com.kgboard.rgb.diagnostics

sealed interface DeliveryReceipt {
    val frame: FrameEnvelope

    data class Sent(override val frame: FrameEnvelope, val sentAtMs: Long) : DeliveryReceipt
    data class Failed(override val frame: FrameEnvelope, val reason: String) : DeliveryReceipt
    data class Cached(override val frame: FrameEnvelope, val sentRevision: RenderRevision, val sentAtMs: Long) :
        DeliveryReceipt
}

data class DeliveryState(
    val pending: RenderRevision? = null, val lastSent: RenderRevision? = null,
    val sentAtMs: Long? = null, val error: String? = null,
    val offered: RenderRevision? = pending
) {
    fun accept(revision: RenderRevision) = copy(pending = revision, offered = revision, error = null)

    fun report(receipt: DeliveryReceipt): DeliveryState {
        if (receipt.frame.revision.value > (offered?.value ?: return this)) return this
        val clearsPending = pending?.let { it.value <= receipt.frame.revision.value } ?: true
        if (receipt is DeliveryReceipt.Failed) return if (clearsPending)
            copy(pending = null, error = receipt.reason) else this
        val revision = when (receipt) {
            is DeliveryReceipt.Sent -> receipt.frame.revision
            is DeliveryReceipt.Cached -> receipt.sentRevision
        }
        if (revision.value > receipt.frame.revision.value) return this
        val at = when (receipt) {
            is DeliveryReceipt.Sent -> receipt.sentAtMs
            is DeliveryReceipt.Cached -> receipt.sentAtMs
        }
        val newest = lastSent == null || revision.value >= lastSent.value
        return copy(
            pending = if (clearsPending) null else pending,
            lastSent = if (newest) revision else lastSent, sentAtMs = if (newest) at else sentAtMs,
            error = if (clearsPending) null else error
        )
    }
}

/** A rebind can retire one index and paint another using the same binding UUID. */
fun FrameEnvelope.deliveryKey(): String = "$bindingId@$deviceIndex"

data class HistoryEntry(
    val key: String,
    val reasonKey: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val count: Int
)

data class DiagnosticsSnapshot(
    val decision: LightingDecision? = null, val delivery: Map<String, DeliveryState> = emptyMap(),
    val history: List<HistoryEntry> = emptyList()
)
