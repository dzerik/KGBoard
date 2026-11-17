package com.kgboard.rgb.settings.ui

import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.runtime.BranchRule
import com.kgboard.rgb.settings.KgBoardProjectSettings.GitColorRule
import com.kgboard.rgb.settings.ProjectDraft
import java.awt.Color
import javax.swing.*
import javax.swing.table.TableCellEditor

class BranchRulesEditor(private val draft: ProjectDraft, private val currentBranch: () -> String?) {
    private var model: ListTableModel<GitColorRule>? = null
    private var table: JBTable? = null
    private var hint: JBLabel? = null
    private var interval: JBTextField? = null
    fun createPanel(): JComponent {
        fun column(key: String, get: (GitColorRule) -> String, set: (GitColorRule, String) -> Unit) =
            object : ColumnInfo<GitColorRule, String>(message(key)) {
                override fun valueOf(item: GitColorRule) = get(item)
                override fun setValue(item: GitColorRule, value: String) = set(item, value)
                override fun isCellEditable(item: GitColorRule) = true
                override fun getEditor(item: GitColorRule): TableCellEditor =
                    DefaultCellEditor(JBTextField().identified("branch.pending", message(key)))
            }

        val rows = ListTableModel<GitColorRule>(
            column("branch.pattern", { it.pattern }, { item, value -> item.pattern = value }),
            column("branch.color", { it.color }, { item, value -> item.color = value })
        )
        model = rows
        val list = JBTable(rows).identified("branch.rules", message("branch.rules")).also { table = it }
        list.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val branch = JBTextField(currentBranch().orEmpty()).identified("branch.test", message("branch.test"))
        hint = JBLabel()
        val poll = JBTextField(draft.state.gitPollingIntervalSec.toString())
            .identified("project.poll", message("project.poll")).also { interval = it }
        return panel {
            group(message("branch.rules")) {
                row { scrollCell(list).align(Align.FILL) }.resizableRow()
                row {
                    button(message("branch.add")) { rows.addRow(GitColorRule()) }
                    button(message("branch.remove")) { stopEditing(); if (list.selectedRow >= 0) rows.removeRow(list.selectedRow) }
                    button(message("branch.up")) { move(-1) }
                    button(message("branch.down")) { move(1) }
                }
                row(message("branch.test")) {
                    cell(branch).align(AlignX.FILL)
                    button(message("branch.checkMatch")) {
                        try {
                            flush()
                            val match = draft.state.gitBranchColorRules.indexOfFirst {
                                BranchRule(it.pattern, it.color).matches(branch.text)
                            }
                            hint!!.text =
                                if (match < 0) message("branch.noMatch") else message("branch.match", match + 1)
                        } catch (error: IllegalArgumentException) {
                            hint!!.text = error.message
                        }
                    }
                }
                row { cell(hint!!) }
            }
            row(message("project.poll")) { cell(poll).columns(7) }
        }.also { reset() }
    }

    private fun stopEditing() {
        val list = table ?: return
        require(!list.isEditing || list.cellEditor.stopCellEditing()) { message("validation.invalid") }
    }

    private fun move(delta: Int) {
        stopEditing()
        val list = table ?: return
        val rows = model ?: return
        val index = list.selectedRow
        val next = index + delta
        if (index !in rows.items.indices || next !in rows.items.indices) return
        val reordered = rows.items.toMutableList()
        java.util.Collections.swap(reordered, index, next)
        rows.items = reordered
        list.setRowSelectionInterval(next, next)
    }

    fun hasPendingEdit(): Boolean = table?.isEditing == true

    fun flush() {
        stopEditing()
        val prepared = model?.items?.map { row ->
            require(runCatching { Regex(row.pattern) }.isSuccess) { message("branch.invalidPattern", row.pattern) }
            require(Regex("#[0-9a-fA-F]{6}").matches(row.color)) { message("branch.invalidColor") }
            GitColorRule(row.pattern, row.color)
        }?.toMutableList() ?: mutableListOf()
        val seconds = requireNotNull(interval!!.text.trim().toIntOrNull()?.takeIf { it >= 2 }) {
            message("validation.number", message("project.poll"), 2, Int.MAX_VALUE)
        }
        draft.state.gitBranchColorRules = prepared
        draft.state.gitPollingIntervalSec = seconds
    }

    fun reset() {
        model?.items = draft.state.gitBranchColorRules.map { GitColorRule(it.pattern, it.color) }
        interval?.text = draft.state.gitPollingIntervalSec.toString()
    }
}
