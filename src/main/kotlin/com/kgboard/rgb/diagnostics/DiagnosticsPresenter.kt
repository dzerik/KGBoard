package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.scenario.ScenarioCatalog

/** Content equality keeps animation-only or duplicate snapshots off the EDT. */
data class DiagnosticsView(
    val connectionLabel: String,
    val deviceRows: List<String>,
    val ruleRows: List<String>,
    val analysisRows: List<String>,
    val errorRows: List<String>,
    val capabilityRows: List<String>,
    val gitRows: List<String>
) {
    companion object {
        fun from(state: RuntimeState): DiagnosticsView {
            val shortConnection = message(
                when (state.connection) {
                    is ConnectionState.Ready -> "diagnostics.status.ready"
                    is ConnectionState.Connecting -> "diagnostics.status.connecting"
                    is ConnectionState.Backoff -> "diagnostics.status.retrying"
                    ConnectionState.Disconnected -> "diagnostics.status.disconnected"
                }
            )
            val connection =
                if (state.settings.enabled) shortConnection else message("diagnostics.status.disabled") + " · " + shortConnection
            val devices = state.lastDecision?.devices.orEmpty()
            val deviceRows = devices.mapIndexed { ordinal, device ->
                val name = device.deviceIndex?.let { state.inventory()?.devices?.getOrNull(it)?.name }
                    ?: message("diagnostics.deviceNumber", ordinal + 1)
                val frame = device.frame
                val delivery = frame?.let { state.diagnostics.delivery[it.deliveryKey()] }
                val status = when {
                    delivery?.error != null -> message(DiagnosticsReasons.safe(delivery.error))
                    delivery?.pending != null -> message("diagnostics.delivery.pending", delivery.pending.value)
                    delivery?.lastSent != null -> message(
                        "diagnostics.delivery.sent", delivery.lastSent.value,
                        safeDifference(state.nowMs, delivery.sentAtMs ?: state.nowMs).coerceAtLeast(0)
                    )

                    else -> message("diagnostics.delivery.none")
                }
                val reason = device.idleReasonKey?.let { message(DiagnosticsReasons.safe(it)) }
                    ?: message(if (device.rules.any { it.visible.isNotEmpty() }) "diagnostics.signalsActive" else "diagnostics.noSignals")
                val explanation = if (device.rules.any { it.visible.isNotEmpty() }) reason
                else message("diagnostics.noSignals") + " · " + reason
                "$name · $status · $explanation"
            }.ifEmpty { listOf(message("device.unconfirmed")) }
            val rules = devices.flatMapIndexed { index, device ->
                device.rules.map { rule ->
                    val label =
                        if (ScenarioCatalog.byId(rule.ruleId) != null) message("scenario.${rule.ruleId}") else message("diagnostics.customSignal")
                    val owner = rule.owner?.let { state.projects[it]?.displayName ?: it.value.take(8) }
                        ?: message("diagnostics.application")
                    val reasons = rule.reasons.joinToString(", ") { message(DiagnosticsReasons.key(it)) }
                    message(
                        "diagnostics.ruleRow",
                        index + 1,
                        label,
                        owner,
                        rule.visible.size,
                        rule.requested.size,
                        reasons
                    )
                }
            }.ifEmpty { listOf(message("diagnostics.noSignals")) }
            val analysis = state.projects.map { (id, project) ->
                val key = when {
                    project.indexing -> "analysis.indexing"
                    project.editor.analysis == AnalysisResult.Unknown -> "analysis.unknown"
                    project.editor.analysis == AnalysisResult.NotApplicable -> "analysis.notApplicable"
                    else -> (project.editor.analysis as AnalysisResult.Ready).let {
                        when {
                            it.hasErrors -> "diagnostics.analysis.errors"; it.hasWarnings -> "diagnostics.analysis.warnings"; else -> "diagnostics.analysis.clean"
                        }
                    }
                }
                "${project.displayName ?: id.value.take(8)}: ${message(key)}"
            }.ifEmpty { listOf(message("diagnostics.noProject")) }
            val errors = state.diagnostics.history.asReversed().map {
                message(
                    "diagnostics.historyRow",
                    message(DiagnosticsReasons.safe(it.reasonKey)),
                    it.count,
                    it.lastSeenMs
                )
            }.toMutableList()
            (state.connection as? ConnectionState.Backoff)?.let {
                errors.add(0, message("diagnostics.retry", safeDifference(it.retryAtMs, state.nowMs).coerceAtLeast(0)))
            }
            val capabilities = state.projects.flatMap { (id, project) ->
                project.capabilities.values.map { capability ->
                    val label = when (capability.id) {
                        "git" -> message("capability.git")
                        "tests.sm" -> message("capability.tests")
                        "build.roots" -> message("capability.builds")
                        "build.automake" -> message("capability.automake")
                        "debug" -> message("capability.debug")
                        else -> message("capability.custom")
                    }
                    message(
                        "capability.row", project.displayName ?: id.value.take(8), label,
                        message("capability.status.${capability.status.name.lowercase(java.util.Locale.ROOT)}"),
                        capability.reasonKey?.let { message(DiagnosticsReasons.safe(it)) } ?: "")
                }
            }
            val git = state.projects.flatMap { (id, project) ->
                project.git.repositories.mapIndexed { index, sample ->
                    val facts = sample.facts
                    val indicator = GitIndicatorPolicy.classify(facts).name.lowercase(java.util.Locale.ROOT)
                    message(
                        "git.repositoryRow", project.displayName ?: id.value.take(8), index + 1,
                        facts.branch ?: message("git.noBranch"), message("git.state.$indicator"),
                        facts.ahead?.toString() ?: "?", facts.behind?.toString() ?: "?",
                        message("git.operation.${facts.operation.name.lowercase(java.util.Locale.ROOT)}"),
                        sample.errorKey?.let { message(DiagnosticsReasons.safe(it)) } ?: ""
                    )
                }
            }.ifEmpty { listOf(message("git.noRepositories")) }
            return DiagnosticsView(
                connection, frozenList(deviceRows), frozenList(rules), frozenList(analysis),
                frozenList(errors), frozenList(capabilities), frozenList(git)
            )
        }
    }
}

