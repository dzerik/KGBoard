package com.kgboard.rgb.client

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Application-level service managing the OpenRGB connection lifecycle.
 * Handles auto-reconnect and provides thread-safe access to the RGB client.
 */
@Service(Service.Level.APP)
class OpenRgbConnectionService : Disposable {

    private val log = Logger.getInstance(OpenRgbConnectionService::class.java)

    private var client: OpenRgbClient? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "KGBoard-RGB").apply { isDaemon = true }
    }
    private var reconnectTask: ScheduledFuture<*>? = null
    private val lock = Any()

    private var cachedDevices: List<RgbDeviceInfo> = emptyList()
    private var cachedDeviceCount: Int = 0

    val isConnected: Boolean
        get() = synchronized(lock) { client?.isConnected == true }

    val devices: List<RgbDeviceInfo>
        get() = cachedDevices

    val deviceCount: Int
        get() = cachedDeviceCount

    fun connect() {
        executor.submit {
            doConnect()
        }
    }

    fun disconnect() {
        synchronized(lock) {
            reconnectTask?.cancel(false)
            reconnectTask = null
            client?.disconnect()
            client = null
            cachedDevices = emptyList()
            cachedDeviceCount = 0
        }
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    fun setAllLeds(deviceIndex: Int, color: Color) {
        executor.submit {
            try {
                synchronized(lock) {
                    client?.setAllLeds(deviceIndex, color)
                }
            } catch (e: Exception) {
                log.warn("Failed to set LEDs: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    fun updateLeds(deviceIndex: Int, colors: List<Color>) {
        executor.submit {
            try {
                synchronized(lock) {
                    client?.setCustomMode(deviceIndex)
                    client?.updateLeds(deviceIndex, colors)
                }
            } catch (e: Exception) {
                log.warn("Failed to update LEDs: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    fun updateSingleLed(deviceIndex: Int, ledIndex: Int, color: Color) {
        executor.submit {
            try {
                synchronized(lock) {
                    client?.updateSingleLed(deviceIndex, ledIndex, color)
                }
            } catch (e: Exception) {
                log.warn("Failed to update single LED: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    fun refreshDevices() {
        executor.submit {
            try {
                synchronized(lock) {
                    val c = client ?: return@submit
                    cachedDeviceCount = c.getControllerCount()
                    cachedDevices = c.getAllDevices()
                    log.info("Discovered $cachedDeviceCount OpenRGB devices")
                }
            } catch (e: Exception) {
                log.warn("Failed to refresh devices: ${e.message}")
            }
        }
    }

    private fun doConnect() {
        val settings = KgBoardSettings.getInstance()
        synchronized(lock) {
            if (client?.isConnected == true) return
            try {
                val c = OpenRgbClient(settings.host, settings.port)
                c.connect()
                client = c
                cachedDeviceCount = c.getControllerCount()
                cachedDevices = c.getAllDevices()
                log.info("OpenRGB connected: $cachedDeviceCount devices found")
                reconnectTask?.cancel(false)
                reconnectTask = null
            } catch (e: Exception) {
                log.warn("OpenRGB connection failed: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (reconnectTask != null) return
            val settings = KgBoardSettings.getInstance()
            if (!settings.autoReconnect) return
            reconnectTask = executor.scheduleWithFixedDelay(
                { doConnect() },
                5, 10, TimeUnit.SECONDS
            )
        }
    }

    override fun dispose() {
        disconnect()
        executor.shutdownNow()
    }

    companion object {
        fun getInstance(): OpenRgbConnectionService =
            ApplicationManager.getApplication().getService(OpenRgbConnectionService::class.java)
    }
}
