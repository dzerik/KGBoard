package com.kgboard.rgb.probe

import com.intellij.build.BuildViewManager
import com.intellij.build.events.*
import com.intellij.compiler.CompilerWorkspaceConfiguration
import com.intellij.compiler.server.BuildManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.compiler.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.task.*
import com.kgboard.rgb.runtime.*
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.*
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.settings.*
import org.jetbrains.idea.maven.execution.*
import org.jetbrains.idea.maven.project.MavenWorkspaceSettingsComponent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Test-only plugin: native SDK observations, isolated fixture setup, no lighting commands. */
class BuildProbe(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val rows = CopyOnWriteArrayList<String>()
    private val ids = mutableMapOf<Any, Int>()
    private val runtimeRows = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var subscription: Cancellation? = null

    @Volatile
    private var secondary: Project? = null

    @Volatile
    private var secondaryError: String? = null

    @Synchronized
    private fun identity(id: Any?) = if (id == null) 0 else ids.getOrPut(id) { ids.size + 1 }
    fun prepare(jdkHome: String) {
        val compilerHome = jdkHome.ifBlank { System.getProperty("java.home") }
        require(
            java.nio.file.Files.isExecutable(
                java.nio.file.Path.of(
                    compilerHome,
                    "bin",
                    "javac"
                )
            )
        ) { "Fixture requires a full JDK" }
        WriteAction.run<RuntimeException> {
            val table = ProjectJdkTable.getInstance()
            val jdk = table.findJdk("KGBoard fixture JDK") ?: JavaSdk.getInstance()
                .createJdk("KGBoard fixture JDK", compilerHome, false).also { table.addJdk(it) }
            ProjectRootManager.getInstance(project).projectSdk = jdk
        }
        fun observe(source: String, observable: com.intellij.build.BuildProgressObservable) {
            observable.addListener({ buildId, event ->
                if (event is StartBuildEvent || event is FinishBuildEvent) {
                    val phase = if (event is StartBuildEvent) "start" else "finish"
                    val result = (event as? FinishBuildEvent)?.result
                    val errors = (result as? FailureResult)?.failures.orEmpty().mapNotNull { it.error?.javaClass?.name }
                    rows.add(
                        "$source|$phase|${identity(event.id)}|parent=${identity(event.parentId)}|build=${
                            identity(
                                buildId
                            )
                        }|${result?.javaClass?.simpleName ?: "none"}|errors=$errors"
                    )
                }
            }, this)
        }
        observe("BVM", project.getService(BuildViewManager::class.java))
        observe(
            "RUNVIEW",
            project.getService(com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfigurationViewManager::class.java)
        )
        project.messageBus.connect(this).subscribe(ProjectTaskListener.TOPIC, object : ProjectTaskListener {
            override fun started(context: ProjectTaskContext) {
                rows.add("TASK|start|${identity(context.sessionId ?: context)}|auto=${context.isAutoRun}")
            }

            override fun finished(result: ProjectTaskManager.Result) {
                rows.add("TASK|finish|${identity(result.context.sessionId ?: result.context)}|auto=${result.context.isAutoRun}|aborted=${result.isAborted}|errors=${result.hasErrors()}")
            }
        })
        project.messageBus.connect(this).subscribe(BuildManagerListener.TOPIC, object : BuildManagerListener {
            override fun buildStarted(p: Project, session: java.util.UUID, automatic: Boolean) {
                if (p === project) rows.add("MANAGER|start|${identity(session)}|auto=$automatic")
            }

            override fun buildFinished(p: Project, session: java.util.UUID, automatic: Boolean) {
                if (p === project) rows.add("MANAGER|finish|${identity(session)}|auto=$automatic")
            }
        })
        project.messageBus.connect(this)
            .subscribe(CompilerTopics.COMPILATION_STATUS, object : CompilationStatusListener {
                override fun compilationFinished(
                    aborted: Boolean,
                    errors: Int,
                    warnings: Int,
                    context: CompileContext
                ) {
                    rows.add("COMPILER|manual|aborted=$aborted|errors=$errors")
                }

                override fun automakeCompilationFinished(errors: Int, warnings: Int, context: CompileContext) {
                    rows.add("COMPILER|auto|errors=$errors")
                }
            })
        ExternalSystemProgressNotificationManager.getInstance()
            .addNotificationListener(object : ExternalSystemTaskNotificationListener {
                override fun onStart(id: ExternalSystemTaskId) {
                    if (id.findProject() === project) rows.add("EXTERNAL|start|${identity(id)}|${id.projectSystemId.id}|${id.type}")
                }

                override fun onEnd(id: ExternalSystemTaskId) {
                    if (id.findProject() === project) rows.add("EXTERNAL|finish|${identity(id)}|${id.projectSystemId.id}|${id.type}")
                }
            }, this)
        subscription = LightingRuntimeService.getInstance().subscribe { state ->
            state.projects.values.flatMap { it.capabilities.values }.forEach { capability ->
                val row = "CAPABILITY|${capability.id}|${capability.status}|${capability.reasonKey}"
                if (runtimeRows.add(row)) rows.add(row)
            }
            state.projects.values.flatMap { it.operations.values }.filter { it.kind == OperationKind.BUILD }
                .forEach { operation ->
                    val row =
                        "RUNTIME|${operation.id.value}|${operation.phase}|${operation.outcome}|${operation.revision}"
                    if (runtimeRows.add(row)) rows.add(row)
                }
        }
        rows.add("CONFIG|sdk=${ProjectRootManager.getInstance(project).projectSdk != null}")
    }

    fun getObservations(): List<String> = rows.toList()
    fun enableAutoMake() {
        CompilerWorkspaceConfiguration.getInstance(project).MAKE_PROJECT_ON_SAVE = true
        AdvancedSettings.setBoolean("compiler.automake.allow.when.app.running", true)
        rows.add(
            "CONFIG|auto=true|whileRunning=${
                CompilerWorkspaceConfiguration.getInstance(project).allowAutoMakeWhileRunningApplication()
            }"
        )
    }

    fun editSource() = WriteIntentReadAction.run {
        val file = LocalFileSystem.getInstance().findFileByPath(project.basePath + "/src/Main.java")
            ?: error("Fixture source missing")
        val document = FileDocumentManager.getInstance().getDocument(file) ?: error("Fixture document missing")
        WriteAction.run<RuntimeException> {
            document.setText(
                document.text.replace(
                    "KGBoard UI verification",
                    "KGBoard UI verification auto"
                )
            )
        }
    }

    fun importGradle(gradleHome: String) {
        val base = Path.of(project.basePath!!)
        val userHome = Files.createTempDirectory("kgboard-gradle-home-")
        GradleSettings.getInstance(project).serviceDirectoryPath = userHome.toString()
        Files.copy(
            base.resolve("fixture/build.gradle.txt"),
            base.resolve("build.gradle"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        Files.copy(
            base.resolve("fixture/settings.gradle.txt"),
            base.resolve("settings.gradle"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        Files.createDirectories(base.resolve("src/main/java"))
        Files.copy(
            base.resolve("fixture/Main.java.txt"),
            base.resolve("src/main/java/Main.java"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        Files.writeString(
            base.resolve("gradle.properties"),
            "org.gradle.java.installations.auto-download=false\norg.gradle.daemon.idletimeout=10000\norg.gradle.jvmargs=-Xmx512m\n"
        )
        val settings = GradleProjectSettings().apply {
            externalProjectPath = base.toString(); distributionType = DistributionType.LOCAL; this.gradleHome =
            gradleHome
            gradleJvm = "KGBoard fixture JDK"; delegatedBuild = true
        }
        GradleSettings.getInstance(project).linkProject(settings)
        WriteIntentReadAction.run {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base)?.refresh(false, true)
        }
        val spec = ImportSpecBuilder(project, ProjectSystemId("GRADLE")).use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
            .withArguments("--offline").withImportProjectData(true)
            .withCallback(java.util.function.Consumer<Boolean> { success -> rows.add("CONTROL|gradle-import|$success") })
        ExternalSystemUtil.refreshProject(base.toString(), spec)
    }

    fun runGradleTask(task: String) {
        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalProjectPath = project.basePath; externalSystemIdString = "GRADLE"; taskNames =
            listOf(task); scriptParameters = "--offline"
        }
        ExternalSystemUtil.runTask(settings, "Run", project, ProjectSystemId("GRADLE"), object : TaskCallback {
            override fun onSuccess() {
                rows.add("CONTROL|gradle-task|success")
            }

            override fun onFailure() {
                rows.add("CONTROL|gradle-task|failure")
            }
        }, ProgressExecutionMode.IN_BACKGROUND_ASYNC)
    }

    fun editGradleSource() = WriteIntentReadAction.run {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.basePath + "/src/main/java/Main.java")
            ?: error("Gradle fixture source missing")
        val document = FileDocumentManager.getInstance().getDocument(file) ?: error("Gradle fixture document missing")
        WriteAction.run<RuntimeException> {
            document.setText(
                document.text.replace(
                    "Gradle verification",
                    "Gradle verification edited"
                )
            )
        }
    }

    fun runMavenValidate() {
        val base = Path.of(project.basePath!!)
        val settingsFile = base.resolve("fixture-settings.xml")
        Files.writeString(settingsFile, "<settings><offline>true</offline></settings>")
        val general = MavenWorkspaceSettingsComponent.getInstance(project).settings.generalSettings
        general.setWorkOffline(true); general.setUserSettingsFile(settingsFile.toString()); general.setLocalRepository(
            Files.createTempDirectory("kgboard-maven-repo-").toString()
        )
        general.setMavenHomeType(org.jetbrains.idea.maven.project.BundledMaven3)
        Files.copy(
            base.resolve("fixture/pom.xml.txt"),
            base.resolve("fixture-pom.xml"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        val runner = MavenRunner.getInstance(project)
        runner.settings.setJreName("KGBoard fixture JDK")
        val parameters =
            MavenRunnerParameters(true, base.toString(), "fixture-pom.xml", listOf("validate"), emptyList<String>())
        runner.run(parameters, runner.settings, Runnable { rows.add("CONTROL|maven|finished") })
    }

    fun openTextFixture() = WriteIntentReadAction.run {
        val path = Path.of(project.basePath!!, "native-editor.txt")
        Files.writeString(path, "Native editor fixture\n")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("Text fixture missing")
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun openImageFixture() = WriteIntentReadAction.run {
        val path = Path.of(project.basePath!!, "native-editor.png")
        javax.imageio.ImageIO.write(
            java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
            "png",
            path.toFile()
        )
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("Image fixture missing")
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun closeEditors() = WriteIntentReadAction.run {
        val manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        manager.openFiles.forEach(manager::closeFile)
    }

    fun deleteTextFixture() = WriteIntentReadAction.run {
        closeEditors()
        openTextFixture()
        val file = LocalFileSystem.getInstance().findFileByPath(project.basePath + "/native-editor.txt")
            ?: error("Text fixture missing")
        WriteAction.run<RuntimeException> { file.delete(this) }
    }

    fun getEditorStatus(): String = com.intellij.openapi.application.ReadAction.compute<String, RuntimeException> {
        val manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val view = when (manager.selectedEditor) {
            null -> "none"; is com.intellij.openapi.fileEditor.TextEditor -> "text"; else -> "nontext"
        }
        val id = com.kgboard.rgb.bridge.ProjectRuntimeService.getInstance(project).binding.id
        val editor = LightingRuntimeService.getInstance().snapshot().projects[id]?.editor ?: return@compute "missing"
        "$view|${editor.applicable}|${editor.revision != null}|${editor.analysis.javaClass.simpleName}"
    }

    fun getFocusStatus(): String {
        val state = LightingRuntimeService.getInstance().snapshot()
        val id = com.kgboard.rgb.bridge.ProjectRuntimeService.getInstance(project).binding.id
        return "${state.focused}|${state.activeProject == id}"
    }

    fun openSecondaryProject() {
        val path = Files.createTempDirectory("kgboard-secondary-project-")
        Files.createDirectories(path.resolve(".idea"))
        Files.writeString(
            path.resolve(".idea/modules.xml"),
            """<project version="4"><component name="ProjectModuleManager"><modules/></component></project>"""
        )
        scope.launch {
            try {
                secondary = com.intellij.ide.impl.ProjectUtil.openOrImportAsync(
                    path,
                    com.intellij.ide.impl.OpenProjectTask.build().withForceOpenInNewFrame(true)
                        .withProjectName("KGBoard secondary fixture")
                )
                if (secondary == null) secondaryError = "notOpened"
            } catch (error: Exception) {
                secondaryError = error.javaClass.simpleName; throw error
            }
        }
    }

    fun arrangeProjectWindows() {
        val windows = com.intellij.openapi.wm.WindowManager.getInstance()
        val first = windows.getFrame(project) ?: error("Primary frame missing")
        val second = secondary?.let(windows::getFrame) ?: error("Secondary frame missing")
        check(first !== second) { "Force-new-frame must create two actual windows" }
        first.setBounds(50, 250, 1000, 700); second.setBounds(1300, 250, 1000, 700)
    }

    fun getProjectWindowStatus(): String {
        secondaryError?.let { return "error|$it" }
        val state = LightingRuntimeService.getInstance().snapshot()
        if (secondary == null && state.projects.size > 1) return "openingOrClosing"
        secondary?.takeUnless { it.isDisposed }?.let {
            if (com.intellij.openapi.wm.WindowManager.getInstance().getFrame(it) == null) return "opening"
        }
        val first = com.kgboard.rgb.bridge.ProjectRuntimeService.getInstance(project).binding.id
        val second = secondary?.takeUnless { it.isDisposed }
            ?.let { com.kgboard.rgb.bridge.ProjectRuntimeService.getInstance(it).binding.id }
        val owner = when (state.activeProject) {
            first -> "primary"; second -> if (second == null) "none" else "secondary"; else -> "other"
        }
        return "${state.projects.size}|${state.focused}|$owner"
    }

    fun closeSecondaryProject() = WriteIntentReadAction.run {
        secondary?.takeUnless { it.isDisposed }?.let {
            check(com.intellij.openapi.project.ProjectManager.getInstance().closeAndDispose(it))
        }
        secondary = null
    }

    fun getVisibleColorMetrics(): String {
        check(java.awt.EventQueue.isDispatchThread())
        fun descendants(component: java.awt.Component): List<java.awt.Component> =
            listOf(component) + (component as? java.awt.Container)?.components.orEmpty().flatMap(::descendants)

        val field = java.awt.Window.getWindows().flatMap(::descendants)
            .filterIsInstance<javax.swing.JTextField>().single { it.name == "color.value" && it.isShowing }
        val available = field.width - field.insets.left - field.insets.right
        val needed = field.getFontMetrics(field.font).stringWidth(field.text) + com.intellij.util.ui.JBUI.scale(2)
        return "$available|$needed"
    }

    override fun dispose() {
        subscription?.cancel(); subscription = null; ids.clear()
    }
}
