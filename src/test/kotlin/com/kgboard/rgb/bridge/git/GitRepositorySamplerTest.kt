package com.kgboard.rgb.bridge.git

import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.*

class GitRepositorySamplerTest {
    @Test
    fun `one active process coalesces updates and rejects completion of invalidated head`() {
        val executor = ManualExecutor();
        val clock = ManualClock();
        val scheduler = ManualScheduler(clock, ManualExecutor())
        val samples = mutableListOf<GitSample>();
        var runs = 0;
        var active = 0;
        var max = 0
        lateinit var sampler: GitRepositorySampler
        sampler = GitRepositorySampler(executor, scheduler, { clock.value }, { id, cancel ->
            active++; max = maxOf(max, active); runs++
            if (runs == 1) repeat(100) { sampler.invalidate(id) }
            active--
            RepositoryFacts.unknown(id, clock.value)
                .copy(branch = if (runs == 1) "old" else "new", head = HeadState.BRANCH, freshness = Freshness.FRESH)
        }, samples::add)
        sampler.add("r"); executor.drain()
        assertEquals(2, runs); assertEquals(1, max)
        assertEquals(listOf("new"), samples.filter { it.facts.freshness == Freshness.FRESH }.map { it.facts.branch })
        assertEquals(100, samples.last().revision.headRevision)
        sampler.close()
    }

    @Test
    fun `timeout cancels active process and replaces stale green with unknown`() {
        val clock = ManualClock();
        val scheduler = ManualScheduler(clock, ManualExecutor());
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1);
        val killed = CountDownLatch(1);
        val done = CountDownLatch(1);
        val samples = CopyOnWriteArrayList<GitSample>()
        val sampler = GitRepositorySampler(executor, scheduler, { clock.value }, { id, cancel ->
            cancel.onCancel { killed.countDown() }; started.countDown(); assertTrue(killed.await(5, TimeUnit.SECONDS))
            RepositoryFacts.unknown(id, 0).copy(freshness = Freshness.FRESH)
        }, { samples.add(it); if (it.errorKey == "git.timeout") done.countDown() })
        try {
            sampler.add("r"); assertTrue(
                started.await(
                    5,
                    TimeUnit.SECONDS
                )
            ); repeat(10) { sampler.request("r") }; scheduler.advanceBy(10_000)
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals("git.timeout", samples.last().errorKey)
            assertEquals(Freshness.UNKNOWN, samples.last().facts.freshness)
        } finally {
            sampler.close(); executor.shutdownNow()
        }
    }

    @Test
    fun `close and repository removal cancel startup tokens and prevent reruns or late facts`() {
        val executor = ManualExecutor();
        val clock = ManualClock();
        val scheduler = ManualScheduler(clock, ManualExecutor())
        val samples = mutableListOf<GitSample>();
        var runs = 0
        val sampler = GitRepositorySampler(
            executor,
            scheduler,
            { clock.value },
            { id, _ -> runs++; RepositoryFacts.unknown(id, 0) },
            samples::add
        )
        sampler.add("r"); sampler.invalidate("r"); sampler.remove("r"); sampler.close();
        val count = samples.size
        executor.drain(); assertEquals(0, runs); assertEquals(count, samples.size)
        val token = GitCancellation(); token.cancel();
        var cancelled = false; token.onCancel { cancelled = true }; assertTrue(cancelled)
    }

    @Test
    fun `failed or malformed next query clears previous fresh status`() {
        val executor = ManualExecutor();
        val clock = ManualClock();
        val scheduler = ManualScheduler(clock, ManualExecutor())
        val samples = mutableListOf<GitSample>();
        var runs = 0
        val sampler = GitRepositorySampler(executor, scheduler, { clock.value }, { id, _ ->
            when (++runs) {
                1 -> RepositoryFacts.unknown(id, 0).copy(freshness = Freshness.FRESH)
                2 -> throw IllegalStateException("raw /private/path")
                else -> RepositoryFacts.unknown(id, 0)
            }
        }, samples::add)
        sampler.add("r"); executor.drain(); assertEquals(Freshness.FRESH, samples.last().facts.freshness)
        sampler.request("r"); executor.drain(); assertEquals("git.commandFailed", samples.last().errorKey)
        assertEquals(Freshness.UNKNOWN, samples.last().facts.freshness)
        sampler.request("r"); executor.drain(); assertEquals("git.invalidStatus", samples.last().errorKey)
        assertFalse(samples.toString().contains("/private/path")); sampler.close()
    }

    @Test
    fun `disposal while scheduling cannot leak rejected execution into SDK callback`() {
        val samples = mutableListOf<GitSample>();
        var ran = false
        lateinit var sampler: GitRepositorySampler
        val scheduler = object : RuntimeScheduler {
            override fun scheduleAt(timeMs: Long, action: () -> Unit): Cancellation {
                sampler.close(); throw RejectedExecutionException("closed test scheduler")
            }
        }
        sampler = GitRepositorySampler(
            ManualExecutor(),
            scheduler,
            { 0L },
            { id, _ -> ran = true; RepositoryFacts.unknown(id, 0) },
            samples::add
        )
        assertDoesNotThrow { sampler.add("r") }; assertFalse(ran); assertTrue(samples.isEmpty())
    }

}
