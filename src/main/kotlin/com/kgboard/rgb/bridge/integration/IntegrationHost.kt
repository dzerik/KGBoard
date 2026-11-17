package com.kgboard.rgb.bridge.integration

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kgboard.rgb.bridge.ProjectRuntimeBinding
import com.kgboard.rgb.runtime.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** Project-owned leases. Provider construction/disposal never runs under the callback gate. */
class IntegrationHost(
    private val project: Project, private val binding: ProjectRuntimeBinding,
    parentScope: CoroutineScope? = null
) : Disposable {
    private val listenerScope = CoroutineScope(
        (parentScope?.coroutineContext ?: Dispatchers.Default) + SupervisorJob(parentScope?.coroutineContext?.get(Job))
    )

    private data class Running(
        val lease: ProviderLease, var adapter: Disposable? = null,
        var initializing: Boolean = true, val pending: MutableMap<Any, RuntimeEvent> = linkedMapOf()
    )

    private val lock = Any()
    private val running = mutableMapOf<String, Running>()
    private var started = false
    private var closed = false

    fun start() {
        synchronized(lock) {
            if (started || closed) return
            started = true
            IntegrationProvider.EP_NAME.addExtensionPointListener(
                listenerScope,
                object : ExtensionPointListener<IntegrationProvider> {
                    override fun extensionAdded(extension: IntegrationProvider, pluginDescriptor: PluginDescriptor) =
                        add(extension)

                    override fun extensionRemoved(extension: IntegrationProvider, pluginDescriptor: PluginDescriptor) =
                        remove(extension.capabilityId)
                })
        }
        IntegrationProvider.EP_NAME.extensionList.forEach(::add)
    }

    private fun add(provider: IntegrationProvider) {
        val id = provider.capabilityId
        val entry = synchronized(lock) {
            if (closed || project.isDisposed || id in running) return
            Running(ProviderLease(binding.id, id, sequence.incrementAndGet())).also { running[id] = it }
        }
        val adapter = try {
            provider.create(IntegrationContext(project, entry.lease, binding) { accept(entry, it) })
        } catch (error: Throwable) {
            val current = synchronized(lock) {
                entry.pending.clear()
                if (running[id] === entry) {
                    running.remove(id); true
                } else false
            }
            if (error !is Exception && error !is LinkageError) throw error
            val cancelled =
                error is java.util.concurrent.CancellationException || error is com.intellij.openapi.progress.ProcessCanceledException
            if (current) binding.emit(
                RuntimeEvent.ProviderStopped(
                    entry.lease,
                    if (cancelled) "capability.unloaded" else "capability.failed"
                )
            )
            if (cancelled) throw error
            return
        }
        val accepted = synchronized(lock) {
            if (!closed && running[id] === entry) {
                entry.adapter = adapter; entry.initializing = false
                binding.emit(RuntimeEvent.ProviderStarted(entry.lease))
                entry.pending.values.forEach { binding.emit(RuntimeEvent.ProviderFact(entry.lease, it)) }
                entry.pending.clear()
                true
            } else false
        }
        if (!accepted) Disposer.dispose(adapter)
    }

    private fun accept(entry: Running, fact: RuntimeEvent): Unit = synchronized(lock) {
        if (closed || running[entry.lease.capability] !== entry || !providerFactAllowed(entry.lease, fact)) return
        if (entry.initializing) {
            val key: Any = when (fact) {
                is RuntimeEvent.OperationChanged -> fact.operation.id
                is RuntimeEvent.GitChanged -> "git"
                else -> return
            }
            entry.pending[key] = fact
        } else binding.emit(RuntimeEvent.ProviderFact(entry.lease, fact))
    }

    private fun remove(id: String) {
        val entry = synchronized(lock) { running.remove(id)?.also { it.pending.clear() } } ?: return
        stop(entry)
    }

    private fun stop(entry: Running) {
        try {
            entry.adapter?.let(Disposer::dispose)
        } finally {
            binding.emit(RuntimeEvent.ProviderStopped(entry.lease, "capability.unloaded"))
        }
    }

    override fun dispose() {
        val entries = synchronized(lock) {
            if (closed) return
            closed = true
            running.values.toList().also { running.clear(); it.forEach { entry -> entry.pending.clear() } }
        }
        listenerScope.cancel()
        var failure: Throwable? = null
        for (entry in entries) try {
            stop(entry)
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    companion object {
        private val sequence = AtomicLong()
    }
}
