package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiagnosticsPresenterTest {
    private class Queue {
        data class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)

        var now = 0L
        val tasks = mutableListOf<Task>()
        fun schedule(delay: Long, action: () -> Unit): Cancellation {
            val task = Task(now + delay, action); tasks.add(task)
            return Cancellation { task.cancelled = true }
        }

        fun advanceBy(ms: Long) {
            val until = now + ms
            while (true) {
                val task = tasks.filter { it.at <= until }.minByOrNull { it.at } ?: break
                tasks.remove(task); now = task.at
                if (!task.cancelled) task.run()
            }
            now = until
        }
    }

    @Test
    fun `idle device explicitly says no active signals while retaining analysis reason`() {
        RuntimeHarness().use { h ->
            h.project()
            val row = DiagnosticsView.from(h.snapshotForTest()).deviceRows.single()
            assertTrue(row.contains("No active signals"), row)
            assertTrue(row.contains("Analysis does not apply"), row)
        }
    }

    @Test
    fun `one thousand updates publish at most four times per second`() {
        val queue = Queue();
        var callback: ((RuntimeState) -> Unit)? = null
        val views = mutableListOf<DiagnosticsView>()
        val presenter = DiagnosticsPresenter(
            { listener -> callback = listener; Cancellation { callback = null } },
            queue::schedule,
            views::add
        )
        presenter.start()
        repeat(1000) { n ->
            callback!!(
                RuntimeState(
                    connection = ConnectionState.Connecting(
                        SessionId(n.toLong()),
                        Endpoint("host", n)
                    )
                )
            )
            queue.advanceBy(1)
        }
        assertTrue(views.size in 1..4, "published ${views.size}")
        val last = RuntimeState(connection = ConnectionState.Ready(SessionId(1), emptyList()))
        val connecting = views.last().connectionLabel
        callback!!(last); queue.advanceBy(250)
        assertNotEquals(connecting, views.last().connectionLabel)
        val count = views.size
        repeat(1000) { callback!!(last) }
        queue.advanceBy(1000)
        assertEquals(count, views.size)
        presenter.close()
    }

    @Test
    fun `twenty open close cycles release subscriptions and ignore queued callbacks`() {
        val queue = Queue();
        var subscriptions = 0;
        var publications = 0
        val stale = mutableListOf<(RuntimeState) -> Unit>()
        repeat(20) {
            val presenter = DiagnosticsPresenter({ listener ->
                subscriptions++; stale.add(listener); listener(RuntimeState())
                Cancellation { subscriptions-- }
            }, queue::schedule, { publications++ })
            presenter.start(); presenter.start()
            assertEquals(1, subscriptions)
            presenter.close(); presenter.close()
            assertEquals(0, subscriptions)
            stale.last()(RuntimeState(focused = true))
            // Simulate an EDT callback already dequeued before cancellation.
            queue.tasks.toList().forEach { it.run() }
            queue.advanceBy(1000)
            assertEquals(0, publications)
        }
    }
}
