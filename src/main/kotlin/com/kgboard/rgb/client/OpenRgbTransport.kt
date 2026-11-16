package com.kgboard.rgb.client

import com.kgboard.rgb.runtime.Endpoint
import java.awt.Color

interface RgbTransport : AutoCloseable {
    fun connect(endpoint: Endpoint): List<RgbDeviceInfo>
    fun write(device: Int, colors: List<Color>)
    fun probe(): Boolean = false
    override fun close()
}

/** Blocking protocol adapter. Called exclusively by the network worker. */
class OpenRgbTransport(private val skipCustomMode: () -> Boolean) : RgbTransport {
    @Volatile private var client: OpenRgbClient? = null
    private var inventory: List<RgbDeviceInfo> = emptyList()
    private val prepared = mutableSetOf<Int>()
    override fun connect(endpoint: Endpoint): List<RgbDeviceInfo> {
        close()
        val next = OpenRgbClient(endpoint.host, endpoint.port)
        client = next
        next.connect()
        return next.getAllDevices().also { inventory = it }
    }
    override fun write(device: Int, colors: List<Color>) {
        val active = checkNotNull(client)
        if (!skipCustomMode() && prepared.add(device)) active.setCustomMode(device)
        active.updateLeds(device, colors)
    }
    override fun probe(): Boolean {
        val actual = checkNotNull(client).getAllDevices()
        fun shape(device: RgbDeviceInfo) = device.copy(colors = emptyList())
        return actual.map(::shape) != inventory.map(::shape)
    }
    fun abort() { client?.close() }
    override fun close() {
        client?.close()
        client = null
        prepared.clear()
        inventory = emptyList()
    }
}
