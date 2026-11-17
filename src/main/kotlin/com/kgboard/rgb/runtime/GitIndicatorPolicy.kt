package com.kgboard.rgb.runtime

object GitIndicatorPolicy {
    fun classify(facts: RepositoryFacts): GitIndicator = with(facts) {
        when {
            freshness != Freshness.FRESH -> GitIndicator.UNKNOWN
            conflicts > 0 -> GitIndicator.CONFLICT
            dirty -> GitIndicator.DIRTY
            head == HeadState.UNBORN -> GitIndicator.UNBORN
            head == HeadState.DETACHED -> GitIndicator.DETACHED
            head != HeadState.BRANCH -> GitIndicator.UNKNOWN
            upstream == UpstreamState.NONE -> GitIndicator.NO_UPSTREAM
            upstream != UpstreamState.PRESENT || ahead == null || behind == null || ahead < 0 || behind < 0 -> GitIndicator.UNKNOWN
            ahead > 0 && behind > 0 -> GitIndicator.DIVERGED
            ahead > 0 -> GitIndicator.AHEAD
            behind > 0 -> GitIndicator.BEHIND
            else -> GitIndicator.SYNCED
        }
    }

    fun severity(indicator: GitIndicator): Int = when (indicator) {
        GitIndicator.CONFLICT -> 9; GitIndicator.DIRTY -> 8; GitIndicator.DIVERGED -> 7
        GitIndicator.AHEAD -> 6; GitIndicator.BEHIND -> 5; GitIndicator.UNKNOWN -> 4
        GitIndicator.NO_UPSTREAM, GitIndicator.DETACHED, GitIndicator.UNBORN -> 3; GitIndicator.SYNCED -> 1
    }
}
