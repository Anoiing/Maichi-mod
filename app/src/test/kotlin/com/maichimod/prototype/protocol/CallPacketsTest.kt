package com.maichimod.prototype.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallPacketsTest {
    @Test
    fun indoorDiscoveryAndTheCallLookupAreDistinctFromActiveMonitoring() {
        val query = byteArrayOf(1) + "S00000000001".toByteArray() + ByteArray(22)
        assertEquals(CallPacket.Query("S00000000001"), CallPackets.decode(10008, query))
        val lookup = hex("50 45 4e 47 55 49 4e 30 98 00 01 00 00 00 20 00") + ByteArray(16)
        assertEquals(CallPacket.Lookup, CallPackets.decode(10000, lookup))
        assertNull(CallPackets.decode(10000, lookup.copyOf(31)))
        assertNull(CallPackets.decode(10008, query.copyOf().apply { this[0] = 2 }))
    }

    @Test
    fun callIdentityIsReadInTheOppositeOrderToActiveMonitoring() {
        val ring = packet(1, 256).apply { "VIDEOA".toByteArray().copyInto(this, 80) }
        val result = CallPackets.decode(10000, ring) as? CallPacket.Signal
        assertEquals(1, result?.type)
        assertEquals("S00000000001", result?.identity?.indoorId)
        assertEquals("192.0.2.10", result?.identity?.indoorAddress?.hostAddress)
        assertEquals("M00000000001", result?.identity?.peerId)
        assertEquals("192.0.2.20", result?.identity?.peerAddress?.hostAddress)
        assertNull(CallPackets.decode(10000, ring.copyOf().apply { this[8] = 0xb8.toByte() }))
        assertNull(CallPackets.decode(10000, ring.copyOf().apply { this[14] = 255.toByte() }))
    }

    @Test
    fun callMediaPreservesItsKindAndRejectsTruncatedOrInconsistentFragments() {
        val media = packet(10, 94).apply { hex("01 00 07 00 01 00 01 00 04 00 ff d8 ff d9").copyInto(this, 80) }
        val result = CallPackets.decode(10000, media) as? CallPacket.Media
        assertEquals(7, result?.fragment?.frameNumber)
        assertEquals(1, result?.fragment?.kind)
        assertNull(CallPackets.decode(10000, media.copyOf(93)))
        assertNull(CallPackets.decode(10000, media.copyOf().apply { this[86] = 2 }))
    }

    private fun packet(type: Int, length: Int): ByteArray = ByteArray(length).apply {
        hex("50 45 4e 47 55 49 4e 30 b7 00").copyInto(this)
        this[10] = type.toByte()
        this[14] = length.toByte()
        this[15] = (length ushr 8).toByte()
        "M00000000001".toByteArray().copyInto(this, 32)
        hex("c0 00 02 14").copyInto(this, 52)
        "S00000000001".toByteArray().copyInto(this, 56)
        hex("c0 00 02 0a").copyInto(this, 76)
    }
}
