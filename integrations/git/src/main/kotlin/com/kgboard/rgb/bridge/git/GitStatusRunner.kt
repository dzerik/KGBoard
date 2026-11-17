package com.kgboard.rgb.bridge.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.GitBinaryHandler
import git4idea.commands.GitCommand
import java.util.concurrent.CancellationException

/** Uses the IDE-selected executable and preserves NUL bytes. No command can fetch or push. */
class GitStatusRunner(private val project: Project) {
    fun status(root: VirtualFile, cancellation: GitCancellation): ByteArray {
        val app = ApplicationManager.getApplication()
        check(!app.isDispatchThread && !app.isReadAccessAllowed)
        if (project.isDisposed || !root.isValid || cancellation.cancelled) throw CancellationException()
        val slot = GitProcessSlot(cancellation)
        val handler = object : GitBinaryHandler(project, root, GitCommand.STATUS) {
            override fun startProcess(): Process {
                if (cancellation.cancelled) throw CancellationException()
                return super.startProcess().also(slot::attach)
            }
        }
        handler.setSilent(true); handler.setStdoutSuppressed(true); handler.setStderrSuppressed(true)
        handler.addParameters("--porcelain=v2", "--branch", "--untracked-files=normal", "-z")
        val bytes = handler.run()
        if (cancellation.cancelled) throw CancellationException("Local Git status cancelled")
        check(slot.exitCode() == 0) { "Local Git status failed" }
        return bytes
    }
}
