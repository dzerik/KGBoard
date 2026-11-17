package com.kgboard.rgb.settings.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.*
import com.kgboard.rgb.KgBoardBundle.message
import com.kgboard.rgb.device.DeviceChoice
import com.kgboard.rgb.runtime.*
import com.kgboard.rgb.settings.SettingsValidation
import com.kgboard.rgb.target.*
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.*

/** The displayed choice is supplied by the inventory view; clicks never renew its token. */
class LedTargetEditor(
    private val selectedDevice: () -> DeviceChoice?,
    private val snapshot: () -> RuntimeState,
    private val groups: () -> Map<String, GroupDefinition>,
    private val onChanged: () -> Unit = {}
) {
    private enum class Kind { UNASSIGNED, ALL, NAMED, GROUP, RAW }
    private data class GroupChoice(val id: String, val label: String) {
        override fun toString() = label
    }

    private var stored: TargetSpec = TargetSpec.Unassigned
    private var fingerprint: String? = null
    private var loading = false
    private val selectedNames = linkedSetOf<String>()
    private var kind: JComboBox<Kind>? = null
    private var raw: JBTextField? = null
    private var search: JBTextField? = null
    private var names: JBList<String>? = null
    private var group: JComboBox<GroupChoice>? = null
    private var hint: JBLabel? = null
    private var cards: JPanel? = null
    private var lastChoice: DeviceChoice? = null

    fun createPanel(): JComponent {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val cardLayout = CardLayout()
        val pages = object : JPanel(cardLayout) {
            override fun getPreferredSize(): java.awt.Dimension = components.firstOrNull { it.isVisible }?.preferredSize
                ?: java.awt.Dimension(0, 0)

            override fun getMinimumSize() = java.awt.Dimension(0, 0)
        }.also { cards = it }
        val selector =
            JComboBox(Kind.entries.toTypedArray()).identified("scenario.target.kind", message("scenario.target"))
        kind = selector
        selector.renderer = DefaultListCellRenderer().let { delegate ->
            ListCellRenderer { list, value, index, selected, focus ->
                delegate.getListCellRendererComponent(
                    list,
                    value?.let { message("target.${it.name}") },
                    index,
                    selected,
                    focus
                )
            }
        }
        val rawInput = JBTextField().identified("scenario.target.raw", message("target.raw")).also { raw = it }
        rawInput.onEdit {
            if (!loading) {
                fingerprint = null; onChanged()
            }; refreshHint()
        }
        val searchInput =
            JBTextField().identified("scenario.target.search", message("target.search")).also { search = it }
        val ledList = JBList<String>().identified("scenario.target.keys", message("target.keys")).also { names = it }
        ledList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        ledList.visibleRowCount = 6
        ledList.addListSelectionListener {
            if (!loading && !it.valueIsAdjusting) {
                val visible = (0 until ledList.model.size).map { i -> ledList.model.getElementAt(i) }.toSet()
                selectedNames.removeAll(visible); selectedNames.addAll(ledList.selectedValuesList); refreshHint(); onChanged()
            }
        }
        searchInput.onEdit { refreshNames() }
        val groupInput =
            JComboBox<GroupChoice>().identified("scenario.target.group", message("target.group")).also { group = it }
        groupInput.addActionListener { refreshHint(); if (!loading) onChanged() }
        pages.add(JBLabel(message("live.noAssignment")), Kind.UNASSIGNED.name)
        pages.add(JBLabel(message("target.ALL")), Kind.ALL.name)
        pages.add(panel {
            row(message("target.search")) { cell(searchInput).align(AlignX.FILL) }
            row { scrollCell(ledList).align(Align.FILL) }.resizableRow()
        }, Kind.NAMED.name)
        pages.add(panel { row(message("target.group")) { cell(groupInput).align(AlignX.FILL) } }, Kind.GROUP.name)
        pages.add(panel {
            row(message("target.raw")) { cell(rawInput).align(AlignX.FILL) }
            row { button(message("target.confirm")) { confirmLayout() } }
        }, Kind.RAW.name)
        selector.addActionListener {
            cardLayout.show(
                pages,
                (selector.selectedItem as Kind).name
            ); refreshHint(); if (!loading) onChanged()
        }
        hint = JBLabel().identified("scenario.target.status", message("scenario.target"))
        return panel {
            row(message("scenario.target")) { cell(selector).align(AlignX.FILL) }
            row { cell(pages).align(AlignX.FILL) }
            row { cell(requireNotNull(hint)).align(AlignX.FILL) }
        }.also { reset(stored) }
    }

    fun reset(value: TargetSpec) {
        stored = value
        if (kind == null) return
        loading = true
        try {
            fingerprint = (value as? TargetSpec.RawIndices)?.fingerprint
            selectedNames.clear(); selectedNames.addAll((value as? TargetSpec.NamedKeys)?.names.orEmpty())
            raw!!.text = (value as? TargetSpec.RawIndices)?.text.orEmpty()
            val selectedKind = when (value) {
                TargetSpec.Unassigned -> Kind.UNASSIGNED
                TargetSpec.All -> Kind.ALL
                is TargetSpec.NamedKeys -> Kind.NAMED
                is TargetSpec.Group -> Kind.GROUP
                is TargetSpec.RawIndices -> Kind.RAW
            }
            kind!!.selectedItem = selectedKind
            val definitions = groups().values.map { GroupChoice(it.id, it.displayName()) }.toMutableList()
            val current = (value as? TargetSpec.Group)?.id
            if (current != null && definitions.none { it.id == current }) definitions.add(
                GroupChoice(
                    current,
                    message("target.unknownGroup")
                )
            )
            group!!.model = DefaultComboBoxModel(definitions.toTypedArray())
            group!!.selectedItem = definitions.firstOrNull { it.id == current }
            refreshNames()
        } finally {
            loading = false
        }
        refreshHint()
    }

    fun value(): TargetSpec = when (kind?.selectedItem as? Kind) {
        Kind.ALL -> TargetSpec.All
        Kind.NAMED -> TargetSpec.NamedKeys(selectedNames.toList())
        Kind.GROUP -> (group?.selectedItem as? GroupChoice)?.let { TargetSpec.Group(it.id) } ?: TargetSpec.Unassigned
        Kind.RAW -> TargetSpec.RawIndices(raw!!.text, fingerprint)
        Kind.UNASSIGNED -> TargetSpec.Unassigned
        null -> stored
    }

    fun updateInventory() {
        val choice = selectedDevice()
        if (lastChoice != choice) {
            lastChoice = choice; refreshNames()
        }
        refreshHint()
    }

    private fun displayedDevice(): com.kgboard.rgb.client.RgbDeviceInfo? {
        val choice = selectedDevice() ?: return null
        val inventory = snapshot().inventory() ?: return null
        return inventory.devices.getOrNull(choice.index)?.takeIf {
            choice.token == inventory.token && choice.fingerprint == com.kgboard.rgb.device.LayoutFingerprint.of(it)
        }
    }

    private fun refreshNames() {
        val list = names ?: return
        val wasLoading = loading
        loading = true
        try {
            val all = (displayedDevice()?.ledNames.orEmpty() + selectedNames).distinct()
            val query = search?.text.orEmpty().trim()
            val visible = all.filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
            list.setListData(visible.toTypedArray())
            list.selectedIndices = visible.indices.filter { visible[it] in selectedNames }.toIntArray()
        } finally {
            loading = wasLoading
        }
    }

    private fun confirmLayout() {
        try {
            val choice = selectedDevice() ?: throw IllegalArgumentException(message("preview.selectDevice"))
            val device = displayedDevice() ?: throw IllegalArgumentException(message("preview.staleDevice"))
            require(raw!!.text.isNotBlank()) { message("preview.selectTarget") }
            SettingsValidation.indices(raw!!.text, device.numLeds)
            fingerprint = choice.fingerprint
            refreshHint(); onChanged()
        } catch (error: IllegalArgumentException) {
            hint?.text = error.message
        }
    }

    private fun refreshHint() {
        if (loading || hint == null) return
        val target = value()
        val device = displayedDevice()
        val invalidRaw = target is TargetSpec.RawIndices && (target.text.isBlank() ||
            runCatching { SettingsValidation.indices(target.text) }.isFailure)
        raw?.putClientProperty("JComponent.outline", if (invalidRaw) "error" else null)
        hint!!.text = when {
            invalidRaw -> message("target.invalidIndices")
            target is TargetSpec.RawIndices && (fingerprint == null || fingerprint != selectedDevice()?.fingerprint) -> message(
                "target.unconfirmed"
            )

            target == TargetSpec.Unassigned -> message("live.noAssignment")
            device == null -> message("live.noDevice")
            else -> TargetResolver.resolve(target, device, groups()).let {
                if (it.status == ResolutionStatus.RESOLVED) message("target.resolution", it.indices.size)
                else if (it.indices.isNotEmpty()) message("target.partial")
                else message("preview.selectTarget")
            }
        }
    }
}
