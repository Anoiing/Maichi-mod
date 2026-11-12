package com.maichimod.prototype.protocol

import java.net.Inet4Address
import java.net.InetAddress

internal enum class MonitorCommand(val type: Int, val length: Int) {
    OPEN(0x01, 172),
    PROBE(0x01, 80),
    KEEP_ALIVE(0x0c, 80),
    CLOSE(0x1e, 80),
}

internal data class MonitorIdentity(
    val indoorId: String,
    val indoorAddress: Inet4Address,
    val peerId: String,
    val peerAddress: Inet4Address,
)

internal data class MediaFragment(
    val kind: Int,
    val frameNumber: Int,
    val total: Int,
    val index: Int,
    val bytes: ByteArray,
) {
    companion object {
        const val JPEG = 1
        const val AUDIO = 3
    }
}

internal sealed interface MaichiPacket {
    data class DiscoveryReply(val peerId: String) : MaichiPacket
    data class MonitorControl(val type: Int, val identity: MonitorIdentity) : MaichiPacket
    data class Media(val fragment: MediaFragment, val identity: MonitorIdentity) : MaichiPacket
    data object ElevatorReply : MaichiPacket
}

internal object MaichiPackets {
    const val DISCOVERY_PORT = 10008
    const val SESSION_PORT = 10000

    private val magic = "PENGUIN0".toByteArray(Charsets.US_ASCII)
    private val videoFormat = "VIDEOA".toByteArray(Charsets.US_ASCII)
    private const val MONITOR_HEADER_LENGTH = 80
    private const val MEDIA_HEADER_LENGTH = 90

    fun encodeDiscovery(peerId: String): ByteArray {
        require(peerId.isTerminalId('M'))
        return ByteArray(35).apply {
            this[0] = 1
            peerId.toByteArray(Charsets.US_ASCII).copyInto(this, 1)
        }
    }

    fun encodeMonitor(command: MonitorCommand, profile: TerminalProfile, peerIp: Inet4Address): ByteArray =
        header(command.length, 0xb8, 0x00, command.type, command.length).apply {
            profile.indoorId.toByteArray(Charsets.US_ASCII).copyInto(this, 32)
            profile.localAddress.address.copyInto(this, 52)
            profile.peerId.toByteArray(Charsets.US_ASCII).copyInto(this, 56)
            peerIp.address.copyInto(this, 76)
            if (command == MonitorCommand.OPEN) {
                videoFormat.copyInto(this, 80)
                // The VIDEOA suffix matches the monitor request in rj3.pcapng.
                this[86] = 1
                this[88] = 50
                this[128] = 1
                this[130] = 9
                this[170] = 0xff.toByte()
            }
        }

    fun encodeElevator(indoorId: String): ByteArray {
        require(indoorId.isTerminalId('S'))
        // The 42-byte room extension follows the 32-byte advertised header.
        return header(74, 0x06, 0x01, 0x01, 32).apply {
            indoorId.toByteArray(Charsets.US_ASCII).copyInto(this, 32)
        }
    }

    fun decode(localPort: Int, payload: ByteArray): MaichiPacket? {
        if (localPort == DISCOVERY_PORT) {
            if (payload.size != 35 || payload[0].toInt() != 2) return null
            val id = payload.identifier(1, 'M') ?: return null
            return MaichiPacket.DiscoveryReply(id)
        }
        if (localPort != SESSION_PORT || payload.size < 16 || !payload.startsWithAt(magic, 0)) return null
        val type = payload.u16(10)
        if (payload.u16(14) != payload.size) return null
        if (payload[8].toInt() == 0x06 && payload[9].toInt() == 0x01) {
            return MaichiPacket.ElevatorReply.takeIf { type == 0x02 && payload.size == 32 }
        }
        if ((payload[8].toInt() and 0xff) != 0xb8 || payload[9].toInt() != 0 ||
            payload.size < MONITOR_HEADER_LENGTH
        ) return null

        val identity = MonitorIdentity(
            indoorId = payload.identifier(32, 'S') ?: return null,
            indoorAddress = payload.ipv4At(52),
            peerId = payload.identifier(56, 'M') ?: return null,
            peerAddress = payload.ipv4At(76),
        )
        return when (type) {
            0x02, 0x0c, 0x1e -> MaichiPacket.MonitorControl(type, identity)
                .takeIf { payload.size == MONITOR_HEADER_LENGTH }
            0x03 -> MaichiPacket.MonitorControl(type, identity)
                .takeIf { payload.size == 92 && payload.startsWithAt(videoFormat, 80) }
            0x0a -> decodeMedia(payload, identity)
            else -> null
        }
    }

    private fun decodeMedia(payload: ByteArray, identity: MonitorIdentity): MaichiPacket.Media? {
        if (payload.size < MEDIA_HEADER_LENGTH) return null
        val kind = payload.u16(80)
        val frameNumber = payload.u16(82)
        val total = payload.u16(84)
        val index = payload.u16(86)
        val length = payload.u16(88)
        if (kind != MediaFragment.JPEG && kind != MediaFragment.AUDIO) return null
        if (index !in 1..total || length == 0 || length != payload.size - MEDIA_HEADER_LENGTH) return null
        return MaichiPacket.Media(
            MediaFragment(kind, frameNumber, total, index, payload.copyOfRange(MEDIA_HEADER_LENGTH, payload.size)),
            identity,
        )
    }

    private fun header(length: Int, family: Int, variant: Int, type: Int, declaredLength: Int): ByteArray =
        ByteArray(length).apply {
            magic.copyInto(this)
            this[8] = family.toByte()
            this[9] = variant.toByte()
            writeU16(10, type)
            writeU16(14, declaredLength)
        }

    private fun ByteArray.writeU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.startsWithAt(prefix: ByteArray, offset: Int): Boolean =
        prefix.indices.all { this[offset + it] == prefix[it] }

    private fun ByteArray.identifier(offset: Int, prefix: Char): String? =
        String(this, offset, 12, Charsets.US_ASCII).takeIf { it.isTerminalId(prefix) }

    private fun ByteArray.ipv4At(offset: Int): Inet4Address =
        InetAddress.getByAddress(copyOfRange(offset, offset + 4)) as Inet4Address
}
