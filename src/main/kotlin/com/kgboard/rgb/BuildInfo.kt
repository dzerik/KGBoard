package com.kgboard.rgb

import java.util.Properties

/** Generated from the same Gradle property as the plugin descriptor and ZIP. */
object BuildInfo {
    val version: String = Properties().apply {
        BuildInfo::class.java.getResourceAsStream("/kgboard-build.properties")?.use { load(it) }
    }.getProperty("pluginVersion", "unknown")
}
