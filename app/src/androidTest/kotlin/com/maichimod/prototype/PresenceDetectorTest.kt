package com.maichimod.prototype

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresenceDetectorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun bundledModelAndActualCameraCanStopAndRestartOnTheEmulator() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var detector: PresenceDetector
            onMain { detector = PresenceDetector(context) }
            try {
                repeat(2) {
                    val frame = java.util.concurrent.CountDownLatch(1)
                    onMain { detector.start { result, _ -> if (result != Presence.UNKNOWN) frame.countDown() } }
                    assertTrue("Actual local model must analyze a camera frame", frame.await(20, java.util.concurrent.TimeUnit.SECONDS))
                    onMain { assertEquals(PresenceDetector.Status.RUNNING, detector.status); detector.stop() }
                }
            } finally { onMain { detector.close() } }
        }
    }

    @Test fun oneInferenceAtATimeAndIntervalsAreMeasuredOnceWithoutExtraFrameSkipping() = onMain {
        val camera = Camera()
        val detector = PresenceDetector(context, camera)
        val results = mutableListOf<Presence>()
        detector.start { result, _ -> results.add(result) }
        val first = camera.frame(0)
        assertTrue(first.analyzed)
        val busy = camera.frame(200)
        assertFalse(busy.analyzed); assertTrue(busy.closed)
        first.complete(Presence.ABSENT)
        val early = camera.frame(199)
        assertFalse(early.analyzed); assertTrue(early.closed)
        val next = camera.frame(200)
        assertTrue(next.analyzed)
        next.complete(Presence.PRESENT)
        detector.intervalMs = 2000
        assertFalse(camera.frame(2199).analyzed)
        val slow = camera.frame(2200)
        assertTrue(slow.analyzed)
        slow.complete(Presence.ABSENT)
        assertEquals(listOf(Presence.ABSENT, Presence.PRESENT, Presence.ABSENT), results)
        assertTrue(first.closed); assertTrue(next.closed); assertTrue(slow.closed)
        detector.close()
    }

    @Test fun stoppedAndPreviousSessionResultsAreClosedAndCannotReachNewSubscribers() = onMain {
        val camera = Camera()
        val detector = PresenceDetector(context, camera)
        var oldResults = 0
        var newResults = 0
        detector.start { _, _ -> oldResults++ }
        val oldSink = camera.sink!!
        val pending = camera.frame(0)
        detector.stop()
        detector.start { _, _ -> newResults++ }
        pending.complete(Presence.PRESENT)
        val staleFrame = Frame(1000)
        oldSink(staleFrame)
        assertTrue(staleFrame.closed); assertFalse(staleFrame.analyzed)
        assertEquals(0, oldResults); assertEquals(0, newResults)
        val fresh = camera.frame(0)
        fresh.complete(Presence.ABSENT)
        assertEquals(1, newResults)
        detector.close()
        val afterClose = camera.frame(200)
        assertTrue(afterClose.closed); assertFalse(afterClose.analyzed)
        assertEquals(1, newResults)
    }

    @Test fun repeatedErrorsStopWithoutRebindingAndExplicitRetryCanRestart() = onMain {
        val camera = Camera()
        val detector = PresenceDetector(context, camera)
        val results = mutableListOf<Presence>()
        detector.start { result, _ -> results.add(result) }
        repeat(3) { camera.frame(it * 200L).complete(null) }
        assertEquals(PresenceDetector.Status.UNAVAILABLE, detector.status)
        assertEquals(listOf(Presence.UNKNOWN, Presence.UNKNOWN, Presence.UNKNOWN), results)
        assertEquals(1, camera.starts)
        detector.start { _, _ -> fail("Must wait for an explicit stop/retry after error") }
        assertEquals(1, camera.starts)
        detector.stop()
        detector.start { _, _ -> }
        assertEquals(2, camera.starts)
        val retry = camera.frame(0)
        retry.complete(Presence.ABSENT)
        assertEquals(PresenceDetector.Status.RUNNING, detector.status)
        detector.close()
    }

    @Test fun cameraFailureReportsUnknownAndClosesOnlyDetectionResources() = onMain {
        val camera = Camera()
        val detector = PresenceDetector(context, camera)
        val results = mutableListOf<Presence>()
        detector.start { result, _ -> results.add(result) }
        camera.failure!!()
        assertEquals(PresenceDetector.Status.UNAVAILABLE, detector.status)
        assertEquals(listOf(Presence.UNKNOWN), results)
        assertTrue(camera.stops > 0)
        detector.close()
        assertTrue(camera.closed)
    }

    @Test fun stalledCameraTimesOutOnceAndLateFramesCannotResurrectIt() {
        val camera = Camera()
        lateinit var detector: PresenceDetector
        val unavailable = java.util.concurrent.CountDownLatch(1)
        onMain {
            detector = PresenceDetector(context, camera)
            detector.start { result, _ -> if (result == Presence.UNKNOWN) unavailable.countDown() }
        }
        try {
            assertTrue("A stalled camera must expose retry within 12 seconds", unavailable.await(12, java.util.concurrent.TimeUnit.SECONDS))
            onMain {
                assertEquals(PresenceDetector.Status.UNAVAILABLE, detector.status)
                assertEquals(1, camera.starts)
                assertTrue(camera.stops > 0)
                val late = camera.frame(20000)
                assertTrue(late.closed); assertFalse(late.analyzed)
                detector.start { _, _ -> fail("No automatic retry after timeout") }
                assertEquals(1, camera.starts)
            }
        } finally { onMain { detector.close() } }
    }

    @Test fun pendingOldInferenceCannotBlockRestartOrClearTheNewSessionsBusyFlag() = onMain {
        val camera = Camera()
        val detector = PresenceDetector(context, camera)
        var newResults = 0
        detector.start { _, _ -> fail("Old observer must be detached") }
        val pending = camera.frame(0)
        detector.stop()
        detector.start { _, _ -> newResults++ }
        val fresh = camera.frame(0)
        assertTrue("New session cannot wait for an obsolete inference", fresh.analyzed)
        pending.complete(Presence.PRESENT)
        assertEquals(0, newResults)
        val overlapping = camera.frame(200)
        assertFalse("Old completion cannot clear the current inference guard", overlapping.analyzed)
        assertTrue(overlapping.closed)
        fresh.complete(Presence.ABSENT)
        assertEquals(1, newResults)
        detector.close()
    }

    private class Camera : PresenceCamera {
        var sink: ((PresenceFrame) -> Unit)? = null
        var failure: (() -> Unit)? = null
        var starts = 0
        var stops = 0
        var closed = false
        override fun start(onFrame: (PresenceFrame) -> Unit, onFailure: () -> Unit) {
            starts++; sink = onFrame; failure = onFailure
        }
        override fun stop() { stops++ }
        override fun close() { closed = true }
        fun frame(at: Long) = Frame(at).also { sink!!(it) }
    }

    private class Frame(override val capturedAtMs: Long) : PresenceFrame {
        var analyzed = false
        var closed = false
        private var result: ((Presence?) -> Unit)? = null
        override fun detect(onResult: (Presence?) -> Unit) { analyzed = true; result = onResult }
        fun complete(presence: Presence?) { result!!(presence) }
        override fun close() { assertFalse("Frame must close once", closed); closed = true }
    }
}
