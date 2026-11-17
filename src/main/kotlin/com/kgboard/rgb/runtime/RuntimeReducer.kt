package com.kgboard.rgb.runtime

import com.kgboard.rgb.effect.*
import com.kgboard.rgb.diagnostics.*

/** Pure state transitions. Only LightingRuntime owns the resulting state. */
class RuntimeReducer {
    fun reduceDelivery(state: RuntimeState, receipt: DeliveryReceipt): RuntimeState {
        if (state.shuttingDown || receipt.frame.token != state.inventory()?.token) return state
        val key = receipt.frame.deliveryKey()
        val previous = state.diagnostics.delivery[key] ?: return state
        if (receipt.frame.revision.value > (previous.offered?.value ?: return state)) return state
        val next = previous.report(receipt)
        return if (next == previous) state else state.copy(
            diagnostics = state.diagnostics.copy(
                delivery = frozenMap(state.diagnostics.delivery + (key to next))
            )
        )
    }

    fun reduce(original: RuntimeState, event: RuntimeEvent, nowMs: Long): Transition =
        reduce(original, event, TimeSample(nowMs, nowMs))

    fun reduce(previous: RuntimeState, event: RuntimeEvent, now: TimeSample): Transition {
        val nowMs = now.monotonicMs
        val original = previous.copy(lastTime = now)
        if (original.shuttingDown) return Transition(original)
        val connection = settingsTransition(original, event, nowMs)
        val state = connection.state.copy(
            preview = connection.state.preview?.takeIf {
                (it.deadline?.remaining(now) ?: safeDifference(
                    it.deadlineMs,
                    nowMs
                )) > 0
            },
            projects = frozenMap(original.projects.mapValues { (_, project) ->
                project.copy(
                    pomodoro = when {
                        !original.settings.enabled || !original.settings.pomodoroEnabled -> project.pomodoro.stop()
                        (event is RuntimeEvent.Wake || event == RuntimeEvent.ResumeDetected) &&
                            (project.pomodoro.deadline?.remaining(now) ?: project.pomodoro.deadlineMs?.let {
                                safeDifference(
                                    it,
                                    nowMs
                                )
                            })?.let { it <= 0 } == true -> project.pomodoro.next(
                            original.settings,
                            now
                        )

                        else -> project.pomodoro
                    }, operations = frozenMap(project.operations.filterValues {
                        (OperationPolicy().deadlineAt(it, original.settings, now) ?: Long.MAX_VALUE) > nowMs
                    })
                )
            }),
            lowMemoryAtMs = original.lowMemoryAtMs.takeUnless { original.lowMemoryDeadline?.remaining(now) == 0L },
            lowMemoryDeadline = original.lowMemoryDeadline?.takeUnless { it.remaining(now) == 0L },
            layers = frozenMap(original.layers.filterValues {
                it.expiryAt(now)?.let { expiry -> expiry > nowMs } != false
            })
        )
        val next = when (event) {
            is RuntimeEvent.ProjectOpened -> if (event.id in state.closedProjects || event.id in state.projects) state
            else state.copy(
                projects = frozenMap(
                    state.projects + (event.id to ProjectState(
                        settings = event.settings,
                        displayName = event.displayName
                    ))
                )
            )

            is RuntimeEvent.ProjectClosed -> if (event.id !in state.projects) state else state.copy(
                projects = frozenMap(state.projects - event.id),
                layers = frozenMap(state.layers.filterKeys { it.project != event.id }),
                activeProject = state.activeProject.takeUnless { it == event.id },
                closedProjects = frozenSet(state.closedProjects + event.id)
            )

            is RuntimeEvent.ProjectActivated -> if (event.id == null || event.id in state.projects)
                state.copy(activeProject = event.id) else state

            is RuntimeEvent.FocusChanged -> state.copy(focused = event.focused)
            is RuntimeEvent.SettingsApplied -> state.copy(
                settings = event.settings.copy(
                    untrustedSerials = frozenSet(
                        state.settings.untrustedSerials + event.settings.untrustedSerials
                    )
                ),
                groups = event.groups?.let { groups ->
                    frozenMap(groups.mapValues { (_, group) ->
                        group.copy(
                            target = com.kgboard.rgb.target.TargetRecord.from(group.target).toSpec()!!
                        )
                    })
                } ?: state.groups,
                projects = frozenMap(state.projects.mapValues { (_, p) ->
                    p.copy(
                        pomodoro = if (event.settings.enabled && event.settings.pomodoroEnabled) p.pomodoro else p.pomodoro.stop(),
                        savedAtMs = if (event.settings.enabled) p.savedAtMs else null,
                        saveDeadline = if (event.settings.enabled) p.saveDeadline else null,
                        operations = if (event.settings.enabled) p.operations else frozenMap(p.operations.filterValues { it.phase != OperationPhase.TERMINATED })
                    )
                }),
                lowMemoryAtMs = if (event.settings.enabled) state.lowMemoryAtMs else null,
                lowMemoryDeadline = if (event.settings.enabled) state.lowMemoryDeadline else null,
                layers = if (event.settings.enabled) state.layers else frozenMap(state.layers.filterValues { it.expiresAtMs == null })
            )

            is RuntimeEvent.ProjectSettingsApplied -> updateProject(
                state,
                event.id
            ) { it.copy(settings = event.settings) }

            is RuntimeEvent.OperationChanged -> updateProject(state, event.project) { project ->
                val previous = project.operations[event.operation.id]
                when {
                    previous == null && event.operation.phase == OperationPhase.TERMINATED -> project
                    previous != null && event.operation.revision <= previous.revision -> project
                    previous?.phase == OperationPhase.TERMINATED -> project
                    event.operation.phase == OperationPhase.TERMINATED && !state.settings.enabled ->
                        project.copy(operations = frozenMap(project.operations - event.operation.id))

                    else -> project.copy(
                        operations = frozenMap(
                            project.operations + (event.operation.id to event.operation.copy(
                                changedAtMs = nowMs,
                                deadline = if (event.operation.phase == OperationPhase.TERMINATED)
                                    RuntimeDeadline.after(
                                        state.settings.rules[OperationPolicy().ruleId(
                                            event.operation,
                                            state.settings
                                        )]?.style?.durationMs
                                            ?: state.settings.effectDurationMs, now, event.operation.revision
                                    ) else null
                            ))
                        )
                    )
                }
            }

            is RuntimeEvent.EditorSelected -> updateProject(state, event.project) { p ->
                if (event.revision == p.editor.revision && event.applicable == p.editor.applicable) p
                else p.copy(
                    editor = EditorState(
                        event.revision, event.applicable,
                        if (event.applicable) AnalysisResult.Unknown else AnalysisResult.NotApplicable
                    )
                )
            }

            is RuntimeEvent.AnalysisRequested -> updateProject(state, event.project) { p ->
                if (p.editor.revision != event.revision || !p.editor.applicable) p
                else p.copy(editor = p.editor.copy(analysis = AnalysisResult.Unknown))
            }

            is RuntimeEvent.AnalysisCompleted -> updateProject(state, event.project) { p ->
                if (p.editor.revision != event.revision || p.indexing || !p.editor.applicable) p
                else p.copy(editor = p.editor.copy(analysis = event.result))
            }

            is RuntimeEvent.TodosCompleted -> updateProject(state, event.project) { p ->
                if (p.editor.revision != event.revision || p.indexing || !p.editor.applicable) p
                else p.copy(editor = p.editor.copy(hasTodos = event.hasTodos))
            }

            is RuntimeEvent.IndexingChanged -> updateProject(state, event.project) { p ->
                p.copy(
                    indexing = event.indexing,
                    editor = p.editor.copy(
                        analysis = if (p.editor.applicable) AnalysisResult.Unknown else AnalysisResult.NotApplicable,
                        hasTodos = null
                    )
                )
            }

            is RuntimeEvent.GitChanged -> updateProject(state, event.project) { p ->
                if (event.revision <= p.gitRevision) p else p.copy(git = event.facts, gitRevision = event.revision)
            }

            is RuntimeEvent.ShortcutsChanged -> updateProject(state, event.project) { p ->
                val current = state.primaryMapping()
                if (event.mapping != null && event.mapping != current || event.indices.isNotEmpty() && event.mapping == null) p
                else p.copy(
                    shortcutMapping = event.mapping, shortcutContexts = frozenSet(event.contexts),
                    shortcutIndices = frozenMap(event.indices.mapValues { frozenList(it.value) }),
                    shortcutKeyCodes = frozenMap(event.keyCodes.mapValues { frozenList(it.value) })
                )
            }

            is RuntimeEvent.DocumentsSaved -> updateProject(state, event.project) { p ->
                if (event.revision <= p.saveRevision) p
                else p.copy(
                    saveRevision = event.revision, savedAtMs = if (state.settings.enabled) nowMs else null,
                    saveDeadline = if (state.settings.enabled) RuntimeDeadline.after(
                        expiryAt(state.settings.rules["fileSave"]?.style?.durationMs ?: 300, 50), now, event.revision
                    ) else null
                )
            }

            is RuntimeEvent.PomodoroStart -> updateProject(state, event.project) { p ->
                if (!state.settings.enabled || !state.settings.pomodoroEnabled || p.pomodoro.phase != PomodoroPhase.IDLE) p
                else p.copy(pomodoro = PomodoroState(generation = p.pomodoro.generation).next(state.settings, now))
            }

            is RuntimeEvent.PomodoroStop -> updateProject(
                state,
                event.project
            ) { p -> p.copy(pomodoro = p.pomodoro.stop()) }

            is RuntimeEvent.PomodoroSkip -> updateProject(state, event.project) { p ->
                if (p.pomodoro.phase == PomodoroPhase.IDLE) p
                else p.copy(pomodoro = p.pomodoro.next(state.settings, now))
            }

            RuntimeEvent.LowMemory -> state.copy(
                lowMemoryAtMs = if (state.settings.enabled) nowMs else null,
                lowMemoryDeadline = if (state.settings.enabled) RuntimeDeadline.after(
                    expiryAt(state.settings.rules["lowMemory"]?.style?.durationMs ?: 2000, 100), now, 0
                ) else null
            )

            RuntimeEvent.ResumeDetected -> state.copy(
                preview = null, previewFeedback = emptyMap(),
                lowMemoryAtMs = null, lowMemoryDeadline = null,
                layers = frozenMap(state.layers.filterValues { it.expiresAtMs == null && it.deadline == null }),
                projects = frozenMap(state.projects.mapValues { (_, p) ->
                    p.copy(
                        savedAtMs = null, saveDeadline = null,
                        operations = frozenMap(p.operations.filterValues { it.phase != OperationPhase.TERMINATED }),
                        shortcutIndices = emptyMap(), shortcutMapping = null
                    )
                })
            )

            RuntimeEvent.Shutdown -> state.copy(
                shuttingDown = true, preview = null, settingsSession = null,
                previewFeedback = emptyMap()
            )

            is RuntimeEvent.LayerChanged -> if ((event.layer.key.project != null && event.layer.key.project !in state.projects) ||
                (event.layer.expiresAtMs != null && event.layer.expiresAtMs <= nowMs)
            ) state
            else state.copy(
                layers = frozenMap(
                    state.layers + (event.layer.key to event.layer.copy(
                        effect = frozenEffect(event.layer.effect),
                        deadline = event.layer.deadline ?: event.layer.expiresAtMs?.let {
                            RuntimeDeadline.after(safeDifference(it, nowMs).coerceAtLeast(0), now, 0)
                        }
                    ))
                )
            )

            is RuntimeEvent.LayerRemoved -> state.copy(layers = frozenMap(state.layers - event.key))
            else -> state
        }
        val mapped = if (original.primaryMapping() == next.primaryMapping()) next else next.copy(
            projects = frozenMap(
                next.projects.mapValues { (_, project) ->
                    project.copy(
                        shortcutIndices = emptyMap(),
                        shortcutMapping = null
                    )
                })
        )
        return Transition(mapped, connection.commands)
    }

