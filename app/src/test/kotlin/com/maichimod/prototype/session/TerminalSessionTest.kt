package com.maichimod.prototype.session

import com.maichimod.prototype.protocol.DEMO_PEER_IP
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.protocol.MaichiPackets
import com.maichimod.prototype.protocol.MonitorCommand
import com.maichimod.prototype.protocol.hex
import com.maichimod.prototype.protocol.ipv4Address
import com.maichimod.prototype.transport.DatagramTransport
import com.maichimod.prototype.transport.ReceivedDatagram
import java.io.IOException
import java.net.Inet4Address
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSessionTest {
    private val clock = TestScheduler()
    private val transport = RecordingTransport()
    private val frames = mutableListOf<ByteArray>()
    private val failures = mutableListOf<Exception>()
    private var publishedMonitor = MonitorStatus.IDLE
    private var decodeJpeg: (ByteArray) -> ByteArray? = { it }
    private val session = TerminalSession(DEMO_PROFILE, transport, clock, object : TerminalObserver<ByteArray> {
        override fun onStateChanged(state: TerminalState) { publishedMonitor = state.monitor }
        override fun decodeJpeg(jpeg: ByteArray) = this@TerminalSessionTest.decodeJpeg(jpeg)
        override fun onFrame(frame: ByteArray) {
            assertEquals(MonitorStatus.VIEWING, publishedMonitor)
            frames.add(frame)
        }
        override fun onTransportFailure(failure: Exception) { failures.add(failure) }
    })

    @Test
    fun anElevatorTapSendsOneRequestAndOnlyTheDiscoveredPeerCanRespond() {
        session.callElevator()
        session.callElevator()
        assertEquals(1, transport.sent.size)
        assertEquals("192.0.2.255", transport.sent.single().target.hostAddress)
        assertEquals(10008, transport.sent.single().port)

        session.receive(discovery("M00000000002"))
        assertEquals(1, transport.sent.size)
        session.receive(discovery())
        session.callElevator()
        assertEquals(2, transport.sent.size)
        assertArrayEquals(MaichiPackets.encodeElevator(DEMO_PROFILE.indoorId), transport.sent.last().payload)

        session.receive(elevatorReply(requireNotNull(ipv4Address("192.0.2.21"))))
        assertEquals(ElevatorStatus.WAITING, session.state.elevator)
        session.receive(elevatorReply(DEMO_PEER_IP))
        assertEquals(ElevatorStatus.RESPONDED, session.state.elevator)
        clock.advance(5_000)
        assertEquals(2, transport.sent.size)
        assertEquals(ElevatorStatus.RESPONDED, session.state.elevator)
    }

    @Test
    fun expiredDiscoveryAndMissingElevatorReplyNeverResendTheRequest() {
        session.callElevator()
        clock.advance(2_000)
        session.receive(discovery())
        assertEquals(ElevatorStatus.TIMED_OUT, session.state.elevator)
        assertEquals(1, transport.sent.size)

        session.callElevator()
        session.receive(discovery())
        clock.advance(3_000)
        assertEquals(ElevatorStatus.TIMED_OUT, session.state.elevator)
        assertEquals(listOf(10008, 10008, 10000), transport.sent.map { it.port })
        session.receive(elevatorReply(DEMO_PEER_IP))
        assertEquals(ElevatorStatus.TIMED_OUT, session.state.elevator)
    }

    @Test
    fun aConfiguredDoorStationAddressFiltersDiscoveryForMonitorAndElevator() {
        val fixedPeer = requireNotNull(ipv4Address("192.0.2.21"))
        val fixedSession = TerminalSession(DEMO_PROFILE.copy(expectedPeerAddress = fixedPeer), transport, clock,
            object : TerminalObserver<ByteArray> {
                override fun onStateChanged(state: TerminalState) = Unit
                override fun decodeJpeg(jpeg: ByteArray) = jpeg
                override fun onFrame(frame: ByteArray) = Unit
            },
        )
        fixedSession.openMonitor()
        fixedSession.callElevator()
        fixedSession.receive(discovery())
        assertEquals(listOf(MaichiPackets.DISCOVERY_PORT), transport.sent.map { it.port })
        fixedSession.receive(discovery().copy(source = fixedPeer))
        assertEquals(MonitorStatus.CONNECTING, fixedSession.state.monitor)
        assertEquals(ElevatorStatus.WAITING, fixedSession.state.elevator)
        assertEquals(listOf(fixedPeer, fixedPeer), transport.sent.drop(1).map { it.target })
    }

    @Test
    fun exitingMonitorStopsPeriodicSendsAndIgnoresLateVideo() {
        session.openMonitor()
        session.receive(discovery())
        assertEquals(172, transport.sent.last().payload.size)
        clock.advance(1_250)
        assertEquals(listOf(1, 1, 12), transport.sent.drop(1).map { it.payload[10].toInt() })
        session.receive(videoFrame())
        assertEquals(MonitorStatus.VIEWING, session.state.monitor)
        assertArrayEquals(hex("ff d8 ff d9"), frames.single())

        session.closeMonitor()
        assertEquals(0x1e, transport.sent.last().payload[10].toInt())
        val sentCount = transport.sent.size
        clock.advance(8_000)
        session.receive(videoFrame())
        assertEquals(sentCount, transport.sent.size)
        assertEquals(1, frames.size)
        assertEquals(MonitorStatus.IDLE, session.state.monitor)
    }

    @Test
    fun monitorTimeoutClosesThePeerSessionAndStopsPolling() {
        session.openMonitor()
        session.receive(discovery())
        clock.advance(5_000)

        assertEquals(MonitorStatus.TIMED_OUT, session.state.monitor)
        assertEquals(0x1e, transport.sent.last().payload[10].toInt())
        val sentCount = transport.sent.size
        clock.advance(1_000)
        assertEquals(sentCount, transport.sent.size)
    }

    @Test
    fun rejectedFramesNeverStartPlaybackOrPostponeTheFirstFrameTimeout() {
        decodeJpeg = { null }
        session.openMonitor()
        session.receive(discovery())

        repeat(4) { index ->
            clock.advance(1_000)
            session.receive(videoFrame(index + 1))
            assertEquals(MonitorStatus.CONNECTING, session.state.monitor)
        }
        clock.advance(1_000)

        assertEquals(emptyList<ByteArray>(), frames)
        assertEquals(MonitorStatus.TIMED_OUT, session.state.monitor)
    }

    @Test
    fun rejectedFramesDoNotKeepPlaybackAliveAfterAValidFrame() {
        session.openMonitor()
        session.receive(discovery())
        clock.advance(1_000)
        session.receive(videoFrame())
        assertEquals(1, frames.size)
        decodeJpeg = { null }

        repeat(4) { index ->
            clock.advance(1_000)
            session.receive(videoFrame(index + 2))
            assertEquals(MonitorStatus.VIEWING, session.state.monitor)
        }
        clock.advance(1_000)

        assertEquals(1, frames.size)
        assertEquals(MonitorStatus.TIMED_OUT, session.state.monitor)
    }

    @Test
    fun failedElevatorSendClosesTransportAndReportsFailureWithoutResending() {
        val failure = IOException("test send failure")
        session.callElevator()
        transport.failure = failure
        session.receive(discovery())
        clock.advance(8_000)
        session.callElevator()

        assertEquals(listOf(failure), failures)
        assertEquals(1, transport.closes)
        assertEquals(listOf(MaichiPackets.DISCOVERY_PORT, MaichiPackets.SESSION_PORT), transport.sent.map { it.port })
        assertEquals(ElevatorStatus.DISCONNECTED, session.state.elevator)
    }

    @Test
    fun permissionFailureDuringDiscoveryIsReportedOnceAndCancelsBothFeatures() {
        val failure = SecurityException("test socket permission failure")
        transport.failure = failure
        session.callElevator()
        session.openMonitor()
        clock.advance(8_000)

        assertEquals(listOf(failure), failures)
        assertEquals(1, transport.closes)
        assertEquals(1, transport.sent.size)
        assertEquals(TerminalState(MonitorStatus.DISCONNECTED, ElevatorStatus.DISCONNECTED), session.state)
    }

    private fun discovery(id: String = DEMO_PROFILE.peerId) = ReceivedDatagram(
        DEMO_PEER_IP, 10008, 10008,
        MaichiPackets.encodeDiscovery(id).apply { this[0] = 2 },
    )

    private fun elevatorReply(source: Inet4Address) = ReceivedDatagram(
        source, 10000, 10000,
        hex("50 45 4e 47 55 49 4e 30 06 01 02 00 00 00 20 00") + ByteArray(16),
    )

    private fun videoFrame(number: Int = 1) = ReceivedDatagram(
        DEMO_PEER_IP, 10000, 10000,
        MaichiPackets.encodeMonitor(MonitorCommand.PROBE, DEMO_PROFILE, DEMO_PEER_IP).copyOf(94).apply {
            this[10] = 10
            this[14] = 94
            hex("01 00 01 00 01 00 01 00 04 00 ff d8 ff d9").copyInto(this, 80)
            this[82] = number.toByte()
        },
    )

    private class RecordingTransport : DatagramTransport {
        data class Sent(val port: Int, val target: Inet4Address, val payload: ByteArray)
        val sent = mutableListOf<Sent>()
        var failure: Exception? = null
        var closes = 0
            private set

        override fun send(localPort: Int, destination: Inet4Address, payload: ByteArray) {
            sent.add(Sent(localPort, destination, payload))
            failure?.let { throw it }
        }

        override fun close() { closes++ }
    }

    private class TestScheduler : SessionScheduler {
        private data class Job(val time: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val jobs = mutableListOf<Job>()
        override var nowMs = 0L
            private set

        override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
            val job = Job(nowMs + delayMs, action)
            jobs.add(job)
            return Cancellation { job.cancelled = true }
        }

        fun advance(deltaMs: Long) {
            val target = nowMs + deltaMs
            while (true) {
                val job = jobs.filter { !it.cancelled && it.time <= target }.minByOrNull { it.time } ?: break
                jobs.remove(job)
                nowMs = job.time
                job.action()
            }
            nowMs = target
        }
    }
}
