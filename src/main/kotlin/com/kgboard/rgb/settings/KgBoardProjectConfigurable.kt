package com.kgboard.rgb.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.ColumnInfo
import java.awt.Color
import java.awt.Dimension
import javax.swing.table.TableCellEditor
import javax.swing.DefaultCellEditor
import javax.swing.JTextField

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

    // Branch color rules table model
    private val branchRulesModel = ListTableModel<KgBoardProjectSettings.GitColorRule>(
        object : ColumnInfo<KgBoardProjectSettings.GitColorRule, String>("Pattern (regex)") {
            override fun valueOf(item: KgBoardProjectSettings.GitColorRule): String = item.pattern
            override fun setValue(item: KgBoardProjectSettings.GitColorRule, value: String) { item.pattern = value }
            override fun isCellEditable(item: KgBoardProjectSettings.GitColorRule): Boolean = true
            override fun getEditor(item: KgBoardProjectSettings.GitColorRule): TableCellEditor =
                DefaultCellEditor(JTextField())
        },
        object : ColumnInfo<KgBoardProjectSettings.GitColorRule, String>("Color (#RRGGBB)") {
            override fun valueOf(item: KgBoardProjectSettings.GitColorRule): String = item.color
            override fun setValue(item: KgBoardProjectSettings.GitColorRule, value: String) { item.color = value }
            override fun isCellEditable(item: KgBoardProjectSettings.GitColorRule): Boolean = true
            override fun getEditor(item: KgBoardProjectSettings.GitColorRule): TableCellEditor =
                DefaultCellEditor(JTextField())
        }
    )

    private fun colorPanel(): ColorPanel = ColorPanel().apply {
        preferredSize = Dimension(90, 25)
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

        // Load branch color rules
        branchRulesModel.items = s.state.gitBranchColorRules.map {
            KgBoardProjectSettings.GitColorRule(it.pattern, it.color)
        }

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

            // ── Git Branch Color Rules ──
            group("Git Branch Color Rules") {
                row {
                    comment("Apply background color based on branch name regex. First match wins.<br/>" +
                            "Example: <code>^main$</code> → green, <code>^feature/</code> → blue.")
                }
                row {
                    val table = JBTable(branchRulesModel)
                    val decorator = ToolbarDecorator.createDecorator(table)
                        .setAddAction {
                            branchRulesModel.addRow(KgBoardProjectSettings.GitColorRule(".*", "#FFFFFF"))
                        }
                        .setRemoveAction {
                            val selected = table.selectedRow
                            if (selected >= 0) branchRulesModel.removeRow(selected)
                        }
                        .disableUpDownActions()
                    cell(decorator.createPanel())
                        .align(AlignX.FILL)
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
        // Save branch color rules
        s.state.gitBranchColorRules = branchRulesModel.items.map {
            KgBoardProjectSettings.GitColorRule(it.pattern, it.color)
        }.toMutableList()
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
        // Reset branch color rules
        branchRulesModel.items = s.state.gitBranchColorRules.map {
            KgBoardProjectSettings.GitColorRule(it.pattern, it.color)
        }
    }

    override fun isModified(): Boolean {
        if (super.isModified()) return true
        val s = KgBoardProjectSettings.getInstance(project)
        // Check color panels
        if (colorChanged(gitUncommittedColor, s.gitUncommittedColor) ||
            colorChanged(gitConflictColor, s.gitConflictColor) ||
            colorChanged(gitUnpushedColor, s.gitUnpushedColor) ||
            colorChanged(gitCleanColor, s.gitCleanColor) ||
            colorChanged(analysisErrorColor, s.analysisErrorColor) ||
            colorChanged(analysisWarningColor, s.analysisWarningColor) ||
            colorChanged(analysisCleanColor, s.analysisCleanColor)) return true
        // Check branch rules
        val currentRules = branchRulesModel.items
        val savedRules = s.state.gitBranchColorRules
        if (currentRules.size != savedRules.size) return true
        return currentRules.zip(savedRules).any { (a, b) -> a.pattern != b.pattern || a.color != b.color }
    }

    private fun colorChanged(panel: ColorPanel, hex: String): Boolean {
        val panelColor = panel.selectedColor ?: return false
        return toHex(panelColor) != hex
    }

    private fun parseColor(hex: String): Color = try { Color.decode(hex) } catch (_: Exception) { Color.BLACK }

    private fun toHex(color: Color?): String {
        color ?: return "#000000"
        return String.format("#%02X%02X%02X", color.red, color.green, color.blue)
    }
}
