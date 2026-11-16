package com.kgboard.rgb.runtime

sealed interface RuntimeEvent {
    data class ProjectOpened(val id: ProjectId, val settings: ProjectSettingsSnapshot = ProjectSettingsSnapshot()) :
        RuntimeEvent

    data class ProjectClosed(val id: ProjectId) : RuntimeEvent
    data class ProjectActivated(val id: ProjectId?) : RuntimeEvent
    data class FocusChanged(val focused: Boolean) : RuntimeEvent
    data class SettingsApplied(
        val settings: RuntimeSettings,
        val groups: Map<String, com.kgboard.rgb.target.GroupDefinition>? = null
    ) : RuntimeEvent

    data class ProjectSettingsApplied(val id: ProjectId, val settings: ProjectSettingsSnapshot) : RuntimeEvent
    data class OperationChanged(val project: ProjectId, val operation: OperationState) : RuntimeEvent
    data class LayerChanged(val layer: EffectLayer) : RuntimeEvent
    data class LayerRemoved(val key: EffectKey) : RuntimeEvent
    data class Wake(val generation: Long) : RuntimeEvent
    data class ConnectRequested(val endpoint: Endpoint? = null) : RuntimeEvent
    data object DisconnectRequested : RuntimeEvent
    data class Connected(val session: SessionId, val devices: List<com.kgboard.rgb.client.RgbDeviceInfo>) : RuntimeEvent
    data class InventoryChanged(val session: SessionId) : RuntimeEvent
    data class PreviewCleared(val id: Long) : RuntimeEvent
    data class ConnectFailed(val session: SessionId, val message: String) : RuntimeEvent
    data class TransportFailed(val session: SessionId, val message: String) : RuntimeEvent
    data class EditorSelected(val project: ProjectId, val revision: EditorRevision?, val applicable: Boolean) :
        RuntimeEvent

    data class AnalysisRequested(val project: ProjectId, val revision: EditorRevision) : RuntimeEvent
    data class AnalysisCompleted(val project: ProjectId, val revision: EditorRevision, val result: AnalysisResult) :
        RuntimeEvent

    data class TodosCompleted(val project: ProjectId, val revision: EditorRevision, val hasTodos: Boolean) :
        RuntimeEvent

    data class IndexingChanged(val project: ProjectId, val indexing: Boolean) : RuntimeEvent
    data class GitChanged(val project: ProjectId, val revision: Long, val facts: GitFacts) : RuntimeEvent
    data class ShortcutsChanged(
        val project: ProjectId, val contexts: Set<String>, val indices: Map<String, List<Int>> = emptyMap(),
        val mapping: DeviceMappingRevision? = null, val keyCodes: Map<String, List<Int>> = emptyMap()
    ) : RuntimeEvent

    data class DocumentsSaved(val project: ProjectId, val revision: Long) : RuntimeEvent
    data object LowMemory : RuntimeEvent
    data class PomodoroStart(val project: ProjectId) : RuntimeEvent
    data class PomodoroStop(val project: ProjectId) : RuntimeEvent
    data class PomodoroSkip(val project: ProjectId) : RuntimeEvent
    data class BeginSettings(val id: Long) : RuntimeEvent
    data class DiagnosticConnect(val id: Long, val endpoint: Endpoint) : RuntimeEvent
    data class PreviewStarted(
        val id: Long,
        val device: Int,
        val color: java.awt.Color,
        val indices: List<Int>,
        val durationMs: Long
    ) : RuntimeEvent

    data class SettingsCommitted(val id: Long) : RuntimeEvent
    data class EndSettings(val id: Long) : RuntimeEvent
    data object Shutdown : RuntimeEvent
}

sealed interface RuntimeCommand {
    data class Probe(val session: SessionId) : RuntimeCommand
    data class Connect(val endpoint: Endpoint, val session: SessionId) : RuntimeCommand
    data class Disconnect(val session: SessionId) : RuntimeCommand
}

data class Transition(val state: RuntimeState, val commands: List<RuntimeCommand> = emptyList())
