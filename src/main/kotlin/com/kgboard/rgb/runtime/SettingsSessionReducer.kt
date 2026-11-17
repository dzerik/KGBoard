package com.kgboard.rgb.runtime

import com.kgboard.rgb.device.*
import com.kgboard.rgb.effect.*
import com.kgboard.rgb.scenario.*
import com.kgboard.rgb.settings.*
import com.kgboard.rgb.target.*
import java.awt.Color

/** Pure scoped leases; closing one Configurable never commits or cancels another's work. */
internal object SettingsSessionReducer {
    private val controller = ConnectionController()
    fun reduce(state: RuntimeState, event: RuntimeEvent, now: Long): Transition = when (event) {
        is RuntimeEvent.BeginSettings -> begin(state, event)
        is RuntimeEvent.EndSettings -> end(state, event.id, now)
        is RuntimeEvent.SettingsCommitted -> commit(state, event.id)
        is RuntimeEvent.DiagnosticConnect -> diagnostic(state, event, now)
        is RuntimeEvent.PreviewStarted -> preview(state, event.request, now)
        is RuntimeEvent.PreviewCleared -> Transition(
            state.copy(
                preview = state.preview.takeUnless { it?.owner == event.id },
                previewFeedback = frozenMap(state.previewFeedback - event.id)
            )
        )

        is RuntimeEvent.ConnectRequested -> {
            val endpoint = event.endpoint ?: Endpoint(state.settings.host, state.settings.port)
            val group = state.settingsSession?.copy(
                endpoint = endpoint, wanted = true,
                manuallyDisconnected = false, connectionOwner = null
            )
            controller.reduce(state.copy(preview = null, settingsSession = group), event, now)
        }

        RuntimeEvent.DisconnectRequested -> controller.reduce(
            state.copy(
                preview = null,
                settingsSession = state.settingsSession?.copy(
                    wanted = false, manuallyDisconnected = true,
                    connectionOwner = null
                )
            ), event, now
        )

        is RuntimeEvent.ConnectFailed, is RuntimeEvent.TransportFailed, is RuntimeEvent.InventoryChanged ->
            controller.reduce(state, event, now).let { result ->
                if (result.state.connection == state.connection) result else result.copy(
                    state = result.state.copy(
                        preview = null
                    )
                )
            }

        is RuntimeEvent.SettingsApplied -> controller.reduce(state.copy(preview = null), event, now)
        is RuntimeEvent.ProjectClosed -> {
            var result = Transition(state)
            val owners = state.settingsSession?.owners.orEmpty().filterValues { it.project == event.id }.keys
            owners.forEach { owner ->
                val next = end(result.state, owner, now)
                result = Transition(next.state, result.commands + next.commands)
            }
            result
        }

        else -> controller.reduce(state, event, now)
    }

    private fun begin(state: RuntimeState, event: RuntimeEvent.BeginSettings): Transition {
        val group = state.settingsSession
        if (group?.owners?.containsKey(event.id) == true) return Transition(state)
        val owner = SettingsOwner(event.scope, event.project)
        val session = group?.copy(owners = frozenMap(group.owners + (event.id to owner)))
            ?: SettingsSessionState(
                frozenMap(mapOf(event.id to owner)),
                state.endpoint,
                state.connectionWanted,
                state.manuallyDisconnected
            )
        return Transition(state.copy(settingsSession = session, preview = null))
    }

    private fun restore(state: RuntimeState, baseline: SettingsSessionState, now: Long): Transition {
        if (state.endpoint == baseline.endpoint && state.connectionWanted == baseline.wanted)
            return Transition(state.copy(manuallyDisconnected = baseline.manuallyDisconnected))
        val event =
            if (baseline.wanted) RuntimeEvent.ConnectRequested(baseline.endpoint) else RuntimeEvent.DisconnectRequested
        return controller.reduce(state, event, now).let {
            it.copy(
                state = it.state.copy(
                    endpoint = baseline.endpoint,
                    manuallyDisconnected = baseline.manuallyDisconnected
                )
            )
        }
    }

