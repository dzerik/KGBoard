package com.kgboard.rgb.runtime

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
    @Volatile private var state = initial
    private val accepting = AtomicBoolean(true)
    private data class Subscription(val listener: (RuntimeState) -> Unit, val active: AtomicBoolean = AtomicBoolean(true))
    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private var scheduled: Cancellation? = null
    private var wakeGeneration = 0L
    private var outputSession: SessionId? = null
    private var ownedDevices: Set<Int> = emptySet()

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
            event !is RuntimeEvent.TransportFailed && event !is RuntimeEvent.ConnectFailed && transition.commands.any { it is RuntimeCommand.Connect || it is RuntimeCommand.Disconnect }) {
            val normal = policy.frames(previous.copy(preview = null), now).toMutableMap()
            previous.preview?.device?.takeIf { it !in normal }?.let { index ->
                previous.connection.devices.getOrNull(index)?.let { device -> normal[index] = List(device.numLeds) { LightingPolicy.color(previous.settings.idleColor) } }
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
        } else listOfNotNull(policy.nextWakeAt(state, now), (state.connection as? ConnectionState.Backoff)?.retryAtMs, state.probeAtMs).minOrNull()?.let { due ->
            scheduled = scheduler.scheduleAt(due) { dispatch(RuntimeEvent.Wake(generation)) }
        }
    }

    private fun render(now: Long) {
        val ready = state.connection as? ConnectionState.Ready
        if (ready?.session != outputSession) {
            outputSession = ready?.session
            ownedDevices = emptySet()
        }
        if (ready == null) return
        val frames = policy.frames(state, now)
        for (removed in ownedDevices - frames.keys) ready.devices.getOrNull(removed)?.let { device ->
            writer.offer(ready.session, removed, List(device.numLeds) { if (state.shuttingDown && state.settings.resetOnExit) java.awt.Color.BLACK else LightingPolicy.color(state.settings.idleColor) })
        }
        frames.forEach { (device, frame) -> writer.offer(ready.session, device, frame) }
        ownedDevices = frozenSet(frames.keys)
    }

    private fun notify(subscription: Subscription) {
        if (subscription.active.get()) try { subscription.listener(state) } catch (e: Exception) { onError(e) }
    }

    override fun close() {
        if (accepting.getAndSet(false)) eventsExecutor.execute {
            if (!state.shuttingDown) process(RuntimeEvent.Shutdown)
        }
    }
}