    private fun updateProject(
        state: RuntimeState,
        id: ProjectId,
        change: (ProjectState) -> ProjectState
    ): RuntimeState {
        val before = state.projects[id] ?: return state
        val after = change(before)
        return if (before == after) state else state.copy(projects = frozenMap(state.projects + (id to after)))
    }
}

private fun frozenEffect(effect: RgbEffect): RgbEffect {
    val target =
        (effect.target as? EffectTarget.LedSet)?.let { EffectTarget.LedSet(frozenList(it.ledIndices)) } ?: effect.target
    return when (effect) {
        is StaticEffect -> StaticEffect(effect.color, effect.name, effect.priority, target)
        is PulseEffect -> PulseEffect(
            effect.color,
            effect.minBrightness,
            effect.periodMs,
            effect.name,
            effect.priority,
            target
        )

        is FlashEffect -> FlashEffect(effect.color, effect.durationMs, effect.name, effect.priority, target)
        is ProgressEffect -> ProgressEffect(
            effect.color,
            effect.backgroundColor,
            effect.progress,
            effect.name,
            effect.priority,
            target
        )

        is GradientEffect -> GradientEffect(
            effect.startColor,
            effect.endColor,
            effect.durationMs,
            effect.name,
            effect.priority,
            target
        )

        is RainbowEffect -> RainbowEffect(effect.speedMs, effect.name, effect.priority, target)
        is WaveEffect -> WaveEffect(
            effect.color,
            effect.speedMs,
            effect.minBrightness,
            effect.name,
            effect.priority,
            target
        )
    }
}
