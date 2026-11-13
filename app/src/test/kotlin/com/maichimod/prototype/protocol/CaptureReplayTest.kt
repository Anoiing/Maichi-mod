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

    @Test
    fun garageCapturesIdentifyBusyWithoutMistakingNormalAcknowledgementsAndDecodeEveryFrame() {
        val directory = System.getProperty("maichi.fixtureDir")
        assumeTrue("Set maichiFixtureDir to verify the local captures", directory != null)
        assumeTrue("Export the optional garage busy capture", File(directory, CaptureFixtures.BASEMENT_ONE_BUSY_RX).isFile)
        assumeTrue("Export both garage stations", File(directory, CaptureFixtures.BASEMENT_TWO_RX).isFile)
        val busyRows = fixture(CaptureFixtures.BASEMENT_ONE_BUSY_RX)
        assertEquals(2, busyRows.size)
        assertEquals(1, busyRows.count { MaichiPackets.decode(it.localPort, it.payload) is MaichiPacket.MonitorBusy })
        assertEquals(2022, fixture(CaptureFixtures.BASEMENT_TWO_RX).size)
        verifyFrames(CaptureFixtures.BASEMENT_TWO_RX, 139, 150)
    }

    @Test
    fun successfulGarageOneMonitoringDecodesEveryFrame() {
        val directory = System.getProperty("maichi.fixtureDir")
        assumeTrue("Set maichiFixtureDir to verify the local captures", directory != null)
        assumeTrue("Export garage 1", File(directory, CaptureFixtures.BASEMENT_ONE_RX).isFile)
        val rows = fixture(CaptureFixtures.BASEMENT_ONE_RX)
        assumeTrue("Export the successful garage 1 video instead of the original busy-only capture", rows.size != 2)
        assertEquals(11213, rows.size)
        verifyFrames(CaptureFixtures.BASEMENT_ONE_RX, 743, 859)
    }

    @Test
    fun garageOneRequestsMatchTheExistingMonitorEncoder() {
        val directory = System.getProperty("maichi.fixtureDir")
        assumeTrue("Set maichiFixtureDir to verify the local captures", directory != null)
        assumeTrue("Export the outgoing garage capture", File(directory, CaptureFixtures.BASEMENT_ONE_TX).isFile)
        val one = fixture(CaptureFixtures.BASEMENT_ONE_TX)
        assertEquals(49, one.size)
        assertArrayEquals(MaichiPackets.encodeDiscovery(DEMO_PROFILE.peerId), one[0].payload)
        val commands = listOf(MonitorCommand.OPEN, MonitorCommand.PROBE) +
            List(45) { MonitorCommand.KEEP_ALIVE } + MonitorCommand.CLOSE
        commands.forEachIndexed { index, command ->
            assertArrayEquals(MaichiPackets.encodeMonitor(command, DEMO_PROFILE, DEMO_PEER_IP), one[index + 1].payload)
        }
    }

    private fun fixture(name: String): List<CaptureRow> {
        val directory = System.getProperty("maichi.fixtureDir")
        assumeTrue("Set maichiFixtureDir to verify the local captures", directory != null)
        return File(checkNotNull(directory), name).inputStream().use(CaptureFixtures::read)
    }
}
