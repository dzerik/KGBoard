package com.kgboard.rgb.runtime

import java.awt.Color
import java.util.PriorityQueue
import java.util.concurrent.Executor

internal class ManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }
    val hasPending: Boolean get() = tasks.isNotEmpty()
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
}

internal class ManualClock : RuntimeClock { var value = 0L; override fun nowMs() = value }
internal class ManualScheduler(private val clock: ManualClock, private val executor: ManualExecutor) : RuntimeScheduler {
    private data class Task(val at: Long, val order: Long, val action: () -> Unit, var cancelled: Boolean = false)
    private val tasks = PriorityQueue(compareBy<Task>({ it.at }, { it.order }))
    private var sequence = 0L
    override fun scheduleAt(timeMs: Long, action: () -> Unit): Cancellation {
        val task = Task(timeMs, sequence++, action)
        tasks.add(task)
        return Cancellation { task.cancelled = true }
    }
    fun advanceBy(ms: Long) {
        require(ms >= 0)
        val until = clock.value + ms
        var runs = 0
        while (tasks.peek()?.at?.let { it <= until } == true) {
            check(runs++ < 100_000) { "Scheduler failed to make progress" }
            val task = tasks.remove()
            if (!task.cancelled) {
                clock.value = task.at.coerceAtLeast(clock.value)
                task.action()
                executor.drain()
            }
        }
        clock.value = until
        executor.drain()
    }
}

internal class RecordingFrameWriter : FrameWriter {
    val history = mutableListOf<Pair<Int, List<Color>>>()
    val connects = mutableListOf<Pair<Endpoint, SessionId>>()
    val disconnects = mutableListOf<SessionId>()
    var closed = false
    override fun offer(session: SessionId, device: Int, colors: List<Color>) {
        check(!closed)
        history.add(device to colors.toList())
    }
    override fun connect(endpoint: Endpoint, session: SessionId, finalFrames: Map<Int, List<Color>>) { finalFrames.forEach { (index, frame) -> offer(session, index, frame) }; connects.add(endpoint to session) }
    override fun disconnect(session: SessionId, finalFrames: Map<Int, List<Color>>) { finalFrames.forEach { (index, frame) -> offer(session, index, frame) }; disconnects.add(session) }
    override fun close() { closed = true }
}

internal class RuntimeHarness(settings: RuntimeSettings = RuntimeSettings()) : AutoCloseable {
    val clock = ManualClock()
    val executor = ManualExecutor()
    val scheduler = ManualScheduler(clock, executor)
    val writer = RecordingFrameWriter()
    val errors = mutableListOf<Throwable>()
    val runtime = LightingRuntime(executor, clock, scheduler, RuntimeReducer(), LightingPolicy(), writer,
        RuntimeState(settings = settings, connectionWanted = true, connectionGeneration = 1, connection = ConnectionState.Ready(SessionId(1), listOf(testDevice(), testDevice()))),
        onError = { errors.add(it) })
    fun send(event: RuntimeEvent) { runtime.dispatch(event); executor.drain() }
    fun advanceBy(ms: Long) = scheduler.advanceBy(ms)
    fun drain() = executor.drain()
    fun lastFrame(device: Int = 0): List<Color> = writer.history.last { it.first == device }.second
    fun snapshotForTest() = runtime.snapshot()
    fun project(id: ProjectId = ProjectId("p")) {
        send(RuntimeEvent.ProjectOpened(id))
        send(RuntimeEvent.ProjectActivated(id))
        send(RuntimeEvent.FocusChanged(true))
    }
    override fun close() { runtime.close(); executor.drain() }
}
