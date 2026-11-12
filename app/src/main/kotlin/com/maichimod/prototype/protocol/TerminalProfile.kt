package com.maichimod.prototype.protocol

import java.net.Inet4Address
import java.net.InetAddress

internal data class TerminalProfile(
    val indoorId: String,
    val peerId: String,
    val localAddress: Inet4Address,
    val prefixLength: Int,
    val expectedPeerAddress: Inet4Address? = null,
) {
    init {
        require(indoorId.isTerminalId('S') && peerId.isTerminalId('M'))
        require(prefixLength in 1..30)
    }

    val broadcastAddress: Inet4Address
        get() {
            val broadcast = localAddress.address
            for (index in broadcast.indices) {
                val networkBits = (prefixLength - index * 8).coerceIn(0, 8)
                broadcast[index] = (broadcast[index].toInt() or (0xff ushr networkBits)).toByte()
            }
            return InetAddress.getByAddress(broadcast) as Inet4Address
        }
}

internal fun String.isTerminalId(prefix: Char): Boolean =
    length == 12 && first() == prefix && drop(1).all { it in '0'..'9' }

internal fun ipv4Address(text: String): Inet4Address? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val octets = ByteArray(4)
    for (index in parts.indices) {
        val part = parts[index]
        if (part.length !in 1..3 || !part.all { it in '0'..'9' }) return null
        val number = part.toInt()
        if (number > 255) return null
        octets[index] = number.toByte()
    }
    return InetAddress.getByAddress(octets) as Inet4Address
}

internal val DEMO_PROFILE = TerminalProfile(
    "S00000000001",
    "M00000000001",
    requireNotNull(ipv4Address("192.0.2.10")),
    24,
)
internal val DEMO_PEER_IP = requireNotNull(ipv4Address("192.0.2.20"))
