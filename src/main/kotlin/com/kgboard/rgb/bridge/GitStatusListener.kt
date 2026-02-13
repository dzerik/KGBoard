package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.dvcs.repo.Repository
import com.kgboard.rgb.effect.*
import com.kgboard.rgb.settings.KgBoardProjectSettings
import com.kgboard.rgb.settings.KgBoardSettings
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors Git repository state and displays per-key RGB indicators.
 *
 * Priority: merge conflict (3) > uncommitted changes (2) > unpushed commits (2) > clean (1)
 * Registered via kgboard-git.xml (only when Git4Idea plugin is available).
 */
@Service(Service.Level.PROJECT)
class GitStatusListener(private val project: Project) : Disposable {

    private val log = Logger.getInstance(GitStatusListener::class.java)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "KGBoard-GitStatus-${project.name}").apply { isDaemon = true }
    }

    private var pollingTask: ScheduledFuture<*>? = null
    private val disposed = AtomicBoolean(false)

    companion object {
        const val EFFECT_ID = "git-status"
        const val BRANCH_COLOR_EFFECT_ID = "git-branch-color"

        fun getInstance(project: Project): GitStatusListener =
            project.getService(GitStatusListener::class.java)
    }

    fun start() {
        val projectSettings = KgBoardProjectSettings.getInstance(project)
        if (!projectSettings.gitEnabled) return

        // Subscribe to Git changes
        project.messageBus.connect(this)
            .subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
                updateGitStatus()
            })

        // Fallback polling
        val intervalSec = projectSettings.gitPollingIntervalSec.toLong().coerceAtLeast(2)
        pollingTask = scheduler.scheduleWithFixedDelay(
            { updateGitStatus() },
            2, intervalSec, TimeUnit.SECONDS
        )

        log.info("Git status listener started (polling every ${intervalSec}s)")
    }

    private fun updateGitStatus() {
        if (disposed.get()) return
        try {
            val settings = KgBoardSettings.getInstance()
            val projectSettings = KgBoardProjectSettings.getInstance(project)
            if (!settings.enabled || !projectSettings.gitEnabled) return

            val effectManager = EffectManagerService.getInstance(project)
            val repoManager = GitRepositoryManager.getInstance(project)
            val repos = repoManager.repositories
            if (repos.isEmpty()) {
                effectManager.removeTargetedEffect(EFFECT_ID)
                return
            }

            val ledIndices = projectSettings.gitLedIndices
            if (ledIndices.isEmpty()) return // no LEDs configured

            val target = EffectTarget.LedSet(ledIndices)

            // Determine highest-priority state across all repos
            var hasConflict = false
            var hasUncommitted = false
            var hasUnpushed = false

            for (repo in repos) {
                when (repo.state) {
                    Repository.State.MERGING,
                    Repository.State.REBASING -> hasConflict = true
                    else -> {}
                }
            }

            // Check for uncommitted changes via change list manager
            val changeListManager = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
            if (changeListManager.allChanges.isNotEmpty()) {
                hasUncommitted = true
            }

            // Check for unpushed: compare local branch with tracked remote
            for (repo in repos) {
                val branch = repo.currentBranch ?: continue
                val trackInfo = repo.branchTrackInfos.find { it.localBranch == branch }
                if (trackInfo != null) {
                    val localHash = repo.branches.getHash(branch)
                    val remoteHash = repo.branches.getHash(trackInfo.remoteBranch)
                    if (localHash != null && remoteHash != null && localHash != remoteHash) {
                        hasUnpushed = true
                    }
                }
            }

            // Select color by priority
            val (color, priority) = when {
                hasConflict -> settings.parseColor(projectSettings.gitConflictColor) to 3
                hasUncommitted -> settings.parseColor(projectSettings.gitUncommittedColor) to 2
                hasUnpushed -> settings.parseColor(projectSettings.gitUnpushedColor) to 2
                else -> settings.parseColor(projectSettings.gitCleanColor) to 1
            }

            effectManager.addTargetedEffect(
                EFFECT_ID,
                if (hasConflict) {
                    PulseEffect(
                        color = color,
                        periodMs = 800,
                        name = "git-conflict",
                        priority = priority,
                        target = target
                    )
                } else {
                    StaticEffect(
                        color = color,
                        name = "git-status",
                        priority = priority,
                        target = target
                    )
                }
            )

            // Apply branch color rules (background color, lowest priority)
            applyBranchColorRules(repos, projectSettings, settings, effectManager)
        } catch (e: Exception) {
            log.warn("Git status update error: ${e.message}")
        }
    }

    /**
     * Evaluates current branch name against configured regex rules.
     * First matching rule wins. Applied as AllLeds background (priority 0).
     */
    private fun applyBranchColorRules(
        repos: List<GitRepository>,
        projectSettings: KgBoardProjectSettings,
        settings: KgBoardSettings,
        effectManager: EffectManagerService
    ) {
        val rules = projectSettings.state.gitBranchColorRules
        if (rules.isEmpty()) {
            effectManager.removeTargetedEffect(BRANCH_COLOR_EFFECT_ID)
            return
        }

        val branchName = repos.firstNotNullOfOrNull { it.currentBranch?.name }
        if (branchName == null) {
            effectManager.removeTargetedEffect(BRANCH_COLOR_EFFECT_ID)
            return
        }

        val matchedRule = rules.firstOrNull { rule ->
            try {
                Regex(rule.pattern).containsMatchIn(branchName)
            } catch (_: Exception) {
                false // invalid regex — skip
            }
        }

        if (matchedRule != null) {
            effectManager.addTargetedEffect(
                BRANCH_COLOR_EFFECT_ID,
                StaticEffect(
                    color = settings.parseColor(matchedRule.color),
                    name = "git-branch-color",
                    priority = 0,
                    target = EffectTarget.AllLeds
                )
            )
        } else {
            effectManager.removeTargetedEffect(BRANCH_COLOR_EFFECT_ID)
        }
    }

    override fun dispose() {
        disposed.set(true)
        pollingTask?.cancel(false)
        pollingTask = null
        scheduler.shutdownNow()
        try {
            val effectManager = EffectManagerService.getInstance(project)
            effectManager.removeTargetedEffect(EFFECT_ID)
            effectManager.removeTargetedEffect(BRANCH_COLOR_EFFECT_ID)
        } catch (_: Exception) {
            // project may already be disposed
        }
    }
}
