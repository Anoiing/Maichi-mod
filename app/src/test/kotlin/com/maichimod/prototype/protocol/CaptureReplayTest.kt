package com.maichimod.prototype.protocol

import com.maichimod.prototype.transport.CaptureFixtures
import com.maichimod.prototype.transport.CaptureRow
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class CaptureReplayTest {
    @Test
    fun generatedControlPacketsMatchTheRecordedMonitorAndElevatorRequests() {
        val monitor = fixture(CaptureFixtures.MONITOR_TX)
        assertEquals(44, monitor.size)
        assertArrayEquals(MaichiPackets.encodeDiscovery(DEMO_PROFILE.peerId), monitor[0].payload)
        val commands = listOf(MonitorCommand.OPEN, MonitorCommand.PROBE) +
            List(40) { MonitorCommand.KEEP_ALIVE } + MonitorCommand.CLOSE
        commands.forEachIndexed { index, command ->
            assertArrayEquals(MaichiPackets.encodeMonitor(command, DEMO_PROFILE, DEMO_PEER_IP), monitor[index + 1].payload)
        }
        val elevator = fixture(CaptureFixtures.ELEVATOR_TX)
        assertEquals(2, elevator.size)
        assertArrayEquals(monitor[0].payload, elevator[0].payload)
        assertArrayEquals(MaichiPackets.encodeElevator(DEMO_PROFILE.indoorId), elevator[1].payload)
        val reply = fixture(CaptureFixtures.ELEVATOR_RX).single { it.localPort == 10000 }
        assertEquals(MaichiPacket.ElevatorReply, MaichiPackets.decode(reply.localPort, reply.payload))
    }

    @Test
    fun reassemblesAndDecodesEveryRecordedFrameFromBothMonitorCaptures() {
        verifyFrames(CaptureFixtures.MONITOR_RX, 608, 703)
        verifyFrames(CaptureFixtures.MONITOR_RJ4_RX, 168, 184)
    }

    private fun verifyFrames(file: String, expectedFrames: Int, expectedAudioPackets: Int) {
        val assembler = JpegAssembler()
        var frames = 0
        var audioPackets = 0
        for (row in fixture(file)) {
            val decoded = MaichiPackets.decode(row.localPort, row.payload)
            assertNotNull("Unexpected payload in $file at ${row.timeMs} ms", decoded)
            if (decoded !is MaichiPacket.Media) continue
            if (decoded.fragment.kind == MediaFragment.AUDIO) {
                audioPackets++
                continue
            }
            val image = assembler.accept(decoded.fragment, row.timeMs) { jpeg ->
                checkNotNull(ImageIO.read(ByteArrayInputStream(jpeg))) { "Undecodable JPEG in $file at ${row.timeMs} ms" }
            } ?: continue
            assertEquals(640, image.width)
            assertEquals(480, image.height)
            frames++
        }
        assertEquals(expectedFrames, frames)
        assertEquals(expectedAudioPackets, audioPackets)
    }

    private fun fixture(name: String): List<CaptureRow> {
        val directory = System.getProperty("maichi.fixtureDir")
        assumeTrue("Set maichiFixtureDir to verify the local captures", directory != null)
        return File(checkNotNull(directory), name).inputStream().use(CaptureFixtures::read)
    }
}
