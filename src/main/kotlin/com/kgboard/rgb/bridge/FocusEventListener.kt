package com.kgboard.rgb.bridge
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import com.kgboard.rgb.runtime.*
class FocusEventListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) = LightingRuntimeService.getInstance().refreshFocus()
    override fun applicationDeactivated(ideFrame: IdeFrame) {
        LightingRuntimeService.getInstance().events.dispatch(RuntimeEvent.FocusChanged(false))
    }
}
