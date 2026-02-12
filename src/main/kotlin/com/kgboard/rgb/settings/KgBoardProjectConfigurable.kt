package com.kgboard.rgb.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.*
import java.awt.Color
import java.awt.Dimension

/**
 * Project-level settings page: Settings → Tools → KGBoard RGB (Project).
 * Configures git indicator, code analysis, shortcut highlight per project.
 */
class KgBoardProjectConfigurable(private val project: Project) : BoundConfigurable("KGBoard RGB (Project)") {

    private val gitUncommittedColor = colorPanel()
    private val gitConflictColor = colorPanel()
    private val gitUnpushedColor = colorPanel()
    private val gitCleanColor = colorPanel()
    private val analysisErrorColor = colorPanel()
    private val analysisWarningColor = colorPanel()
    private val analysisCleanColor = colorPanel()

    private fun colorPanel(): ColorPanel = ColorPanel().apply {
        preferredSize = Dimension(45, 25)
    }

    override fun createPanel(): DialogPanel {
        val s = KgBoardProjectSettings.getInstance(project)

        // Init colors
        gitUncommittedColor.selectedColor = parseColor(s.gitUncommittedColor)
        gitConflictColor.selectedColor = parseColor(s.gitConflictColor)
        gitUnpushedColor.selectedColor = parseColor(s.gitUnpushedColor)
        gitCleanColor.selectedColor = parseColor(s.gitCleanColor)
        analysisErrorColor.selectedColor = parseColor(s.analysisErrorColor)
        analysisWarningColor.selectedColor = parseColor(s.analysisWarningColor)
        analysisCleanColor.selectedColor = parseColor(s.analysisCleanColor)

        return panel {
            // ── Git Indicator ──
            group("Git Status Indicator") {
                row {
                    checkBox("Enable Git status indicator")
                        .bindSelected(s.state::gitEnabled)
                }
                row("LED indices:") {
                    textField()
                        .columns(20)
                        .bindText(s.state::gitLedIndices)
                        .comment("Comma-separated LED indices (e.g., 0,1,2). Leave empty to disable.")
                }
                row("Polling interval:") {
                    intTextField(2..60)
                        .columns(4)
                        .bindIntText(s.state::gitPollingIntervalSec)
                        .comment("Seconds between Git status checks")
                }
                row("Uncommitted:") {
                    cell(gitUncommittedColor).gap(RightGap.SMALL)
                    comment("Modified/staged files not yet committed")
                }
                row("Conflict:") {
                    cell(gitConflictColor).gap(RightGap.SMALL)
                    comment("Merge/rebase conflict in progress")
                }
                row("Unpushed:") {
                    cell(gitUnpushedColor).gap(RightGap.SMALL)
                    comment("Local commits not yet pushed to remote")
                }
                row("Clean:") {
                    cell(gitCleanColor).gap(RightGap.SMALL)
                    comment("Working tree is clean and up-to-date")
                }
            }

            // ── Code Analysis ──
            group("Code Analysis Indicator") {
                row {
                    checkBox("Enable code analysis indicator")
                        .bindSelected(s.state::analysisEnabled)
                }
                row("LED indices:") {
                    textField()
                        .columns(20)
                        .bindText(s.state::analysisLedIndices)
                        .comment("Comma-separated LED indices for analysis indicator")
                }
                row("Errors:") {
                    cell(analysisErrorColor).gap(RightGap.SMALL)
                    comment("Files with compilation errors")
                }
                row("Warnings:") {
                    cell(analysisWarningColor).gap(RightGap.SMALL)
                    comment("Files with warnings only")
                }
                row("Clean:") {
                    cell(analysisCleanColor).gap(RightGap.SMALL)
                    comment("No problems found")
                }
            }

            // ── Shortcut Highlight ──
            group("Shortcut Highlight") {
                row {
                    checkBox("Enable shortcut highlighting")
                        .bindSelected(s.state::shortcutHighlightEnabled)
                }
                row {
                    comment("Highlights relevant keyboard shortcuts based on current IDE context.<br/>" +
                            "Requires LED name mapping from OpenRGB device (auto-detected).")
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        val s = KgBoardProjectSettings.getInstance(project)
        s.state.gitUncommittedColor = toHex(gitUncommittedColor.selectedColor)
        s.state.gitConflictColor = toHex(gitConflictColor.selectedColor)
        s.state.gitUnpushedColor = toHex(gitUnpushedColor.selectedColor)
        s.state.gitCleanColor = toHex(gitCleanColor.selectedColor)
        s.state.analysisErrorColor = toHex(analysisErrorColor.selectedColor)
        s.state.analysisWarningColor = toHex(analysisWarningColor.selectedColor)
        s.state.analysisCleanColor = toHex(analysisCleanColor.selectedColor)
    }

    override fun reset() {
        super.reset()
        val s = KgBoardProjectSettings.getInstance(project)
        gitUncommittedColor.selectedColor = parseColor(s.gitUncommittedColor)
        gitConflictColor.selectedColor = parseColor(s.gitConflictColor)
        gitUnpushedColor.selectedColor = parseColor(s.gitUnpushedColor)
        gitCleanColor.selectedColor = parseColor(s.gitCleanColor)
        analysisErrorColor.selectedColor = parseColor(s.analysisErrorColor)
        analysisWarningColor.selectedColor = parseColor(s.analysisWarningColor)
        analysisCleanColor.selectedColor = parseColor(s.analysisCleanColor)
    }

    private fun parseColor(hex: String): Color = try { Color.decode(hex) } catch (_: Exception) { Color.BLACK }

    private fun toHex(color: Color?): String {
        color ?: return "#000000"
        return String.format("#%02X%02X%02X", color.red, color.green, color.blue)
    }
}
