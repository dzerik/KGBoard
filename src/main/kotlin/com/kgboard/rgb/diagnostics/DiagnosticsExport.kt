package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.scenario.ScenarioCatalog
import java.util.Locale

/** Only fixed reason codes and numeric counters cross this boundary. */
object DiagnosticsReasons {
    val keys: Set<String> = setOf(
        "transport.failed",
        "diagnostics.unknown",
        "diagnostics.disconnected",
        "diagnostics.lightingChanged",
        "diagnostics.clockChanged",
        "diagnostics.signalsActive",
        "diagnostics.noProject",
        "diagnostics.noSignals",
        "diagnostics.retired",
        "analysis.indexing",
        "analysis.notApplicable",
        "analysis.unknown",
        "connection.disconnected",
        "device.ambient",
        "device.ambiguous",
        "device.disabled",
        "device.missing",
        "device.unconfirmed",
        "integration.disabled",
        "integration.shuttingDown",
        "integration.unfocused",
        "scenario.disabled",
        "scenario.unavailable",
        "target.confirmLayout",
        "target.emptyDevice",
        "target.unassigned"
    ) + SuppressionReason.entries.map { key(it) }

    fun key(reason: SuppressionReason): String = "diagnostics.reason.${reason.name.lowercase(Locale.ROOT)}"
    fun safe(value: String?): String = value?.takeIf { it in keys } ?: "diagnostics.unknown"
}

object DiagnosticsExport {
    fun copyText(snapshot: DiagnosticsSnapshot, versions: Map<String, String>, capabilityLabels: List<String>): String =
        buildString {
            appendLine("KGBoard diagnostics")
            for ((key, pattern) in mapOf(
                "plugin" to "\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.]+)?",
                "ide" to "[A-Z]{2,4}-\\d{3}\\.\\d+\\.\\d+", "java" to "\\d+(?:\\.\\d+){0,3}"
            )) {
                versions[key]?.takeIf { it.length <= 80 && Regex(pattern).matches(it) }?.let { appendLine("$key=$it") }
            }
            // C1 supplies these fixed codes; unknown descriptions are deliberately excluded.
            capabilityLabels.filter {
                it in setOf(
                    "capability.available",
                    "capability.unavailable",
                    "capability.degraded"
                )
            }
                .forEach { appendLine(it) }
            val decision = snapshot.decision
            appendLine("session=${decision?.token?.session?.value ?: 0} inventory=${decision?.token?.revision ?: 0} revision=${decision?.revision?.value ?: 0}")
            appendLine("devices=${decision?.devices?.size ?: 0}")
            decision?.devices?.forEachIndexed { index, device ->
                appendLine(
                    "device=$index leds=${device.frame?.colors?.size ?: 0} reason=${
                        DiagnosticsReasons.safe(
                            device.idleReasonKey ?: "diagnostics.signalsActive"
                        )
                    }"
                )
                device.rules.forEach { rule ->
                    val id = rule.ruleId.takeIf { ScenarioCatalog.byId(it) != null } ?: "unknown"
                    appendLine(
                        "rule=$id requested=${rule.requested.size} visible=${rule.visible.size} reasons=${
                            rule.reasons.joinToString(
                                ","
                            ) { DiagnosticsReasons.key(it) }
                        }"
                    )
                }
            }
            snapshot.delivery.values.forEachIndexed { index, delivery ->
                appendLine(
                    "delivery=$index pending=${delivery.pending?.value ?: 0} sent=${delivery.lastSent?.value ?: 0} at=${delivery.sentAtMs ?: 0} error=${
                        delivery.error?.let(
                            DiagnosticsReasons::safe
                        ) ?: "none"
                    }"
                )
            }
            snapshot.history.forEach {
                appendLine("history=${DiagnosticsReasons.safe(it.reasonKey)} first=${it.firstSeenMs} last=${it.lastSeenMs} count=${it.count}")
            }
        }
}
