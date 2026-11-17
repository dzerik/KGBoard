package com.kgboard.rgb.bridge

import com.kgboard.rgb.runtime.Cancellation
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.desktop.SystemSleepEvent
import java.awt.desktop.SystemSleepListener
import java.util.concurrent.atomic.AtomicBoolean

interface ResumeSignalSource {
    val available: Boolean
    fun subscribe(resumed: () -> Unit): Cancellation
}

/** Public JDK Desktop API, capability-gated on the actual OS/toolkit. Never power-save mode. */
class DesktopResumeSignalSource : ResumeSignalSource {
    private val desktop = runCatching {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) null
        else Desktop.getDesktop().takeIf { it.isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP) }
    }.getOrNull()
    @Volatile
    override var available: Boolean = desktop != null
        private set

    override fun subscribe(resumed: () -> Unit): Cancellation {
        val desktop = desktop ?: return Cancellation {}
        val active = AtomicBoolean(true)
        val listener = object : SystemSleepListener {
            override fun systemAboutToSleep(event: SystemSleepEvent) {}
            override fun systemAwoke(event: SystemSleepEvent) {
                if (active.get()) resumed()
            }
        }
        if (runCatching { desktop.addAppEventListener(listener) }.isFailure) {
            available = false
            return Cancellation {}
        }
        return Cancellation {
            if (active.getAndSet(false)) runCatching { desktop.removeAppEventListener(listener) }
        }
    }
}
