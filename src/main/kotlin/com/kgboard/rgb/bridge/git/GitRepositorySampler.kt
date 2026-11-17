package com.kgboard.rgb.bridge.git

import com.kgboard.rgb.runtime.*
import java.util.concurrent.Executor

/** Cancellation registration closes the gap between cancellation and process creation. */
class GitCancellation {
    private val lock = Any()

    @Volatile
    var reasonKey: String? = null; private set
    private var callback: (() -> Unit)? = null
    val cancelled: Boolean get() = reasonKey != null
    fun onCancel(action: () -> Unit) {
        val run = synchronized(lock) {
            if (cancelled) true else {
                check(callback == null); callback = action; false
            }
        }
        if (run) action()
    }

    fun cancel(reason: String = "git.cancelled") {
        val action = synchronized(lock) { if (cancelled) return; reasonKey = reason; callback.also { callback = null } }
        action?.invoke()
    }
}

/** One process and one pending refresh per repository. No SDK object enters this boundary. */
class GitRepositorySampler(
    private val executor: Executor, private val scheduler: RuntimeScheduler, private val now: () -> Long,
    private val status: (String, GitCancellation) -> RepositoryFacts, private val publish: (GitSample) -> Unit
) : AutoCloseable {
    private class Entry {
        var head = 0L;
        var request = 0L;
        var active: Run? = null;
        var pending = false
    }

    private class Run(val revision: RepoRevision, val token: GitCancellation = GitCancellation()) {
        var timer: Cancellation? = null
    }

    private val lock = Any();
    private val entries = mutableMapOf<String, Entry>();
    private var closed = false
    fun add(id: String) {
        synchronized(lock) { if (closed || id in entries) return; entries[id] = Entry() }
        request(id)
    }

    fun request(id: String) = queue(id, false)
    fun invalidate(id: String) = queue(id, true)
    private fun queue(id: String, invalidate: Boolean) {
        var unknown: GitSample? = null
        val run = synchronized(lock) {
            val e = entries[id] ?: return; if (closed) return
            if (invalidate) {
                e.head++; unknown =
                    GitSample(RepoRevision(id, e.request + 1, e.head), RepositoryFacts.unknown(id, now()))
            }
            if (e.active != null) {
                e.pending = true; null
            } else create(id, e)
        }
        unknown?.let(publish); run?.let { launch(id, it) }
    }

    private fun create(id: String, e: Entry) = Run(RepoRevision(id, ++e.request, e.head)).also { e.active = it }
    private fun launch(id: String, run: Run) {
        val at = now().let { if (it > Long.MAX_VALUE - 10_000) Long.MAX_VALUE else it + 10_000 }
        val timer = try {
            scheduler.scheduleAt(at) { run.token.cancel("git.timeout") }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            complete(id, run, RepositoryFacts.unknown(id, now()), "git.commandFailed"); return
        }
        val valid = synchronized(lock) {
            if (entries[id]?.active === run && !closed) {
                run.timer = timer; true
            } else false
        }
        if (!valid) {
            timer.cancel(); run.token.cancel(); return
        }
        try {
            executor.execute {
                if (synchronized(lock) { closed || entries[id]?.active !== run }) return@execute
                var errorKey: String? = null
                val sample = try {
                    if (run.token.cancelled) RepositoryFacts.unknown(id, now()) else status(id, run.token)
                } catch (_: Exception) {
                    errorKey = "git.commandFailed"; RepositoryFacts.unknown(id, now())
                } catch (_: LinkageError) {
                    errorKey = "git.commandFailed"; RepositoryFacts.unknown(id, now())
                } finally {
                    run.timer?.cancel()
                }
                complete(id, run, sample, errorKey)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            timer.cancel(); complete(id, run, RepositoryFacts.unknown(id, now()), "git.commandFailed")
        }
    }

    private fun complete(id: String, run: Run, sample: RepositoryFacts, failure: String?) {
        var result: GitSample? = null
        val next = synchronized(lock) {
            val e = entries[id] ?: return
            if (closed || e.active !== run) return
            val error = run.token.reasonKey ?: failure
            ?: if (sample.freshness == Freshness.UNKNOWN) "git.invalidStatus" else null
            if (e.head == run.revision.headRevision) result = GitSample(
                run.revision,
                if (error == null) sample else RepositoryFacts.unknown(id, now()), error
            )
            e.active = null
            if (run.token.reasonKey == "git.timeout") e.pending = false
            if (e.pending) {
                e.pending = false; create(id, e)
            } else null
        }
        try {
            result?.let(publish)
        } finally {
            next?.let { launch(id, it) }
        }
    }

    fun remove(id: String) {
        val run = synchronized(lock) { entries.remove(id)?.active }; run?.timer?.cancel(); run?.token?.cancel()
    }

    override fun close() {
        val active = synchronized(lock) {
            if (closed) return; closed = true; entries.values.mapNotNull { it.active }.also { entries.clear() }
        }
        active.forEach { it.timer?.cancel(); it.token.cancel() }
    }
}
