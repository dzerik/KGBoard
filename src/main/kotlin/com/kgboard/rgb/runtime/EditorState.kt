package com.kgboard.rgb.runtime

sealed interface AnalysisResult {
    data object Unknown : AnalysisResult
    data object NotApplicable : AnalysisResult
    data class Ready(val hasErrors: Boolean, val hasWarnings: Boolean) : AnalysisResult
}

data class EditorState(
    val revision: EditorRevision? = null, val applicable: Boolean = false,
    val analysis: AnalysisResult = AnalysisResult.NotApplicable, val hasTodos: Boolean? = null
)