    private fun end(state: RuntimeState, id: Long, now: Long): Transition {
        val group = state.settingsSession ?: return Transition(state)
        if (id !in group.owners) return Transition(state)
        val remaining = frozenMap(group.owners - id)
        val cleared = state.copy(
            preview = state.preview.takeUnless { it?.owner == id },
            previewFeedback = frozenMap(state.previewFeedback - id),
            settingsSession = if (remaining.isEmpty()) null else group.copy(
                owners = remaining,
                connectionOwner = group.connectionOwner.takeUnless { it == id })
        )
        return if (remaining.isEmpty() || group.connectionOwner == id) restore(cleared, group, now) else Transition(
            cleared
        )
    }

    private fun commit(state: RuntimeState, id: Long): Transition {
        val group = state.settingsSession ?: return Transition(state)
        val owner = group.owners[id] ?: return Transition(state)
        val updated = if (owner.scope == SettingsScope.APP) group.copy(
            endpoint = state.endpoint,
            wanted = state.connectionWanted, manuallyDisconnected = state.manuallyDisconnected, connectionOwner = null
        ) else group
        return Transition(
            state.copy(
                settingsSession = updated, preview = state.preview.takeUnless { it?.owner == id },
                previewFeedback = frozenMap(state.previewFeedback - id)
            )
        )
    }

    private fun diagnostic(state: RuntimeState, event: RuntimeEvent.DiagnosticConnect, now: Long): Transition {
        val group = state.settingsSession ?: return Transition(state)
        val owner = group.owners[event.id] ?: return Transition(state)
        if (owner.scope == SettingsScope.PROJECT && owner.project !in state.projects) return Transition(state)
        if (event.endpoint.host.isBlank() || event.endpoint.port !in 1..65535) return Transition(state)
        return controller.reduce(
            state.copy(preview = null, settingsSession = group.copy(connectionOwner = event.id)),
            RuntimeEvent.ConnectRequested(event.endpoint), now
        )
    }

    private fun preview(state: RuntimeState, request: PreviewRequest, now: Long): Transition {
        val availability = PreviewAvailability.evaluate(state, request)
        val feedback = frozenMap(state.previewFeedback + (request.owner to availability))
        if (!availability.allowed) return Transition(
            state.copy(
                previewFeedback =
                    if (state.settingsSession?.owners?.containsKey(request.owner) == true) feedback else state.previewFeedback
            )
        )
        val inventory = state.inventory()!!
        val device = inventory.devices[request.choice.index]
        val content = request.content
        val target = when (content) {
            PreviewContent.DeviceTest -> TargetSpec.All
            is PreviewContent.ColorSample -> content.target
            is PreviewContent.Scenario -> content.appearance.target
        }
        val indices = frozenList(TargetResolver.resolve(target, device, request.groups ?: state.groups).indices)
        val effectTarget = EffectTarget.LedSet(indices)
        val effect = when (content) {
            PreviewContent.DeviceTest -> StaticEffect(Color(77, 77, 77), "device-test", target = effectTarget)
            is PreviewContent.ColorSample -> StaticEffect(content.color, "color-sample", target = effectTarget)
            is PreviewContent.Scenario -> ScenarioEffects.create(
                content.ruleId,
                content.appearance.style,
                effectTarget,
                .5f
            )
        }
        val duration = when (content) {
            is PreviewContent.Scenario -> content.appearance.style.durationMs.takeIf { it > 0 }?.coerceAtMost(10000)
                ?: 2000

            else -> 2000
        }
        val binding =
            DeviceResolver.bind("preview:${request.owner}", request.choice, inventory, state.settings.untrustedSerials)
        val session = requireNotNull(state.settingsSession)
        val owner = session.owners.getValue(request.owner)
        val group = if (session.connectionOwner != null) session.copy(connectionOwner = request.owner) else session
        return Transition(
            state.copy(
                settingsSession = group, previewFeedback = feedback,
                preview = PreviewState(
                    request.owner,
                    request.choice.index,
                    indices,
                    now,
                    expiryAt(now, duration),
                    binding,
                    inventory.token,
                    content,
                    effect,
                    owner.project,
                    RuntimeDeadline.after(duration, state.timeAt(now), request.owner)
                )
            )
        )
    }
}
