plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

version = rootProject.version
repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}
dependencies {
    compileOnly(project(":"))
    intellijPlatform { bundledPlugin("Git4Idea") }
}
kotlin {
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}
