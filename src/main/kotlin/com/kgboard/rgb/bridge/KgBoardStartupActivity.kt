package com.kgboard.rgb.bridge
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
class KgBoardStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = withContext(Dispatchers.EDT) {
        ProjectRuntimeService.getInstance(project).start()
        ShortcutHighlightService.getInstance(project).start()
    }
}
