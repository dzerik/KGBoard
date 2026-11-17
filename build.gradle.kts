plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    intellijPlatform {
        val localPlatformPath = providers.gradleProperty("localPlatformPath")
        if (localPlatformPath.isPresent) {
            local(localPlatformPath.get())
        } else {
            create(providers.gradleProperty("platformType").get(), providers.gradleProperty("platformVersion").get())
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.platform.testRunner")
        bundledPlugin("Git4Idea")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Starter,
            configurationName = "integrationTestImplementation")
    }
    testImplementation(platform("org.junit:junit-bom:${providers.gradleProperty("junitVersion").get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"("org.kodein.di:kodein-di-jvm:7.20.2")
    "integrationTestImplementation"("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:1.11.0-intellij-1")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.kgboard.rgb"
        name = "KGBoard"
        version = providers.gradleProperty("pluginVersion").get()
        description = """
            RGB keyboard integration for JetBrains IDEs.
            Visualize build status, test results, debug state, and more through your RGB keyboard.
            Uses OpenRGB SDK for cross-vendor hardware support.
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            // Keep compatibility bounded to the SDK branch used to compile and verify the plugin.
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        vendor {
            name = "KGBoard"
        }
    }

    pluginVerification {
        ides {
            current()
        }
    }
}

kotlin {
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

tasks {
    wrapper {
        gradleVersion = "9.7.0"
        distributionSha256Sum = "84fbba45c7f4c64abc77460e1c00f541e9f960e3c7ed2538f1ede19eacd873ae"
    }

    buildSearchableOptions {
        enabled = false
    }

    test {
        useJUnitPlatform()
    }
}

val integrationTest = intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        systemProperty("java.awt.headless", "false")
        dependsOn(tasks.buildPlugin, tasks.testClasses)
        systemProperty("kgboard.ui.idePath", providers.gradleProperty("localPlatformPath")
            .orElse(providers.provider { platformPath.toString() }).get())
        systemProperty("kgboard.ui.expectedBuild", "263.7307.200")
        systemProperty("kgboard.ui.pluginZip", tasks.buildPlugin.get().archiveFile.get().asFile.absolutePath)
        maxParallelForks = 1
    }
}
