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
        if (event is RuntimeEvent.Wake && event.generation != wakeGeneration) return
        val now = clock.nowMs()
        val previous = state
        val transition = reducer.reduce(state, event, now)
        state = transition.state.copy(nowMs = now)
        scheduled?.cancel()
        scheduled = null
        val generation = ++wakeGeneration
        val finalFrames = if (previous.connection is ConnectionState.Ready && event !is RuntimeEvent.InventoryChanged &&
            event !is RuntimeEvent.TransportFailed && event !is RuntimeEvent.ConnectFailed && transition.commands.any { it is RuntimeCommand.Connect || it is RuntimeCommand.Disconnect }
        ) {
            val normal = policy.frames(previous.copy(preview = null), now).toMutableMap()
            previous.preview?.binding?.let { binding ->
                previous.inventory()?.takeIf { it.token == previous.preview?.token }?.let { inventory ->
                    val resolved = DeviceResolver.resolve(binding, inventory, previous.settings.untrustedSerials)
                    if (resolved is DeviceResolution.Resolved && resolved.index !in normal)
                        normal[resolved.index] = policy.retiredFrame(previous, inventory.devices[resolved.index])
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
        subscriptions.forEach { notify(it) }
        if (state.shuttingDown) {
            accepting.set(false)
            subscriptions.clear()
            writer.close()
        } else listOfNotNull(
            state.lastDecision?.nextWakeAtMs,
            (state.connection as? ConnectionState.Backoff)?.retryAtMs,
            state.probeAtMs
        ).minOrNull()?.let { due ->
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
            state = state.copy(lastDecision = decision)
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
        state = state.copy(lastDecision = decision)
        decision.devices.mapNotNull { it.frame }.forEach(writer::offer)
        ownedDevices = currentFrames.mapNotNull { frame ->
            val binding =
                state.settings.bindings.firstOrNull { it.enabled && it.binding.id == frame.bindingId }?.binding
                    ?: state.preview?.binding?.takeIf { it.id == frame.bindingId }
            binding?.let { frame.deviceIndex to OwnedOutput(it, frame.token) }
        }.toMap()
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
            if (!state.shuttingDown) process(RuntimeEvent.Shutdown)
        }
    }
}
