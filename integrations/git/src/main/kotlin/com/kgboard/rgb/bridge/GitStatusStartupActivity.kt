package com.kgboard.rgb.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starts Git status monitoring on project open.
 * Only loaded when Git4Idea plugin is available (via kgboard-git.xml).
 */
class GitStatusStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = withContext(Dispatchers.EDT) {
        GitStatusListener.getInstance(project).start()
    }
}
