package com.kgboard.rgb.settings.ui

import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.settings.GroupDraft
import com.kgboard.rgb.target.*
import javax.swing.*

class GroupSettingsPanel(
    private val draft: GroupDraft, private val currentTarget: () -> TargetSpec,
    private val changed: (String?) -> Unit
) {
    private data class Choice(val value: GroupDefinition) {
        override fun toString() = value.displayName()
    }

    private var list: JBList<Choice>? = null
    private var name: JBTextField? = null
    private var hint: JBLabel? = null
    fun createPanel(): JComponent {
        val values = JBList<Choice>().identified("groups.list", message("target.group")).also { list = it }
        values.selectionMode = ListSelectionModel.SINGLE_SELECTION
        values.visibleRowCount = 4
        val input = JBTextField().identified("groups.name", message("group.name")).also { name = it }
        values.addListSelectionListener {
            if (!it.valueIsAdjusting) input.text = values.selectedValue?.value?.name.orEmpty()
        }
        hint = JBLabel()
        return panel {
            collapsibleGroup(message("group.manage")) {
                row { scrollCell(values).align(AlignX.FILL) }
                row(message("group.name")) { cell(input).align(AlignX.FILL) }
                row {
                    button(message("group.add")) { edit { draft.create(input.text, currentTarget()) } }
                    button(message("group.rename")) {
                        values.selectedValue?.value?.id?.let { id ->
                            edit {
                                draft.rename(
                                    id,
                                    input.text
                                ); id
                            }
                        }
                    }
                    button(message("group.delete")) {
                        values.selectedValue?.value?.id?.let { id ->
                            edit { draft.delete(id); changed(id); hint!!.text = message("group.deleted"); null }
                        }
                    }
                }
                row { cell(hint!!) }
                row { comment(message("group.builtin")) }
            }
        }.also { reset() }
    }

    private fun edit(action: () -> String?) {
        try {
            val id = action(); reset(id); changed(null)
        } catch (error: IllegalArgumentException) {
            hint?.text = message(error.message ?: "validation.invalid")
        }
    }

    fun reset(id: String? = list?.selectedValue?.value?.id) {
        val values = draft.definitions().values.map(::Choice)
        list?.setListData(values.toTypedArray())
        list?.setSelectedValue(values.firstOrNull { it.value.id == id }, true)
    }
}
