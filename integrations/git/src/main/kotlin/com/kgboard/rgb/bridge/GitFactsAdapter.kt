package com.kgboard.rgb.bridge

import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.kgboard.rgb.runtime.*
import git4idea.repo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Optional Git4Idea adapter. Poll/callback reads are serialized and lifetime-bound. */
class GitFactsAdapter(
    private val project: Project, private val binding: ProjectRuntimeBinding,
    parentScope: CoroutineScope, private val emit: (RuntimeEvent) -> Unit = binding::emit,
    private val settings: () -> ProjectSettingsSnapshot
) : Disposable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    fun start() {
        project.messageBus.connect(this)
            .subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { changes.trySend(Unit) })
        scope.launch {
            var revision = 0L
            while (isActive) {
                val facts = readAction {
                    if (project.isDisposed) return@readAction null
                    val repos = GitRepositoryManager.getInstance(project).repositories
                    GitFacts(
                        repos.isNotEmpty(),
                        repos.any { it.state == Repository.State.MERGING || it.state == Repository.State.REBASING },
                        ChangeListManager.getInstance(project).allChanges.isNotEmpty(),
                        repos.any { repo ->
                            val branch = repo.currentBranch
                            val tracking = repo.branchTrackInfos.firstOrNull { it.localBranch == branch }
                            if (branch == null || tracking == null) false else {
                                val local = repo.branches.getHash(branch);
                                val remote = repo.branches.getHash(tracking.remoteBranch)
                                local != null && remote != null && local != remote
                            }
                        },
                        repos.firstNotNullOfOrNull { it.currentBranch?.name })
                }
                ensureActive()
                if (facts != null) emit(RuntimeEvent.GitChanged(binding.id, ++revision, facts))
                withTimeoutOrNull(settings().gitPollingIntervalSec.coerceAtLeast(2) * 1000L) { changes.receive() }
            }
        }
    }

    override fun dispose() {
        job.cancel(); changes.close()
    }
}
