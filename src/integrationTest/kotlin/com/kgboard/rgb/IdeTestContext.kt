package com.kgboard.rgb

import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Launches only an explicitly pinned local distribution, always with isolated Starter paths. */
object IdeTestContext {
    fun create(
        testName: String, endpoint: com.kgboard.rgb.runtime.Endpoint, scale: Float = 1f,
        templateName: String = "jps", lightingEnabled: Boolean = false
    ): IDETestContext {
        val ide = Path.of(requireNotNull(System.getProperty("kgboard.ui.idePath")))
        val info = Files.readString(ide.resolve("product-info.json"))
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").findAll(info).single().groupValues[1]
        val expected = requireNotNull(System.getProperty("kgboard.ui.expectedBuild"))
        require(field("buildNumber") == expected) { "Local IDE build differs from the pinned UI test build" }
        val product = IdeInfo.fromProductCode(field("productCode")).copy(
            buildNumber = expected, getInstaller = { ExistingIdeInstaller(ide) })
        val project = Files.createTempDirectory("kgboard-ui-project-")
        require(templateName in setOf("jps", "gradle", "maven"))
        val template = Path.of("src/integrationTest/resources/projects", templateName)
        Files.walk(template).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { source ->
                val target =
                    project.resolve(template.relativize(source)); Files.createDirectories(target.parent); Files.copy(
                source,
                target
            )
            }
        }
        val context = Starter.newContext(testName, TestCase(product, LocalProjectInfo(project)))
        val options = context.paths.configDir.resolve("options")
        Files.createDirectories(options)
        Files.writeString(
            options.resolve("kgboard.xml"), """
            <application><component name="KgBoardSettings">
              <option name="host" value="${endpoint.host}" />
              <option name="port" value="${endpoint.port}" />
              <option name="autoConnect" value="false" />
              <option name="enabled" value="$lightingEnabled" />
            </component></application>
        """.trimIndent()
        )
        context.applyVMOptionsPatch {
            withXmx(2048)
            addSystemProperty("awt.toolkit.name", "XToolkit")
            addSystemProperty("ide.ui.scale", scale.toString())
            addSystemProperty("idea.trust.all.projects", true)
            addSystemProperty("ide.show.tips.on.startup.default.value", false)
        }
        val plugin = Path.of(requireNotNull(System.getProperty("kgboard.ui.pluginZip")))
        PluginConfigurator(context).installPluginFromPath(plugin)
        val jbr = Properties().apply { Files.newBufferedReader(ide.resolve("jbr/release")).use(::load) }
        val metadata = Properties().apply {
            setProperty("product", field("productCode"))
            setProperty("build", expected)
            setProperty("jbr", jbr.getProperty("JAVA_RUNTIME_VERSION").trim('"'))
            setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
            setProperty("plugin", plugin.fileName.toString())
            setProperty(
                "sha256", MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(plugin))
                    .joinToString("") { "%02x".format(it) })
            setProperty("ui.scale", scale.toString())
        }
        val evidence = Path.of("build/reports/kgboard-ui/$testName.properties")
        Files.createDirectories(evidence.parent)
        Files.newBufferedWriter(evidence)
            .use { metadata.store(it, "Actual launch inputs; UI assertions verify scale and geometry") }
        return context
    }
}
