package com.kgboard.rgb.bridge
import com.intellij.ide.AppLifecycleListener
import com.kgboard.rgb.runtime.LightingRuntimeService
class AppLifecycleHandler : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) = LightingRuntimeService.getInstance().dispose()
}
