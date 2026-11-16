package com.kgboard.rgb.device

import com.kgboard.rgb.client.RgbDeviceInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Stable ordered geometry identity. Color changes never invalidate key assignments. */
object LayoutFingerprint {
    fun of(device: RgbDeviceInfo): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size); out.write(encoded)
            }
            out.writeInt(1) // Fingerprint format version.
            out.writeInt(device.numLeds)
            out.writeInt(device.ledNames.size)
            device.ledNames.forEach(::text)
            out.writeInt(device.zones.size)
            device.zones.forEach { zone ->
                text(zone.name); out.writeInt(zone.type); out.writeInt(zone.ledsCount)
                out.writeInt(zone.matrixHeight); out.writeInt(zone.matrixWidth)
                out.writeInt(zone.matrixMap?.size ?: -1)
                zone.matrixMap?.forEach(out::writeInt)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
