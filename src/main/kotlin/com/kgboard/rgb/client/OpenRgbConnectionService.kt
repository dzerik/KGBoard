package com.kgboard.rgb.client

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.kgboard.rgb.settings.KgBoardSettings
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /** Bumped by disconnect() so an in-flight doConnect() can detect cancellation and discard its result. */
    private val connectEpoch = AtomicInteger(0)

    @Volatile
    private var cachedDevices: List<RgbDeviceInfo> = emptyList()
    @Volatile
    private var cachedDeviceCount: Int = 0
    private val connectionListeners = CopyOnWriteArrayList<Runnable>()

    val isConnected: Boolean
        get() = synchronized(lock) { client?.isConnected == true }

    val devices: List<RgbDeviceInfo>
        get() = cachedDevices

    val deviceCount: Int
        get() = cachedDeviceCount

    fun addConnectionListener(listener: Runnable) {
        connectionListeners.add(listener)
    }

    fun removeConnectionListener(listener: Runnable) {
        connectionListeners.remove(listener)
    }

    fun connect() {
        executor.submit {
            doConnect()
        }
    }

    fun disconnect() {
        // Invalidate any in-flight connect so its result is discarded instead of published.
        connectEpoch.incrementAndGet()
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
                log.warn("Failed to set LEDs: [${e.javaClass.simpleName}] ${e.message}")
                scheduleReconnect()
            }
        }
    }

    /**
     * Lightweight LED test: uses cached device info, sends updateLeds directly.
     * No setCustomMode — safe for EVision and similar keyboards.
     *
     * @param ledIndices if non-empty, only these LEDs get [color]; rest get [idleColor]
     */
    fun testLeds(deviceIndex: Int, color: Color, ledIndices: List<Int> = emptyList(), idleColor: Color = Color.BLACK) {
        val hex = String.format("#%06X", color.rgb and 0xFFFFFF)
        log.info("testLeds: device=$deviceIndex, color=$hex, indices=${if (ledIndices.isEmpty()) "all" else ledIndices}")
        executor.submit {
            try {
                synchronized(lock) {
                    val c = client
                    if (c == null) {
                        log.warn("testLeds: client is null")
                        return@submit
                    }
                    val numLeds = cachedDevices.getOrNull(deviceIndex)?.numLeds
                    if (numLeds == null) {
                        log.warn("testLeds: device $deviceIndex not found in cache (${cachedDevices.size} cached)")
                        return@submit
                    }
                    val frame = if (ledIndices.isEmpty()) {
                        List(numLeds) { color }
                    } else {
                        val indexSet = ledIndices.toSet()
                        List(numLeds) { i -> if (i in indexSet) color else idleColor }
                    }
                    log.info("testLeds: sending $numLeds LEDs (${ledIndices.size} targeted)")
                    c.updateLeds(deviceIndex, frame)
                    log.info("testLeds: sent OK")
                }
            } catch (e: Exception) {
                log.warn("testLeds: FAILED: ${e.message}", e)
            }
        }
    }

    fun updateLeds(deviceIndex: Int, colors: List<Color>) {
        executor.submit {
            try {
                synchronized(lock) {
                    client?.updateLeds(deviceIndex, colors)
                }
            } catch (e: Exception) {
                log.warn("Failed to update LEDs: [${e.javaClass.simpleName}] ${e.message}")
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
                log.warn("Failed to update single LED: [${e.javaClass.simpleName}] ${e.message}")
                scheduleReconnect()
            }
        }
    }

    /**
     * Multi-device: set all LEDs on all enabled devices.
     * 10ms delay between device updates to avoid overwhelming OpenRGB.
     */
    fun setAllLedsMultiDevice(color: Color) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.multiDeviceEnabled || settings.deviceConfigs.isEmpty()) {
            setAllLeds(settings.deviceIndex, color)
            return
        }
        executor.submit {
            try {
                synchronized(lock) {
                    val c = client ?: return@submit
                    for (config in settings.deviceConfigs) {
                        if (!config.enabled) continue
                        try {
                            c.setAllLeds(config.deviceIndex, color)
                            Thread.sleep(10)
                        } catch (e: Exception) {
                            log.warn("Failed to set LEDs on device ${config.deviceIndex}: [${e.javaClass.simpleName}] ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("Multi-device set LEDs failed: [${e.javaClass.simpleName}] ${e.message}")
                scheduleReconnect()
            }
        }
    }

    /**
     * Multi-device: update LEDs on all enabled devices.
     */
    fun updateLedsMultiDevice(colors: List<Color>) {
        val settings = KgBoardSettings.getInstance()
        if (!settings.multiDeviceEnabled || settings.deviceConfigs.isEmpty()) {
            updateLeds(settings.deviceIndex, colors)
            return
        }
        executor.submit {
            try {
                synchronized(lock) {
                    val c = client ?: return@submit
                    for (config in settings.deviceConfigs) {
                        if (!config.enabled) continue
                        try {
                            val deviceInfo = cachedDevices.getOrNull(config.deviceIndex)
                            val adjustedColors = if (deviceInfo != null && deviceInfo.numLeds != colors.size) {
                                if (deviceInfo.numLeds < colors.size) {
                                    colors.take(deviceInfo.numLeds)
                                } else {
                                    colors + List(deviceInfo.numLeds - colors.size) { colors.lastOrNull() ?: Color.BLACK }
                                }
                            } else colors
                            c.updateLeds(config.deviceIndex, adjustedColors)
                            Thread.sleep(10)
                        } catch (e: Exception) {
                            log.warn("Failed to update LEDs on device ${config.deviceIndex}: [${e.javaClass.simpleName}] ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("Multi-device update LEDs failed: [${e.javaClass.simpleName}] ${e.message}")
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
                log.warn("Failed to refresh devices: [${e.javaClass.simpleName}] ${e.message}")
            }
        }
    }

    private fun doConnect() {
        val settings = KgBoardSettings.getInstance()

        // Fast path: already connected.
        synchronized(lock) {
            if (client?.isConnected == true) return
        }

        // Perform the blocking network I/O WITHOUT holding [lock], so EDT callers of
        // isConnected/disconnect() never block on a slow/hung connect. doConnect() only ever
        // runs on the single-threaded executor, so concurrent connects can't overlap.
        val epoch = connectEpoch.get()
        val c = OpenRgbClient(settings.host, settings.port)
        val count: Int
        val devices: List<RgbDeviceInfo>
        try {
            c.connect()
            count = c.getControllerCount()
            devices = c.getAllDevices()
            // Set custom mode once per device at connect time
            if (!settings.skipCustomMode) {
                for (i in 0 until count) {
                    try {
                        c.setCustomMode(i)
                    } catch (e: Exception) {
                        log.warn("setCustomMode failed for device $i: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("OpenRGB connection failed: [${e.javaClass.simpleName}] ${e.message}")
            try {
                c.disconnect()
            } catch (_: Exception) {
            }
            scheduleReconnect()
            return
        }

        var connected = false
        synchronized(lock) {
            if (epoch != connectEpoch.get()) {
                // disconnect() was called while we were connecting — discard this client.
                try {
                    c.disconnect()
                } catch (_: Exception) {
                }
                return
            }
            client = c
            cachedDeviceCount = count
            cachedDevices = devices
            reconnectTask?.cancel(false)
            reconnectTask = null
            connected = true
        }

        if (connected) {
            log.info("OpenRGB connected: $count devices found")
            for (listener in connectionListeners) {
                try {
                    listener.run()
                } catch (e: Exception) {
                    log.warn("Connection listener failed: [${e.javaClass.simpleName}] ${e.message}")
                }
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
