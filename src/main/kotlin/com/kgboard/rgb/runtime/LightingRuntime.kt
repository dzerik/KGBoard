package com.kgboard.rgb.runtime

import com.kgboard.rgb.device.*
import com.kgboard.rgb.diagnostics.*

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Sole owner of lighting state. External callbacks only enqueue facts. */
class LightingRuntime(
    private val eventsExecutor: Executor,
    private val clock: RuntimeClock,
    private val scheduler: RuntimeScheduler,
    private val reducer: RuntimeReducer,
    private val policy: LightingPolicy,
    private val writer: FrameWriter,
    initial: RuntimeState,
    private val onError: (Throwable) -> Unit = {}
) : RuntimeEvents, AutoCloseable {
    @Volatile
    private var state = initial
    private val accepting = AtomicBoolean(true)

    private data class Subscription(
        val listener: (RuntimeState) -> Unit,
        val active: AtomicBoolean = AtomicBoolean(true)
    )

    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private var scheduled: Cancellation? = null
    private var wakeGeneration = 0L
    private var outputToken: InventoryToken? = null
    private var renderRevision = initial.lastDecision?.revision?.value ?: 0L
    private val history = DiagnosticsHistory()
    private var significantState: List<Any?>? = null
    private var historySequence = 0L

    private data class OwnedOutput(val binding: DeviceBinding, val token: InventoryToken)

    private var ownedDevices: Map<Int, OwnedOutput> = emptyMap()

    fun snapshot(): RuntimeState = state

    override fun dispatch(event: RuntimeEvent) {
        if (!accepting.get()) return
        try {
            eventsExecutor.execute { if (!state.shuttingDown) process(event) }
        } catch (e: RejectedExecutionException) {
            if (accepting.get()) onError(e)
        }
    }

    fun subscribe(listener: (RuntimeState) -> Unit): Cancellation {
        val subscription = Subscription(listener)
        if (accepting.get()) eventsExecutor.execute {
            if (subscription.active.get() && !state.shuttingDown) {
                subscriptions.add(subscription)
                notify(subscription)
            }
        }
        return Cancellation {
            subscription.active.set(false)
            subscriptions.remove(subscription)
        }
    }

    private fun process(event: RuntimeEvent) {
        if (event is RuntimeEvent.DeliveryReported) {
            val frame = event.receipt.frame
            val delivery = state.diagnostics.delivery[frame.deliveryKey()]
            if (event.receipt is DeliveryReceipt.Failed && frame.token == state.inventory()?.token &&
                frame.revision == delivery?.offered
            ) {
                history.record(
                    HistoryEntry(
                        "delivery:${frame.deliveryKey()}",
                        event.receipt.reason,
                        clock.nowMs(),
                        clock.nowMs(),
                        1
                    )
                )
            }
            state = reducer.reduceDelivery(state, event.receipt)
                .let { it.copy(diagnostics = it.diagnostics.copy(history = history.entries())) }
            subscriptions.forEach { notify(it) }
            return
        }
        if (event == RuntimeEvent.ClearHistory) {
            history.clear()
            state = state.copy(diagnostics = state.diagnostics.copy(history = emptyList()))
            subscriptions.forEach { notify(it) }
            return
        }
        if (event is RuntimeEvent.Wake && event.generation != wakeGeneration) return
        val sample = clock.sample()
        val previous = state.lastTime
        if (event != RuntimeEvent.ResumeDetected && event != RuntimeEvent.Shutdown && previous != null && ResumeDetector.observe(
                previous,
                sample
            )
        )
            processAndRender(RuntimeEvent.ResumeDetected, sample)
        processAndRender(event, sample)
    }

    private fun processAndRender(event: RuntimeEvent, sample: TimeSample) {
        val now = sample.monotonicMs
        val previous = state
        val transition = reducer.reduce(state, event, sample)
        state = transition.state.copy(nowMs = now)
        scheduled?.cancel()
        scheduled = null
        val generation = ++wakeGeneration
        val finalFrames = if (previous.connection is ConnectionState.Ready && event !is RuntimeEvent.InventoryChanged &&
            event !is RuntimeEvent.TransportFailed && event !is RuntimeEvent.ConnectFailed && event != RuntimeEvent.ResumeDetected && transition.commands.any { it is RuntimeCommand.Connect || it is RuntimeCommand.Disconnect }
        ) {
            val previousDecision = policy.decide(previous.copy(preview = null), now, RenderRevision(renderRevision + 1))
            val normal = previousDecision.devices.mapNotNull { it.frame }.associateBy { it.deviceIndex }.toMutableMap()
            previous.preview?.binding?.let { binding ->
                previous.inventory()?.takeIf { it.token == previous.preview?.token }?.let { inventory ->
                    val resolved = DeviceResolver.resolve(binding, inventory, previous.settings.untrustedSerials)
                    if (resolved is DeviceResolution.Resolved && resolved.index !in normal)
                        normal[resolved.index] = FrameEnvelope(
                            inventory.token,
                            binding.id,
                            resolved.index,
                            previousDecision.revision,
                            frozenList(policy.retiredFrame(previous, inventory.devices[resolved.index]))
                        )
                }
            }
            normal.toMap()
        } else emptyMap()
        transition.commands.forEach { command ->
            when (command) {
                is RuntimeCommand.Probe -> writer.probe(command.session)
                is RuntimeCommand.Connect -> writer.connect(command.endpoint, command.session, finalFrames)
                is RuntimeCommand.Disconnect -> writer.disconnect(command.session, finalFrames)
            }
        }
        render(now)
        if (event == RuntimeEvent.ResumeDetected)
            history.record(
                HistoryEntry(
                    "resume:${state.connectionGeneration}",
                    "diagnostics.clockChanged",
                    now,
                    now,
                    1
                )
            )
        recordSignificantState(now)
        subscriptions.forEach { notify(it) }
        if (state.shuttingDown) {
            accepting.set(false)
            subscriptions.clear()
            writer.close()
        } else listOfNotNull(
            state.lastDecision?.nextWakeAtMs,
            (state.connection as? ConnectionState.Backoff)?.retryAtMs,
            state.probeAtMs
        ).minOrNull()?.takeIf { it > now }?.let { due ->
            scheduled = scheduler.scheduleAt(due) { dispatch(RuntimeEvent.Wake(generation)) }
        }
    }

    private fun render(now: Long) {
        val inventory = state.inventory()
        if (inventory?.token != outputToken) {
            outputToken = inventory?.token
            ownedDevices = emptyMap()
        }
        var decision = policy.decide(state, now, RenderRevision(++renderRevision))
        val currentFrames = decision.devices.mapNotNull { it.frame }
        if (inventory == null) {
            state = state.copy(
                lastDecision = decision,
                diagnostics = state.diagnostics.copy(decision = decision, delivery = emptyMap())
            )
            return
        }
        val indices = currentFrames.map { it.deviceIndex }.toSet()
        val remaining = decision.devices.toMutableList()
        val retired = mutableListOf<DeviceDecision>()
        for ((oldIndex, owned) in ownedDevices.filterKeys { it !in indices }) {
            if (owned.token != inventory.token) continue
            val resolution = DeviceResolver.resolve(owned.binding, inventory, state.settings.untrustedSerials)
            if (resolution !is DeviceResolution.Resolved || resolution.index != oldIndex) continue
            val device = inventory.devices[oldIndex]
            if (device.numLeds <= 0) continue
            val existing = remaining.indexOfFirst { it.bindingId == owned.binding.id && it.frame == null }
            val explanation = if (existing >= 0) remaining.removeAt(existing) else DeviceDecision(
                owned.binding.id, oldIndex, null,
                frozenList(
                    state.lastDecision?.devices?.firstOrNull {
                        it.bindingId == owned.binding.id && it.deviceIndex == oldIndex
                    }?.rules.orEmpty().map {
                        it.copy(
                            visible = emptyList(),
                            reasons = frozenSet(it.reasons + SuppressionReason.UNASSIGNED)
                        )
                    }),
                "diagnostics.retired"
            )
            retired.add(
                explanation.copy(
                    deviceIndex = oldIndex, frame = FrameEnvelope(
                        inventory.token,
                        owned.binding.id, oldIndex, decision.revision, frozenList(policy.retiredFrame(state, device))
                    )
                )
            )
        }
        if (retired.isNotEmpty()) decision = decision.copy(devices = frozenList(retired + remaining))
        val frames = decision.devices.mapNotNull { it.frame }
        val keys = frames.map { it.deliveryKey() }.toSet()
        val delivery = if (state.diagnostics.decision?.token == decision.token)
            state.diagnostics.delivery.filterKeys { it in keys }.toMutableMap() else mutableMapOf()
        state = state.copy(lastDecision = decision)
        for (frame in frames) if (writer.offer(frame))
            delivery[frame.deliveryKey()] = (delivery[frame.deliveryKey()] ?: DeliveryState()).accept(frame.revision)
        state = state.copy(diagnostics = state.diagnostics.copy(decision = decision, delivery = frozenMap(delivery)))
        ownedDevices = currentFrames.mapNotNull { frame ->
            val binding =
                state.settings.bindings.firstOrNull { it.enabled && it.binding.id == frame.bindingId }?.binding
                    ?: state.preview?.binding?.takeIf { it.id == frame.bindingId }
            binding?.let { frame.deviceIndex to OwnedOutput(it, frame.token) }
        }.toMap()
    }

    private fun recordSignificantState(now: Long) {
        val decision = state.lastDecision
        val summary = listOf(
            state.connection::class.simpleName, state.connectionError, state.focused,
            state.settings.enabled, state.activeProject, state.preview?.owner,
            decision?.devices?.map { device ->
                listOf(
                    device.bindingId, device.deviceIndex, device.idleReasonKey,
                    device.rules.map {
                        listOf(
                            it.ruleId,
                            it.owner,
                            it.source,
                            it.requested.size,
                            it.visible.size,
                            it.reasons
                        )
                    })
            })
        if (summary != significantState) {
            significantState = summary
            val reason = when {
                state.connectionError != null -> "transport.failed"
                state.manuallyDisconnected -> "diagnostics.disconnected"
                else -> "diagnostics.lightingChanged"
            }
            history.record(HistoryEntry("transition:${++historySequence}", reason, now, now, 1))
        }
        state = state.copy(diagnostics = state.diagnostics.copy(history = history.entries()))
    }

    private fun notify(subscription: Subscription) {
        if (subscription.active.get()) try {
            subscription.listener(state)
        } catch (e: Exception) {
            onError(e)
        }
    }

    override fun close() {
        if (accepting.getAndSet(false)) eventsExecutor.execute {
            if (!state.shuttingDown) processAndRender(RuntimeEvent.Shutdown, clock.sample())
        }
    }
}
