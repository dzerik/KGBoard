package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.kgboard.rgb.bridge.git.*
import com.kgboard.rgb.runtime.*
import git4idea.repo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Collects repository identities briefly; subprocesses run separately without a read lock. */
class GitFactsAdapter(
    private val project: Project, private val binding: ProjectRuntimeBinding, parentScope: CoroutineScope,
    private val emit: (RuntimeEvent) -> Unit = binding::emit, private val settings: () -> ProjectSettingsSnapshot
) : Disposable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any();
    private var closed = false;
    private var revision = 0L
    private val identities = IdentityHashMap<GitRepository, String>()
    private val roots = linkedMapOf<String, GitRepository>()
    private val samples = linkedMapOf<String, GitSample>()
    private val timer = ScheduledThreadPoolExecutor(
        1,
        { task -> Thread(task, "KGBoard Git deadlines").apply { isDaemon = true } }).apply {
        removeOnCancelPolicy = true; executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    private fun now() = System.nanoTime() / 1_000_000
    private val runner = GitStatusRunner(project)
    private val sampler = GitRepositorySampler(Dispatchers.IO.asExecutor(), object : RuntimeScheduler {
        override fun scheduleAt(timeMs: Long, action: () -> Unit): Cancellation {
            val future = timer.schedule(action, (timeMs - now()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            return Cancellation { future.cancel(false) }
        }
    }, ::now, { id, cancel ->
        val repository = synchronized(lock) { roots[id] } ?: throw java.util.concurrent.CancellationException()
        val bytes = runner.status(repository.root, cancel)
        val operation = when (repository.state.name) {
            "MERGING" -> RepositoryOperation.MERGE; "REBASING" -> RepositoryOperation.REBASE
            "NORMAL", "DETACHED" -> RepositoryOperation.NONE; else -> RepositoryOperation.OTHER
        }
        PorcelainV2Parser.parse(bytes, id, now()).copy(operation = operation)
    }, ::accept)

    fun start() {
        project.messageBus.connect(this)
            .subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository ->
                val id = synchronized(lock) { identities[repository] }
                id?.let(sampler::invalidate); changes.trySend(Unit)
            })
        scope.launch {
            while (isActive) {
                val repositories =
                    readAction { if (project.isDisposed) emptyList() else GitRepositoryManager.getInstance(project).repositories.toList() }
                ensureActive()
                val removed: List<String>;
                val added: List<String>;
                val existing: List<String>
                synchronized(lock) {
                    if (closed) return@launch
                    val current = repositories.associateBy { identities.getOrPut(it) { UUID.randomUUID().toString() } }
                    removed = (roots.keys - current.keys).toList(); added =
                    (current.keys - roots.keys).toList(); existing = (current.keys.intersect(roots.keys)).toList()
                    roots.clear(); roots.putAll(current)
                    removed.forEach(samples::remove)
                    identities.keys.removeIf { old -> repositories.none { it === old } }
                    added.forEach { id ->
                        samples[id] = GitSample(RepoRevision(id, 0, 0), RepositoryFacts.unknown(id, now()))
                    }
                    publish()
                }
                removed.forEach(sampler::remove); added.forEach(sampler::add); existing.forEach(sampler::request)
                withTimeoutOrNull(settings().gitPollingIntervalSec.coerceAtLeast(2) * 1000L) { changes.receive() }
            }
        }
    }

    private fun accept(sample: GitSample) = synchronized(lock) {
        val id = sample.revision.repositoryId
        if (closed || id !in roots || samples[id]?.let { sample.revision.olderThan(it.revision) } == true) return@synchronized
        samples[id] = sample; publish()
    }

    /** The callback only enqueues a detached batch; no SDK work takes place while holding the gate. */
    private fun publish() {
        emit(RuntimeEvent.GitChanged(binding.id, ++revision, GitFacts(roots.keys.mapNotNull(samples::get))))
    }

    override fun dispose() {
        synchronized(lock) { if (closed) return; closed = true; roots.clear(); samples.clear(); identities.clear() }
        sampler.close(); job.cancel(); changes.close(); timer.shutdownNow()
    }
}
