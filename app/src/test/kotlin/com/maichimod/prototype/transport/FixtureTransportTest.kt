package com.maichimod.prototype.transport

import com.maichimod.prototype.protocol.DEMO_PEER_IP
import com.maichimod.prototype.protocol.DEMO_PROFILE
import com.maichimod.prototype.protocol.MaichiPackets
import com.maichimod.prototype.protocol.MonitorCommand
import com.maichimod.prototype.protocol.hex
import com.maichimod.prototype.session.Cancellation
import com.maichimod.prototype.session.ElevatorStatus
import com.maichimod.prototype.session.MonitorStatus
import com.maichimod.prototype.session.SessionScheduler
import com.maichimod.prototype.session.TerminalObserver
import com.maichimod.prototype.session.TerminalSession
import com.maichimod.prototype.session.TerminalState
import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureTransportTest {
    @Test
    fun changingMonitorCancelsOldFramesAndKeepsAnIndependentElevatorReply() {
        val clock = TestScheduler()
        val frames = mutableListOf<ByteArray>()
        val discovery = row(0, 10008, MaichiPackets.encodeDiscovery(DEMO_PROFILE.peerId).apply { this[0] = 2 })
        val video = MaichiPackets.encodeMonitor(MonitorCommand.PROBE, DEMO_PROFILE, DEMO_PEER_IP).copyOf(94).apply {
            this[10] = 10
            this[14] = 94
            hex("01 00 01 00 01 00 01 00 04 00 ff d8 ff d9").copyInto(this, 80)
        }
        val busy = MaichiPackets.encodeMonitor(MonitorCommand.PROBE, DEMO_PROFILE, DEMO_PEER_IP).apply {
            this[10] = 2
            this[14] = 172.toByte()
        }
        val elevator = row(0, 10000, hex("50 45 4e 47 55 49 4e 30 06 01 02 00 00 00 20 00") + ByteArray(16))
        lateinit var session: TerminalSession<ByteArray>
        val transport = FixtureTransport(clock, listOf(discovery, row(100, 10000, video), row(800, 10000, video)), elevator) {
            session.receive(it)
        }
        session = TerminalSession(DEMO_PROFILE, transport, clock, object : TerminalObserver<ByteArray> {
            override fun onStateChanged(state: TerminalState) = Unit
            override fun decodeJpeg(jpeg: ByteArray) = jpeg
            override fun onFrame(frame: ByteArray) { frames.add(frame) }
        })
        session.openMonitor()
        clock.advance(150)
        assertEquals(MonitorStatus.VIEWING, session.state.monitor)
        assertEquals(1, frames.size)
        session.closeMonitor()
        session.callElevator()
        transport.selectMonitor(listOf(discovery, row(100, 10000, busy)))
        session.openMonitor()
        clock.advance(150)
        assertEquals(MonitorStatus.BUSY, session.state.monitor)
        assertEquals(ElevatorStatus.WAITING, session.state.elevator)
        clock.advance(1_000)
        assertEquals(MonitorStatus.BUSY, session.state.monitor)
        assertEquals(ElevatorStatus.RESPONDED, session.state.elevator)
        assertEquals(1, frames.size)
        session.close()
    }

    private fun row(timeMs: Long, port: Int, payload: ByteArray) =
        CaptureRow(timeMs, true, port, DEMO_PEER_IP, port, payload)

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
