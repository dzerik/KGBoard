package com.kgboard.rgb.bridge.git

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import git4idea.config.GitVcsSettings
import git4idea.config.GitExecutableManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.concurrent.*
import com.kgboard.rgb.runtime.*

class GitStatusRunnerPlatformTest {
    @TempDir
    lateinit var dir: Path
    private fun quoted(path: Path) = "'" + path.toString().replace("'", "'\\''") + "'"

    @Test
    fun `SDK runner uses IDE selected git preserves NUL and cancels a live process`() {
        val init =
            ProcessBuilder("git", "init", "--initial-branch=main", dir.toString()).redirectErrorStream(true).start()
        init.inputStream.use { it.readBytes() }; assertTrue(init.waitFor(10, TimeUnit.SECONDS)); assertEquals(
            0,
            init.exitValue()
        )
        val commands = dir.resolve("commands.txt");
        val marker = dir.resolve("started.txt")
        val wrapper = dir.resolve("selected-git")
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(javaClass.simpleName).fixture
        EdtTestUtil.runInEdtAndWait<Throwable> { fixture.setUp() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            Files.writeString(
                wrapper,
                "#!/bin/sh\nprintf '%s\\n' \"\$@\" > ${quoted(commands)}\nexec /usr/bin/git \"\$@\"\n"
            )
            assertTrue(wrapper.toFile().setExecutable(true, true))
            var root: com.intellij.openapi.vfs.VirtualFile? = null
            EdtTestUtil.runInEdtAndWait<Throwable> {
                GitVcsSettings.getInstance(fixture.project).pathToGit = wrapper.toString()
                root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
            }
            GitExecutableManager.getInstance().dropExecutableCache()
            val runner = GitStatusRunner(fixture.project)
            val bytes =
                executor.submit<ByteArray> { runner.status(root!!, GitCancellation()) }.get(10, TimeUnit.SECONDS)
            assertTrue(bytes.contains(0.toByte()))
            val args =
                Files.readAllLines(commands); assertTrue(args.contains("status")); assertTrue(args.contains("-z"))
            assertTrue(args.contains("--porcelain=v2")); assertFalse(args.contains("fetch")); assertFalse(
                args.contains(
                    "push"
                )
            )
            assertEquals(HeadState.UNBORN, PorcelainV2Parser.parse(bytes, "repo", 0).head)
            val valid =
                "# branch.oid ${"a".repeat(40)}\\000# branch.head main\\000# branch.upstream origin/main\\000# branch.ab +0 -0\\000"
            Files.writeString(wrapper, "#!/bin/sh\nprintf '$valid'\nexit 1\n")
            assertThrows(ExecutionException::class.java) {
                executor.submit<ByteArray> {
                    runner.status(
                        root!!,
                        GitCancellation()
                    )
                }.get(5, TimeUnit.SECONDS)
            }
            Files.writeString(wrapper, "#!/bin/sh\nprintf started > ${quoted(marker)}\nexec /usr/bin/sleep 30\n")
            val cancellation = GitCancellation()
            val active = executor.submit<ByteArray> { runner.status(root!!, cancellation) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(marker), "Selected executable must actually start")
            cancellation.cancel("git.timeout")
            assertThrows(ExecutionException::class.java) { active.get(5, TimeUnit.SECONDS) }
            assertEquals("git.timeout", cancellation.reasonKey)
        } finally {
            executor.shutdownNow()
            EdtTestUtil.runInEdtAndWait<Throwable> { fixture.tearDown() }
        }
    }
}
