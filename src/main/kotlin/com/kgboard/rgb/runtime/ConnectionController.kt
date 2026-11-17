package com.kgboard.rgb.runtime

/** Pure connection intent, generation fencing and bounded exponential retry. */
class ConnectionController {
    fun reduce(state: RuntimeState, event: RuntimeEvent, nowMs: Long): Transition {
        fun connect(endpoint: Endpoint): Transition {
            val session = SessionId(state.connectionGeneration + 1)
            return Transition(
                state.copy(
                    connection = ConnectionState.Connecting(session, endpoint),
                    endpoint = endpoint, connectionGeneration = session.value, connectionWanted = true,
                    manuallyDisconnected = false, connectionError = null, probeAtMs = null
                ), listOf(RuntimeCommand.Connect(endpoint, session))
            )
        }

        fun failed(session: SessionId, message: String): Transition {
            val current = when (val connection = state.connection) {
                is ConnectionState.Connecting -> connection.session
                is ConnectionState.Ready -> connection.session
                else -> null
            }
            if (session != current) return Transition(state)
            val delay = state.retryDelayMs
            val retry = state.connectionWanted && state.settings.autoReconnect
            return Transition(
                state.copy(
                    connection = if (retry) ConnectionState.Backoff(session, expiryAt(nowMs, delay), message)
                    else ConnectionState.Disconnected,
                    connectionError = message,
                    probeAtMs = null,
                    retryDelayMs = (delay * 2).coerceAtMost(30000)
                ),
                listOf(RuntimeCommand.Disconnect(session))
            )
        }
        return when (event) {
            is RuntimeEvent.ConnectRequested -> {
                val endpoint = event.endpoint ?: Endpoint(state.settings.host, state.settings.port)
                if (state.connectionWanted && state.endpoint == endpoint &&
                    (state.connection is ConnectionState.Ready || state.connection is ConnectionState.Connecting)
                ) Transition(state)
                else connect(endpoint)
            }

            RuntimeEvent.DisconnectRequested -> {
                val session = SessionId(state.connectionGeneration + 1)
                Transition(
                    state.copy(
                        connection = ConnectionState.Disconnected, connectionGeneration = session.value,
                        connectionWanted = false, manuallyDisconnected = true, connectionError = null, probeAtMs = null
                    ), listOf(RuntimeCommand.Disconnect(session))
                )
            }

            is RuntimeEvent.Connected -> {
                if ((state.connection as? ConnectionState.Connecting)?.session != event.session) Transition(state)
                else Transition(
                    state.copy(
                        connection = ConnectionState.Ready(event.session, frozenList(event.devices.map { device ->
                            device.copy(
                                ledNames = frozenList(device.ledNames),
                                displayNames = frozenList(device.displayNames),
                                zones = frozenList(device.zones.map { it.copy(matrixMap = it.matrixMap?.let(::frozenList)) }),
                                colors = frozenList(device.colors)
                            )
                        })), settings = state.settings.copy(
                            untrustedSerials = frozenSet(
                                state.settings.untrustedSerials +
                                    com.kgboard.rgb.device.DeviceResolver.duplicates(event.devices)
                            )
                        ), retryDelayMs = 1000, probeAtMs = expiryAt(nowMs, 5000)
                    )
                )
            }

            RuntimeEvent.ResumeDetected -> {
                val fenced = state.copy(
                    connection = ConnectionState.Disconnected, probeAtMs = null,
                    connectionError = null, connectionEpoch = java.util.UUID.randomUUID().toString()
                )
                if (state.connectionWanted && !state.manuallyDisconnected) {
                    val session = SessionId(state.connectionGeneration + 1)
                    Transition(
                        fenced.copy(
                            connection = ConnectionState.Connecting(session, state.endpoint),
                            connectionGeneration = session.value
                        ), listOf(RuntimeCommand.Connect(state.endpoint, session))
                    )
                } else {
                    val session = SessionId(state.connectionGeneration + 1)
                    Transition(
                        fenced.copy(connectionGeneration = session.value),
                        listOf(RuntimeCommand.Disconnect(session))
                    )
                }
            }

            is RuntimeEvent.InventoryChanged -> if ((state.connection as? ConnectionState.Ready)?.session == event.session)
                connect(state.endpoint) else Transition(state)

            is RuntimeEvent.ConnectFailed -> failed(event.session, event.message)
            is RuntimeEvent.TransportFailed -> failed(event.session, event.message)
            is RuntimeEvent.Wake -> when {
                state.connection is ConnectionState.Backoff && state.connectionWanted && state.connection.retryAtMs <= nowMs -> connect(
                    state.endpoint
                )

                state.connection is ConnectionState.Ready && state.probeAtMs != null && state.probeAtMs <= nowMs ->
                    Transition(
                        state.copy(probeAtMs = expiryAt(nowMs, 5000)),
                        listOf(RuntimeCommand.Probe(state.connection.session))
                    )

                else -> Transition(state)
            }

            is RuntimeEvent.SettingsApplied -> {
                val endpoint = Endpoint(event.settings.host, event.settings.port)
                when {
                    state.connectionWanted && endpoint != state.endpoint -> connect(endpoint)
                    !event.settings.autoReconnect && state.connection is ConnectionState.Backoff ->
                        Transition(state.copy(connection = ConnectionState.Disconnected, probeAtMs = null))

                    event.settings.enabled && event.settings.autoConnect && !state.manuallyDisconnected && !state.connectionWanted &&
                        (!state.settings.enabled || !state.settings.autoConnect) -> connect(endpoint)

                    state.connectionWanted && state.connection is ConnectionState.Disconnected && event.settings.autoReconnect && !state.settings.autoReconnect -> connect(
                        endpoint
                    )

                    else -> Transition(if (state.connectionWanted) state else state.copy(endpoint = endpoint))
                }
            }

            else -> Transition(state)
        }
    }
}
