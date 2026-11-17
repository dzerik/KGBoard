package com.kgboard.rgb.bridge.integration

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.kgboard.rgb.bridge.ProjectRuntimeBinding
import com.kgboard.rgb.runtime.*

data class IntegrationContext(
    val project: Project, val lease: ProviderLease,
    val binding: ProjectRuntimeBinding, val emit: (RuntimeEvent) -> Unit
)

interface IntegrationProvider {
    val capabilityId: String
    fun create(context: IntegrationContext): Disposable

    companion object {
        val EP_NAME = ExtensionPointName.create<IntegrationProvider>("com.kgboard.rgb.integrationProvider")
    }
}