/** Latest-view coalescing. The supplied scheduler must execute publications on the EDT. */
class DiagnosticsPresenter(
    private val subscribe: ((RuntimeState) -> Unit) -> Cancellation,
    private val schedule: (Long, () -> Unit) -> Cancellation,
    private val publish: (DiagnosticsView) -> Unit
) : AutoCloseable {
    private val lock = Any()
    private var active = false
    private var generation = 0L
    private var subscription: Cancellation? = null
    private var pending: Cancellation? = null
    private var queued = false
    private var latest: DiagnosticsView? = null
    private var published: DiagnosticsView? = null

    fun start() {
        val current = synchronized(lock) { if (active) return; active = true; ++generation }
        val handle = subscribe { state -> update(state, current) }
        synchronized(lock) { if (active && generation == current) subscription = handle else handle.cancel() }
    }

    private fun update(state: RuntimeState, current: Long): Unit = synchronized(lock) {
        if (!active || generation != current) return
        latest = DiagnosticsView.from(state)
        if (queued || latest == published) return
        queued = true
        pending = schedule(250) {
            synchronized(lock) {
                if (!active || generation != current) return@synchronized
                queued = false; pending = null
                val view = latest
                if (view != null && view != published) {
                    published = view; publish(view)
                }
            }
        }
    }

    override fun close(): Unit = synchronized(lock) {
        if (!active) return
        active = false; ++generation
        subscription?.cancel(); subscription = null
        pending?.cancel(); pending = null; queued = false
        latest = null; published = null
    }
}
