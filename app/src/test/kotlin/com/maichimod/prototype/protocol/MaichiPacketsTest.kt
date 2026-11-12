package com.maichimod.prototype.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaichiPacketsTest {
    @Test
    fun discoveryUsesTheDoorIdentifierAndOneByteRequestMarker() {
        val request = MaichiPackets.encodeDiscovery("M00000000001")
        val expected = hex("01 4d 30 30 30 30 30 30 30 30 30 30 31") + ByteArray(22)

        assertArrayEquals(expected, request)
        val response = hex("02 4d 30 30 30 30 30 30 30 30 30 30 31") + ByteArray(22)
        assertEquals(MaichiPacket.DiscoveryReply("M00000000001"), MaichiPackets.decode(10008, response))
        assertNull(MaichiPackets.decode(10000, response))
    }

    @Test
    fun elevatorKeepsTheRoomExtensionAfterTheAdvertisedLength() {
        val expected = hex("50 45 4e 47 55 49 4e 30 06 01 01 00 00 00 20 00") + ByteArray(16) +
            "S00000000001".toByteArray(Charsets.US_ASCII) + ByteArray(30)

        assertArrayEquals(expected, MaichiPackets.encodeElevator("S00000000001"))
        val reply = hex("50 45 4e 47 55 49 4e 30 06 01 02 00 00 00 20 00") + ByteArray(16)
        assertEquals(MaichiPacket.ElevatorReply, MaichiPackets.decode(10000, reply))
        assertNull(MaichiPackets.decode(10000, reply.copyOf(31)))
    }

    @Test
    fun monitorStartAndStopUseTheCapturedFamilyAndIdentityLayout() {
        val profile = DEMO_PROFILE
        val start = MaichiPackets.encodeMonitor(MonitorCommand.OPEN, profile, DEMO_PEER_IP)
        val stop = MaichiPackets.encodeMonitor(MonitorCommand.CLOSE, profile, DEMO_PEER_IP)

        assertEquals(172, start.size)
        assertArrayEquals(hex("50 45 4e 47 55 49 4e 30 b8 00 01 00 00 00 ac 00"), start.copyOfRange(0, 16))
        assertArrayEquals("S00000000001".toByteArray(), start.copyOfRange(32, 44))
        assertArrayEquals(hex("c0 00 02 0a"), start.copyOfRange(52, 56))
        assertArrayEquals("M00000000001".toByteArray(), start.copyOfRange(56, 68))
        assertArrayEquals(hex("c0 00 02 14"), start.copyOfRange(76, 80))
        assertArrayEquals(hex("56 49 44 45 4f 41 01 00 32 00"), start.copyOfRange(80, 90))
        assertEquals(1, start[128].toInt())
        assertEquals(9, start[130].toInt())
        assertEquals(255, start[170].toInt() and 0xff)
        assertEquals(80, stop.size)
        assertArrayEquals(hex("b8 00 1e 00 00 00 50 00"), stop.copyOfRange(8, 16))
    }

    @Test
    fun rejectsMediaWithLengthOrFragmentOutsideItsHeader() {
        val packet = MaichiPackets.encodeMonitor(MonitorCommand.PROBE, DEMO_PROFILE, DEMO_PEER_IP).copyOf(94)
        packet[10] = 10
        packet[14] = 94
        hex("01 00 2a 00 01 00 01 00 04 00 ff d8 ff d9").copyInto(packet, 80)

        val media = MaichiPackets.decode(10000, packet) as MaichiPacket.Media
        assertEquals(42, media.fragment.frameNumber)
        assertArrayEquals(hex("ff d8 ff d9"), media.fragment.bytes)
        assertNull(MaichiPackets.decode(10000, packet.copyOf(93)))
        packet[86] = 2
        assertNull(MaichiPackets.decode(10000, packet))
    }

    @Test
    fun derivesBroadcastFromTheSelectedWiredAddress() {
        val profile = TerminalProfile("S00000000001", "M00000000001", ipv4Address("10.23.42.17")!!, 20)

        assertEquals("10.23.47.255", profile.broadcastAddress.hostAddress)
        assertNull(ipv4Address("192.0.2.256"))
    }
}

internal fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
