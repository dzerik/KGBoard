package com.kgboard.rgb.runtime

import java.util.Collections
import com.kgboard.rgb.client.RgbDeviceInfo
import com.kgboard.rgb.effect.RgbEffect
import com.kgboard.rgb.device.*
import com.kgboard.rgb.target.GroupDefinition

@JvmInline
value class ProjectId(val value: String)
@JvmInline
value class OperationId(val value: String)
@JvmInline
value class SessionId(val value: Long)
data class DeviceMappingRevision(
    val token: InventoryToken,
    val bindingId: String,
    val device: Int,
    val fingerprint: String
) {
    val session: SessionId get() = token.session
}

data class Endpoint(val host: String, val port: Int)
data class EditorRevision(val fileUrl: String, val documentStamp: Long, val request: Long)
data class EffectKey(val project: ProjectId?, val source: String)
data class EffectLayer(
    val key: EffectKey, val effect: RgbEffect, val createdAtMs: Long, val expiresAtMs: Long? = null,
    val ruleId: String? = null, val factDurationMs: Long? = null, val colorOverride: java.awt.Color? = null,
    val hintCodes: List<Int>? = null, val mapping: DeviceMappingRevision? = null
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val session: SessionId, val endpoint: Endpoint) : ConnectionState
    data class Ready(val session: SessionId, val devices: List<RgbDeviceInfo>, val inventoryRevision: Long = 1) :
        ConnectionState

    data class Backoff(val session: SessionId, val retryAtMs: Long, val error: String) : ConnectionState
}

enum class OperationKind { RUN, DEBUG, BUILD, TEST }
enum class OperationPhase { STARTING, RUNNING, PAUSED, TERMINATED }
enum class OperationOutcome { SUCCESS, FAILURE, CANCELLED, NOT_EXECUTED }

data class OperationState(
    val id: OperationId,
    val kind: OperationKind,
    val phase: OperationPhase,
    val outcome: OperationOutcome? = null,
    val parent: OperationId? = null,
    val revision: Long = 1,
    val changedAtMs: Long = 0
)

data class ProjectState(
    val settings: ProjectSettingsSnapshot = ProjectSettingsSnapshot(),
    val operations: Map<OperationId, OperationState> = emptyMap(),
    val editor: EditorState = EditorState(),
    val indexing: Boolean = false,
    val git: GitFacts = GitFacts(),
    val gitRevision: Long = -1,
    val shortcutContexts: Set<String> = emptySet(),
    val shortcutMapping: DeviceMappingRevision? = null,
    val shortcutIndices: Map<String, List<Int>> = emptyMap(),
    val shortcutKeyCodes: Map<String, List<Int>> = emptyMap(),
    val savedAtMs: Long? = null,
    val saveRevision: Long = -1,
    val pomodoro: PomodoroState = PomodoroState()
)

data class RuntimeState(
    val settings: RuntimeSettings = RuntimeSettings(),
    val connectionEpoch: String = java.util.UUID.randomUUID().toString(),
    val groups: Map<String, GroupDefinition> = emptyMap(),
    val projects: Map<ProjectId, ProjectState> = emptyMap(),
    val activeProject: ProjectId? = null,
    val focused: Boolean = false,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val connectionError: String? = null,
    val connectionGeneration: Long = 0,
    val connectionWanted: Boolean = false,
    val manuallyDisconnected: Boolean = false,
    val endpoint: Endpoint = Endpoint(settings.host, settings.port),
    val retryDelayMs: Long = 1000,
    val probeAtMs: Long? = null,
    val layers: Map<EffectKey, EffectLayer> = emptyMap(),
    val closedProjects: Set<ProjectId> = emptySet(),
    val settingsSession: SettingsSessionState? = null,
    val preview: PreviewState? = null,
    val nowMs: Long = 0,
    val lowMemoryAtMs: Long? = null,
    val shuttingDown: Boolean = false
)

internal fun <T> frozenList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
internal fun <T> frozenSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
