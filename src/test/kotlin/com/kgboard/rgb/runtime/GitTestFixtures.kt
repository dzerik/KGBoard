package com.kgboard.rgb.runtime

fun gitTestFacts(branch: String = "main") = GitFacts(
    listOf(
        GitSample(
            RepoRevision("repo", 1, 0),
            RepositoryFacts(
                "repo", "a".repeat(40), branch, HeadState.BRANCH, UpstreamState.PRESENT, false, 0, 0, 0,
                RepositoryOperation.NONE, Freshness.FRESH, 0
            )
        )
    )
)
