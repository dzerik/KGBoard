package com.kgboard.rgb.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.LowMemoryWatcher
import com.kgboard.rgb.bridge.*
import com.kgboard.rgb.client.OpenRgbTransport
import com.kgboard.rgb.layout.KeyboardLayoutService
import com.kgboard.rgb.preset.KeyGroupService
import com.kgboard.rgb.settings.KgBoardSettings
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** The only owner of state, deadlines and the physical OpenRGB writer. */
@Service(Service.Level.APP)
class LightingRuntimeService : Disposable {
    private val log = Logger.getInstance(LightingRuntimeService::class.java)
    private fun thread(name: String) = ThreadFactory { r -> Thread(r, "KGBoard-$name").apply { isDaemon = true } }
    private val actor = Executors.newSingleThreadExecutor(thread("Runtime"))
    private val timer = Executors.newSingleThreadScheduledExecutor(thread("Deadlines"))
    private val network = Executors.newSingleThreadExecutor(thread("OpenRGB"))
    private val clock = RuntimeClock { System.nanoTime() / 1000000 }
    private val transport = OpenRgbTransport { snapshot().settings.skipCustomMode }
    private val runtime: LightingRuntime
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val projects = ConcurrentHashMap<Project, ProjectRuntimeBinding>()
    private val focus: FrameFocusTracker
    private val source: IdeFocusedProjectSource
    val events: RuntimeEvents get() = runtime

    init {
        val initial = RuntimeState(
            settings = RuntimeSettings.capture(com.kgboard.rgb.settings.SettingsMigration.app(KgBoardSettings.getInstance().state)),
            groups = KeyGroupService.getInstance().definitions()
        )
        val writer = CoalescingFrameWriter(transport, network, RuntimeEvents { events.dispatch(it) })
        val scheduler = object : RuntimeScheduler {
            override fun scheduleAt(timeMs: Long, action: () -> Unit): Cancellation {
                val future = timer.schedule(action, (timeMs - clock.nowMs()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                return Cancellation { future.cancel(false) }
            }
        }
        runtime = LightingRuntime(
            actor,
            clock,
            scheduler,
            RuntimeReducer(),
            LightingPolicy(),
            writer,
            initial
        ) { log.warn("Lighting runtime error", it) }
        source = IdeFocusedProjectSource { project -> projects[project]?.id }
        focus = FrameFocusTracker(source, events)
        focus.start()
        LowMemoryWatcher.register(Runnable { events.dispatch(RuntimeEvent.LowMemory) }, this)
        var pomodoroEnabled: Boolean? = null
        var mappingDevice: DeviceMappingRevision? = null
        runtime.subscribe { state ->
            if (closed.get()) return@subscribe
            if (pomodoroEnabled != state.settings.pomodoroEnabled) {
                pomodoroEnabled = state.settings.pomodoroEnabled
                ApplicationManager.getApplication().invokeLater {
                    if (!closed.get()) projects.keys.filter { !it.isDisposed }.forEach { project ->
                        project.getService(com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager::class.java)
                            .updateWidget(com.kgboard.rgb.pomodoro.PomodoroWidgetFactory::class.java)
                    }
                }
            }
            val key = state.primaryMapping()
            if (key != mappingDevice) {
                mappingDevice = key
                val layout = KeyboardLayoutService.getInstance()
                val device = state.primaryDevice()?.device
                if (device == null) layout.clear() else layout.refreshForDevice(device, key)
                KeyGroupService.getInstance().refreshFromDevice()
            }
            KgBoardSettings.getInstance().mergeObservedSerials(state.settings.untrustedSerials)

        }
    }

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            val settings = snapshot().settings
            if (settings.enabled && settings.autoConnect) events.dispatch(RuntimeEvent.ConnectRequested())
        }
    }

    fun register(project: Project, binding: ProjectRuntimeBinding) {
        projects[project] = binding; refreshFocus(); ensureStarted()
    }

    fun unregister(project: Project) {
        projects.remove(project); refreshFocus()
    }

    fun binding(project: Project): ProjectRuntimeBinding? = projects[project]
    fun refreshFocus() = source.refresh()
    fun snapshot() = runtime.snapshot()
    fun subscribe(listener: (RuntimeState) -> Unit) = runtime.subscribe(listener)
    override fun dispose() {
        if (!closed.compareAndSet(false, true)) return
        focus.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(0)
        runtime.close(); timer.shutdownNow(); actor.shutdown()
        try {
            actor.awaitTermination(remaining(), TimeUnit.NANOSECONDS)
            network.shutdown()
            if (!network.awaitTermination(remaining(), TimeUnit.NANOSECONDS)) {
                transport.abort(); network.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); transport.abort(); network.shutdownNow(); actor.shutdownNow()
        }
        projects.clear()
    }

    companion object {
        fun getInstance(): LightingRuntimeService =
            ApplicationManager.getApplication().getService(LightingRuntimeService::class.java)
    }
}
