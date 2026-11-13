package com.maichimod.prototype.protocol

import java.net.Inet4Address
import java.net.InetAddress

internal enum class CallCommand(val type: Int, val length: Int) {
    NEGOTIATE(3, 96), ANSWER(5, 80), UNLOCK(6, 80), KEEP_ALIVE(12, 80), CLOSE(30, 80),
}

internal sealed interface CallPacket {
    data class Query(val indoorId: String) : CallPacket
    data object Lookup : CallPacket
    data class Signal(val type: Int, val identity: MonitorIdentity) : CallPacket
    data class Media(val fragment: MediaFragment, val identity: MonitorIdentity) : CallPacket
}

internal object CallPackets {
    private val magic = "PENGUIN0".toByteArray(Charsets.US_ASCII)
    private val format = "VIDEOA".toByteArray(Charsets.US_ASCII)

    fun encodeDiscoveryReply(indoorId: String): ByteArray {
        require(indoorId.isTerminalId('S'))
        return ByteArray(35).apply { this[0] = 2; indoorId.toByteArray().copyInto(this, 1) }
    }

    fun encodeRoster(profile: TerminalProfile): ByteArray = header(898, 0x98, 2).apply {
        this[32] = 1
        profile.indoorId.toByteArray().copyInto(this, 34)
        profile.localAddress.address.copyInto(this, 54)
    }

    fun encodeSignal(command: CallCommand, profile: TerminalProfile, peer: Inet4Address): ByteArray =
        identity(header(command.length, 0xb7, command.type), profile, peer).apply {
            if (command == CallCommand.NEGOTIATE) {
                format.copyInto(this, 80)
                this[88] = 50; this[90] = 9; this[92] = 9; this[94] = 0xff.toByte()
            }
        }

    fun encodeAudio(profile: TerminalProfile, peer: Inet4Address, sequence: Int, pcm: ByteArray): ByteArray {
        require(pcm.size == 512)
        return identity(header(602, 0xb7, 10), profile, peer).apply {
            put16(80, 3); put16(82, sequence and 0xffff)
            put16(84, 1); put16(86, 1); put16(88, pcm.size)
            pcm.copyInto(this, 90)
        }
    }

    fun decode(port: Int, bytes: ByteArray): CallPacket? {
        if (port == MaichiPackets.DISCOVERY_PORT) {
            if (bytes.size != 35 || bytes[0].toInt() != 1) return null
            return bytes.id(1, 'S')?.let { CallPacket.Query(it) }
        }
        if (port != MaichiPackets.SESSION_PORT || bytes.size < 32 ||
            !magic.indices.all { bytes[it] == magic[it] } || bytes[9].toInt() != 0 ||
            bytes.u16(14) != bytes.size
        ) return null
        val type = bytes.u16(10)
        if (bytes[8] == 0x98.toByte()) return CallPacket.Lookup.takeIf { type == 1 && bytes.size == 32 }
        if (bytes[8] != 0xb7.toByte() || bytes.size < 80) return null
        val identity = MonitorIdentity(bytes.id(56, 'S') ?: return null, bytes.ip(76),
            bytes.id(32, 'M') ?: return null, bytes.ip(52))
        return when (type) {
            1 -> CallPacket.Signal(type, identity).takeIf {
                bytes.size == 256 && format.indices.all { bytes[80 + it] == format[it] }
            }
            5, 6, 12, 30 -> CallPacket.Signal(type, identity).takeIf { bytes.size == 80 }
            10 -> {
                if (bytes.size < 90) return null
                val kind = bytes.u16(80); val total = bytes.u16(84); val index = bytes.u16(86)
                val length = bytes.u16(88)
                if (kind !in listOf(1, 3) || index !in 1..total || length == 0 || length != bytes.size - 90) return null
                CallPacket.Media(MediaFragment(kind, bytes.u16(82), total, index, bytes.copyOfRange(90, bytes.size)), identity)
            }
            else -> null
        }
    }

    private fun header(length: Int, family: Int, type: Int) = ByteArray(length).apply {
        magic.copyInto(this); this[8] = family.toByte(); put16(10, type); put16(14, length)
    }

    private fun identity(bytes: ByteArray, profile: TerminalProfile, peer: Inet4Address) = bytes.apply {
        profile.peerId.toByteArray().copyInto(this, 32); peer.address.copyInto(this, 52)
        profile.indoorId.toByteArray().copyInto(this, 56); profile.localAddress.address.copyInto(this, 76)
    }

    private fun ByteArray.put16(offset: Int, value: Int) {
        this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte()
    }
    private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
    private fun ByteArray.id(offset: Int, prefix: Char) =
        String(this, offset, 12, Charsets.US_ASCII).takeIf { it.isTerminalId(prefix) }
    private fun ByteArray.ip(offset: Int) = InetAddress.getByAddress(copyOfRange(offset, offset + 4)) as Inet4Address
}
