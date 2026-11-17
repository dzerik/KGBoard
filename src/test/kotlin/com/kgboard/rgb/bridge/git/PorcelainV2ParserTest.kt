package com.kgboard.rgb.bridge.git

import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PorcelainV2ParserTest {
    private val oid = "a".repeat(40)
    private fun headers(
        head: String = "main",
        oid: String = this.oid,
        upstream: String = "# branch.upstream origin/main\u0000# branch.ab +0 -0\u0000"
    ) =
        "# branch.oid $oid\u0000# branch.head $head\u0000$upstream"

    private fun parse(text: String) = PorcelainV2Parser.parse(text.toByteArray(), "repo", 42)

    @Test
    fun `directional counts and rename with newline preserve dirty state without filenames`() {
        val data = headers(upstream = "# branch.upstream origin/main\u0000# branch.ab +2 -3\u0000") +
            "2 R. N... 100644 100644 100644 $oid $oid R100 new\nname\u0000old name\u0000"
        val f = parse(data)
        assertEquals(2, f.ahead); assertEquals(3, f.behind); assertTrue(f.dirty)
        assertEquals(GitIndicator.DIRTY, GitIndicatorPolicy.classify(f))
        assertEquals(GitIndicator.DIVERGED, GitIndicatorPolicy.classify(f.copy(dirty = false)))
        assertFalse(f.toString().contains("new\nname")); assertFalse(f.toString().contains("old name"))
        assertEquals(42, f.sampledAtMs)
    }

    @Test
    fun `clean requires fresh branch with upstream and known zero counts`() {
        val clean = parse(headers())
        assertEquals(GitIndicator.SYNCED, GitIndicatorPolicy.classify(clean))
        assertEquals(GitIndicator.AHEAD, GitIndicatorPolicy.classify(clean.copy(ahead = 2)))
        assertEquals(GitIndicator.BEHIND, GitIndicatorPolicy.classify(clean.copy(behind = 3)))
        assertEquals(GitIndicator.UNKNOWN, GitIndicatorPolicy.classify(clean.copy(freshness = Freshness.UNKNOWN)))
        assertEquals(GitIndicator.UNKNOWN, GitIndicatorPolicy.classify(clean.copy(ahead = null)))
        assertEquals(GitIndicator.CONFLICT, GitIndicatorPolicy.classify(clean.copy(conflicts = 1, dirty = true)))
    }

    @Test
    fun `no upstream detached unborn malformed and errors never become synced`() {
        val inputs = listOf(
            headers(upstream = ""),
            headers(head = "(detached)", upstream = ""),
            headers(oid = "(initial)", upstream = ""),
            "",
            headers().dropLast(1),
            headers() + "x something\u0000",
            headers(upstream = "# branch.upstream origin/main\u0000# branch.ab +x -0\u0000"),
            headers() + "2 R. N... 100644 100644 100644 $oid $oid R100 new\u0000",
            headers() + "1 M. N... bad 100644 100644 $oid $oid path\u0000"
        )
        for (input in inputs) assertNotEquals(GitIndicator.SYNCED, GitIndicatorPolicy.classify(parse(input)), input)
        assertEquals(GitIndicator.NO_UPSTREAM, GitIndicatorPolicy.classify(parse(headers(upstream = ""))))
        assertEquals(
            GitIndicator.DETACHED,
            GitIndicatorPolicy.classify(parse(headers(head = "(detached)", upstream = "")))
        )
        assertEquals(GitIndicator.UNBORN, GitIndicatorPolicy.classify(parse(headers(oid = "(initial)", upstream = ""))))
    }

    @Test
    fun `merge operation alone is not an unmerged conflict and binary filenames remain valid`() {
        val raw = (headers() + "? ").toByteArray() + byteArrayOf(0xff.toByte(), 0)
        val f = PorcelainV2Parser.parse(raw, "repo", 0)
        assertTrue(f.dirty); assertEquals(0, f.conflicts)
        val clean = parse(headers()).copy(operation = RepositoryOperation.MERGE)
        assertNotEquals(GitIndicator.CONFLICT, GitIndicatorPolicy.classify(clean))
        val conflicted =
            parse(headers() + "u UU N... 100644 100644 100644 100644 $oid $oid $oid name\nwith space\u0000")
        assertEquals(1, conflicted.conflicts)
    }
}
