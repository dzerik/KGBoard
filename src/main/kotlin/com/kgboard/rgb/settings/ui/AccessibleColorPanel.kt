package com.kgboard.rgb.settings.ui

import com.intellij.ui.ColorPanel
import com.kgboard.rgb.KgBoardBundle.message
import java.awt.EventQueue
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.JTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import javax.accessibility.*
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/** Keeps the platform color picker while exposing an action and keyboard focus to assistive tools. */
internal class AccessibleColorPanel : ColorPanel() {
    private var colorAccessibility: AccessibleContext? = null

    init {
        isFocusable = true
        components.filterIsInstance<JTextField>().forEach {
            it.identified("color.value", message("color.value")); it.isFocusable = false
        }
        val restingBorder = border ?: JBUI.Borders.empty(1)
        border = restingBorder
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                border = BorderFactory.createLineBorder(
                    JBColor.namedColor("Component.focusColor", JBColor.BLUE),
                    JBUI.scale(1)
                )
            }

            override fun focusLost(event: FocusEvent) {
                border = restingBorder
            }
        })
        val action = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                onPressed()
            }
        }
        actionMap.put("chooseColor", action)
        for (key in listOf(KeyEvent.VK_SPACE, KeyEvent.VK_ENTER))
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "chooseColor")
        addActionListener {
            getAccessibleContext().accessibleDescription =
                selectedColor?.let { "#%06X".format(it.rgb and 0xFFFFFF) }.orEmpty()
        }
    }

    override fun getAccessibleContext(): AccessibleContext {
        if (colorAccessibility == null) colorAccessibility = object : AccessibleJComponent(), AccessibleAction {
            override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
            override fun getAccessibleDescription(): String? =
                selectedColor?.let { "#%06X".format(it.rgb and 0xFFFFFF) }
                    ?: super.getAccessibleDescription()

            override fun getAccessibleAction(): AccessibleAction = this
            override fun getAccessibleActionCount() = 1
            override fun getAccessibleActionDescription(index: Int): String? =
                if (index == 0) message("color.choose") else null

            override fun doAccessibleAction(index: Int): Boolean {
                if (index != 0 || !isEnabled) return false
                EventQueue.invokeLater { onPressed() }
                return true
            }
        }
        return requireNotNull(colorAccessibility)
    }
}
