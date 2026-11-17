package com.kgboard.rgb.runtime

enum class HeadState { BRANCH, DETACHED, UNBORN, UNKNOWN }
enum class UpstreamState { PRESENT, NONE, UNKNOWN }
enum class RepositoryOperation { NONE, MERGE, REBASE, OTHER }
enum class Freshness { FRESH, UNKNOWN }
enum class GitIndicator { CONFLICT, DIRTY, DIVERGED, AHEAD, BEHIND, SYNCED, NO_UPSTREAM, DETACHED, UNBORN, UNKNOWN }

data class RepositoryFacts(
    val id: String, val headOid: String?, val branch: String?, val head: HeadState,
    val upstream: UpstreamState, val dirty: Boolean, val conflicts: Int,
    val ahead: Int?, val behind: Int?, val operation: RepositoryOperation,
    val freshness: Freshness, val sampledAtMs: Long
) {
    companion object {
        fun unknown(id: String, nowMs: Long) = RepositoryFacts(
            id, null, null, HeadState.UNKNOWN,
            UpstreamState.UNKNOWN, false, 0, null, null, RepositoryOperation.NONE, Freshness.UNKNOWN, nowMs
        )
    }
}

data class RepoRevision(val repositoryId: String, val request: Long, val headRevision: Long)
data class GitSample(val revision: RepoRevision, val facts: RepositoryFacts, val errorKey: String? = null)

/** Detached batch snapshot; no caller-owned list can mutate queued/runtime facts. */
class GitFacts(repositories: List<GitSample> = emptyList()) {
    val repositories: List<GitSample> = frozenList(repositories)
    val selected: GitSample? =
        this.repositories.maxByOrNull { GitIndicatorPolicy.severity(GitIndicatorPolicy.classify(it.facts)) }
    val branch: String? get() = selected?.facts?.takeIf { it.freshness == Freshness.FRESH }?.branch
    fun reconciled(previous: GitFacts): GitFacts {
        val old = previous.repositories.associateBy { it.revision.repositoryId }
        val unique = repositories.groupBy { it.revision.repositoryId }.filterValues { it.size == 1 }
        return GitFacts(unique.mapNotNull { (id, list) ->
            val incoming = list.single(); if (incoming.facts.id != id) return@mapNotNull null
            val prior = old[id]
            if (prior != null && incoming.revision.olderThan(prior.revision)) prior else incoming
        })
    }

    override fun equals(other: Any?) = other is GitFacts && repositories == other.repositories
    override fun hashCode() = repositories.hashCode()
}

fun RepoRevision.olderThan(other: RepoRevision): Boolean =
    headRevision < other.headRevision || headRevision == other.headRevision && request < other.request
