package com.kgboard.rgb.settings.ui

import com.intellij.ui.DocumentAdapter
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.event.DocumentEvent

internal fun <T : JComponent> T.identified(id: String, label: String): T = apply {
    name = id
    accessibleContext.accessibleName = label
}

internal fun JTextField.onEdit(action: () -> Unit) {
    document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = action()
    })
}

/** Animated rendering changes time, not the settings controls; only presentation changes enter EDT. */
internal fun com.kgboard.rgb.runtime.RuntimeState.settingsPresentation(
    owner: Long,
    project: com.kgboard.rgb.runtime.ProjectId? = null
): List<Any?> = listOf(
    connection, settings.enabled, shuttingDown, settingsSession?.owners?.get(owner), preview,
    settings.untrustedSerials, groups, project?.let { it in projects })

/** Forms follow the settings viewport width; tall sections scroll without widening the whole dialog. */
internal fun settingsScrollPane(view: JComponent): com.intellij.ui.components.JBScrollPane {
    val content = object : javax.swing.JPanel(java.awt.BorderLayout()), javax.swing.Scrollable {
        init {
            add(view, java.awt.BorderLayout.CENTER)
        }

        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(rectangle: java.awt.Rectangle, orientation: Int, direction: Int) =
            com.intellij.util.ui.JBUI.scale(16)

        override fun getScrollableBlockIncrement(rectangle: java.awt.Rectangle, orientation: Int, direction: Int) =
            (if (orientation == javax.swing.SwingConstants.VERTICAL) rectangle.height else rectangle.width).coerceAtLeast(
                1
            )

        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = (parent?.height ?: 0) > preferredSize.height
    }
    return com.intellij.ui.components.JBScrollPane(content).apply {
        border = null; minimumSize = java.awt.Dimension(0, 0)
    }
}

internal fun com.kgboard.rgb.target.GroupDefinition.displayName(): String =
    if (id.startsWith("builtin:")) com.kgboard.rgb.KgBoardBundle.message("keygroup." + id.removePrefix("builtin:")) else name
