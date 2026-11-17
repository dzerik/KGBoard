package com.kgboard.rgb.bridge.git

import com.kgboard.rgb.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.concurrent.TimeUnit

/** All remotes are local bare directories. Network is never involved. */
class GitRepositoryIntegrationTest {
    @TempDir
    lateinit var dir: Path
    private fun git(root: Path, vararg args: String, success: Boolean = true): ByteArray {
        val process =
            ProcessBuilder(listOf("git") + args).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture git must terminate")
            val bytes = process.inputStream.use { it.readBytes() }
            if (success) assertEquals(0, process.exitValue(), args.joinToString(" "))
            return bytes
        } finally {
            process.destroyForcibly()
        }

    }

    private fun identity(root: Path) {
        git(root, "config", "user.name", "Fixture"); git(root, "config", "user.email", "fixture@invalid")
    }

    private fun commit(root: Path, text: String) {
        Files.writeString(root.resolve("file"), text); git(root, "add", "--", "file"); git(root, "commit", "-qm", text)
    }

    private fun status(root: Path) = PorcelainV2Parser.parse(
        git(root, "status", "--porcelain=v2", "--branch", "--untracked-files=normal", "-z"),
        "repo",
        0
    )

    @Test
    fun `real local clones distinguish synced ahead behind diverged conflict and newline rename`() {
        val origin = Files.createDirectory(dir.resolve("origin.git")); git(
            origin,
            "init",
            "--bare",
            "--initial-branch=main"
        )
        val a = Files.createDirectory(dir.resolve("a")); git(a, "init", "--initial-branch=main"); identity(a); commit(
            a,
            "base"
        )
        git(a, "remote", "add", "origin", origin.toString()); git(a, "push", "-u", "origin", "main")
        val b = dir.resolve("b"); git(dir, "clone", origin.toString(), b.toString()); identity(b)
        assertEquals(GitIndicator.SYNCED, GitIndicatorPolicy.classify(status(a)))
        commit(a, "from-a"); assertEquals(GitIndicator.AHEAD, GitIndicatorPolicy.classify(status(a)))
        git(a, "push", "origin", "main"); git(b, "fetch", "origin")
        assertEquals(GitIndicator.BEHIND, GitIndicatorPolicy.classify(status(b)))
        commit(b, "from-b");
        val diverged = status(b)
        assertEquals(1, diverged.ahead); assertEquals(1, diverged.behind)
        assertEquals(GitIndicator.DIVERGED, GitIndicatorPolicy.classify(diverged))
        git(b, "merge", "origin/main", success = false)
        assertEquals(GitIndicator.CONFLICT, GitIndicatorPolicy.classify(status(b)))
        assertEquals(1, status(b).conflicts)
        git(b, "merge", "--abort")
        Files.move(a.resolve("file"), a.resolve("new\nfile name")); git(a, "add", "-A")
        val rename = status(a); assertTrue(rename.dirty); assertEquals(
            GitIndicator.DIRTY,
            GitIndicatorPolicy.classify(rename)
        )
        assertFalse(rename.toString().contains("new\nfile name"))
    }

    @Test
    fun `real unborn detached no-upstream and untracked status is never synced`() {
        val repo = Files.createDirectory(dir.resolve("repo")); git(
            repo,
            "init",
            "--initial-branch=main"
        ); identity(repo)
        assertEquals(GitIndicator.UNBORN, GitIndicatorPolicy.classify(status(repo)))
        commit(repo, "first"); assertEquals(GitIndicator.NO_UPSTREAM, GitIndicatorPolicy.classify(status(repo)))
        git(repo, "checkout", "--detach"); assertEquals(
            GitIndicator.DETACHED,
            GitIndicatorPolicy.classify(status(repo))
        )
        Files.writeString(repo.resolve("untracked name\nline"), "data"); assertEquals(
            GitIndicator.DIRTY,
            GitIndicatorPolicy.classify(status(repo))
        )
    }
}
