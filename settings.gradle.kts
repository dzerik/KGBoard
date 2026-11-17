pluginManagement {
    val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
    val intellijPlatformVersion = providers.gradleProperty("intellijPlatformVersion").get()

    plugins {
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
        id("org.jetbrains.intellij.platform") version intellijPlatformVersion
        id("org.jetbrains.intellij.platform.module") version intellijPlatformVersion
    }

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "KGBoard"

include(":integrations:git")
