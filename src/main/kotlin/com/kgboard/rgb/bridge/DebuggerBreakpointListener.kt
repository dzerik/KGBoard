package com.kgboard.rgb.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.kgboard.rgb.effect.EffectManagerService
import com.kgboard.rgb.effect.StaticEffect
import com.kgboard.rgb.settings.KgBoardSettings
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors debugger sessions and triggers RGB effects:
 * - Breakpoint hit (session paused) → static color
 * - Session resumed → keep effect (prevents blink between steps)
 * - Session stopped → remove effect
 */
@Service(Service.Level.PROJECT)
class DebuggerBreakpointListener(private val project: Project) : Disposable {

    private val log = Logger.getInstance(DebuggerBreakpointListener::class.java)
    private val disposed = AtomicBoolean(false)

    companion object {
        const val EFFECT_ID = "debug-breakpoint"

        fun getInstance(project: Project): DebuggerBreakpointListener =
            project.getService(DebuggerBreakpointListener::class.java)
    }

    fun start() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.state.debugBreakpointFlashEnabled) return

        project.messageBus.connect(this)
            .subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: com.intellij.xdebugger.XDebugProcess) {
                    attachSessionListener(debugProcess.session)
                }
            })

        log.info("Debugger breakpoint listener started")
    }

    private fun attachSessionListener(session: XDebugSession) {
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                if (disposed.get()) return
                onBreakpointHit()
            }

            override fun sessionResumed() {
                if (disposed.get()) return
                onResumed()
            }

            override fun sessionStopped() {
                if (disposed.get()) return
                onStopped()
            }
        })
    }

    private fun onBreakpointHit() {
        val settings = KgBoardSettings.getInstance()
        if (!settings.enabled || !settings.state.debugBreakpointFlashEnabled) return

        val color = settings.parseColor(settings.state.debugBreakpointFlashColor)
        val target = settings.resolveTarget(settings.debugBreakpointLedIndices)
        try {
            val effectManager = EffectManagerService.getInstance(project)
            effectManager.addTargetedEffect(
                EFFECT_ID,
                StaticEffect(
                    color = color,
                    name = "breakpoint-hit",
                    priority = 8,
                    target = target
                )
            )
        } catch (e: Exception) {
            log.warn("Breakpoint flash error: ${e.message}")
        }
    }

    private fun onResumed() {
        // Keep breakpoint effect during stepping to prevent blink
        // (red → purple pulse → red). Next sessionPaused() will replace it
        // via same EFFECT_ID. Effect is removed only on sessionStopped().
    }

    private fun onStopped() {
        try {
            EffectManagerService.getInstance(project).removeTargetedEffect(EFFECT_ID)
        } catch (_: Exception) {
            // ignore
        }
    }

    override fun dispose() {
        disposed.set(true)
        try {
            EffectManagerService.getInstance(project).removeTargetedEffect(EFFECT_ID)
        } catch (_: Exception) {
            // project may already be disposed
        }
    }
}
