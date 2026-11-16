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
    }
    testImplementation(platform("org.junit:junit-bom:${providers.gradleProperty("junitVersion").get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.mockk:mockk:1.13.13")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
