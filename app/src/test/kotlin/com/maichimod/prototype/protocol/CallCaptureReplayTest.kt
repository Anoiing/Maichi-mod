package com.maichimod.prototype.protocol

import com.maichimod.prototype.transport.CaptureFixtures
import com.maichimod.prototype.transport.CaptureRow
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CallCaptureReplayTest {
    @Test fun bothRecordedCallsDecodeEveryVideoFrameAndAudioPacket() {
        val rows = fixture(CaptureFixtures.CALL_RX)
        assertEquals(4191, rows.size)
        val assembler = JpegAssembler()
        val counts = mutableListOf<Int>()
        var frames = 0
        var audio = 0
        for (row in rows) {
            val packet = CallPackets.decode(row.localPort, row.payload)
            assertNotNull("Unexpected call payload at ${row.timeMs}", packet)
            when (packet) {
                is CallPacket.Media -> {
                    if (packet.fragment.kind == MediaFragment.AUDIO) {
                        assertEquals(512, packet.fragment.bytes.size)
                        audio++
                    } else {
                        assembler.accept(packet.fragment, row.timeMs) {
                            checkNotNull(ImageIO.read(ByteArrayInputStream(it)))
                        }?.let { image -> assertEquals(640, image.width); assertEquals(480, image.height); frames++ }
                    }
                }
                is CallPacket.Signal -> if (packet.type == 30) { counts.add(frames); frames = 0; assembler.clear() }
                else -> Unit
            }
        }
        assertEquals(listOf(339, 331), counts)
        assertEquals(566, audio)
    }

    @Test fun everyRecordedOutgoingHandshakeControlAndAudioHeaderMatchesTheEncoder() {
        val rows = fixture(CaptureFixtures.CALL_TX)
        assertEquals(645, rows.size)
        val types = mutableMapOf<Int, Int>()
        for (row in rows) {
            val bytes = row.payload
            val expected = when {
                row.localPort == 10008 -> CallPackets.encodeDiscoveryReply(DEMO_PROFILE.indoorId)
                bytes[8] == 0x98.toByte() -> CallPackets.encodeRoster(DEMO_PROFILE)
                bytes[10].toInt() == 10 -> {
                    val sequence = (bytes[82].toInt() and 255) or ((bytes[83].toInt() and 255) shl 8)
                    CallPackets.encodeAudio(DEMO_PROFILE, DEMO_PEER_IP, sequence, bytes.copyOfRange(90, bytes.size))
                }
                else -> {
                    val type = bytes[10].toInt()
                    types[type] = types.getOrDefault(type, 0) + 1
                    CallPackets.encodeSignal(CallCommand.entries.single { it.type == type }, DEMO_PROFILE, DEMO_PEER_IP)
                }
            }
            assertArrayEquals("TX at ${row.timeMs}", bytes, expected)
        }
        assertEquals(mapOf(3 to 4, 5 to 2, 6 to 1, 12 to 168, 30 to 2), types)
    }

    private fun fixture(name: String): List<CaptureRow> {
        val directory = System.getProperty("maichi.fixtureDir")
        assumeTrue("Set maichiFixtureDir", directory != null)
        val file = File(directory, name)
        assumeTrue("Export the optional call captures", file.isFile)
        return file.inputStream().use(CaptureFixtures::read)
    }
}
