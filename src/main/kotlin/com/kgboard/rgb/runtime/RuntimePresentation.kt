package com.kgboard.rgb.runtime

fun connectionStatus(state: RuntimeState): String = when(val connection = state.connection) {
    ConnectionState.Disconnected -> state.connectionError?.let { "Disconnected: $it" } ?: "Disconnected"
    is ConnectionState.Connecting -> "Connecting to ${connection.endpoint.host}:${connection.endpoint.port}…"
    is ConnectionState.Backoff -> "Retrying: ${connection.error}"
    is ConnectionState.Ready -> if (connection.devices.isEmpty()) "Connected — no devices" else
        if (connection.devices.getOrNull(state.settings.deviceIndex) == null) "Connected — selected device unavailable"
        else "Connected (${connection.devices.size} devices)"
}
fun analysisStatus(project: ProjectState?): String = when {
    project == null -> "No active project"
    project.indexing -> "Indexing"
    project.editor.analysis == AnalysisResult.NotApplicable -> "Not applicable"
    project.editor.analysis == AnalysisResult.Unknown -> "Analysis unknown"
    else -> (project.editor.analysis as AnalysisResult.Ready).let {
        when { it.hasErrors -> "Errors"; it.hasWarnings -> "Warnings"; else -> "Analysis clean" }
    }
}
