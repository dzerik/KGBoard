package com.kgboard.rgb.diagnostics

import com.kgboard.rgb.runtime.*
import java.awt.Color

/** The same painter records both color and owner; explanations never reconstruct the frame. */
object DecisionComposer {
    fun compose(idle: Color, count: Int, layers: List<CompositionLayer>): ComposedPixels {
        require(count >= 0)
        val ordered = layers.sortedWith(
            compareBy(
                { it.policyOverlay }, { it.priority }, { it.createdAtMs },
                { it.owner?.value.orEmpty() }, { it.source })
        )
        val pixels = MutableList(count) { idle }
        val owners = IntArray(count) { -1 }
        ordered.forEachIndexed { index, layer ->
            layer.indices.zip(layer.colors).forEach { (led, color) ->
                if (led in pixels.indices) {
                    pixels[led] = color; owners[led] = index
                }
            }
        }
        val visibility = ordered.mapIndexed { index, layer ->
            val requested = layer.indices.filter { it in pixels.indices }.distinct()
            val visible = requested.filter { owners[it] == index }
            val reasons = when {
                requested.isEmpty() -> setOf(SuppressionReason.UNASSIGNED)
                visible.size < requested.size -> setOf(SuppressionReason.COVERED)
                else -> emptySet()
            }
            RuleVisibility(
                layer.ruleId,
                layer.owner,
                layer.source,
                frozenList(requested),
                frozenList(visible),
                frozenSet(reasons)
            )
        }
        return ComposedPixels(frozenList(pixels), frozenList(visibility))
    }
}
