package com.maichimod.prototype

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.maichimod.prototype.session.CallStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingCallInteractionTest {
    @Test fun callControlsAreVisibleInsideTheWindow() = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var visible = false
        while (!visible && android.os.SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                val window = android.graphics.Rect()
                activity.window.decorView.getWindowVisibleDisplayFrame(window)
                visible = listOf(R.id.call_back, R.id.call_action, R.id.unlock_call, R.id.call_video_frame).all {
                    val rect = android.graphics.Rect()
                    activity.findViewById<android.view.View>(it).getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0 && window.contains(rect)
                }
            }
            if (!visible) android.os.SystemClock.sleep(50)
        }
        assertTrue("call controls were not visible inside the window", visible)
    }

    @Test fun closingSystemPanelResumesIncomingOverSettingsDialog() = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fixture.scenario.onActivity {
            it.findViewById<android.view.View>(R.id.settings_button).performClick()
            it.findViewById<android.view.View>(R.id.edit_setting).performClick()
        }
        instrumentation.waitForIdleSync()
        try {
            fixture.shell("cmd statusbar expand-notifications")
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.systemui" && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertEquals("com.android.systemui", instrumentation.uiAutomation.rootInActiveWindow.packageName.toString())
            fixture.owner.simulateIncoming()
            awaitState(fixture.owner) { it.inCall && it.video == VideoHealth.AVAILABLE }
            val collapsedAt = android.os.SystemClock.elapsedRealtime()
            // The service may already have resumed the call behind SystemUI. Collapsing the
            // panel then changes window focus without a second lifecycle RESUMED event.
            val activity = awaitIncomingCall(allowExisting = true) { fixture.shell("cmd statusbar collapse") }
            var focused = false
            while (!focused && android.os.SystemClock.elapsedRealtime() - collapsedAt < 2000) {
                instrumentation.runOnMainSync { focused = activity.hasWindowFocus() }
                if (!focused) android.os.SystemClock.sleep(20)
            }
            assertFalse(activity.isFinishing)
            assertTrue("call did not gain window focus after the panel closed", focused)
            assertTrue("call waited for another state change after panel closed", android.os.SystemClock.elapsedRealtime() - collapsedAt < 2000)
        } finally { fixture.shell("cmd statusbar collapse") }
    }

    @Test fun incomingOverFocusedSettingsDialogPreservesDraft() = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fixture.scenario.onActivity {
            it.findViewById<android.view.View>(R.id.settings_button).performClick()
            it.findViewById<android.view.View>(R.id.edit_setting).performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 5000)
        val input = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("${fixture.context.packageName}:id/field_input").single()
        assertTrue(input.isFocused)
        assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "12345678")
        }))
        fixture.scenario.onActivity { assertFalse(it.hasWindowFocus()) }
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        instrumentation.runOnMainSync { activity.findViewById<android.view.View>(R.id.call_back).performClick() }
        awaitState(fixture.owner) { !it.inCall }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 5000)
        val restored = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("${fixture.context.packageName}:id/field_input").single()
        assertEquals("12345678", restored.text.toString())
    }

    @Test fun localEndReturnsToMainWithoutCreatingAnotherCallPage() = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        var created = 0
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is IncomingCallActivity && stage == Stage.CREATED) created++
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            val activity = awaitCall { fixture.owner.simulateIncoming() }
            instrumentation.runOnMainSync { activity.findViewById<android.view.View>(R.id.call_back).performClick() }
            awaitState(fixture.owner) { !it.inCall }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(300, 5000)
            instrumentation.runOnMainSync {
                assertEquals(1, created)
                assertTrue(monitor.getActivitiesInStage(Stage.RESUMED).any { it is MainActivity })
            }
        } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
    }

    @Test fun returnBeforeServiceBindingStillSubmitsHangUp() = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val returned = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var submitted = false
        val sub = fixture.owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) { if (submitted && !state.inCall) ended.countDown() }
        })
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (!submitted && activity is IncomingCallActivity && stage == Stage.CREATED) {
                submitted = true
                activity.findViewById<android.view.View>(R.id.call_back).performClick()
                returned.countDown()
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            fixture.owner.simulateIncoming()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            assertTrue("loading page returned without submitting hang up", ended.await(2, TimeUnit.SECONDS))
        } finally {
            sub.cancel()
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
        }
    }

    @Test fun lockedIncomingCallWakesAndPresentsWithoutNotificationTap() = RunningIntercom().use { fixture ->
        showExternalSettings(fixture)
        assertTrue(fixture.owner.currentState.fullScreenAllowed)
        fixture.shell("input keyevent KEYCODE_SLEEP")
        assertFalse(fixture.context.getSystemService(android.os.PowerManager::class.java).isInteractive)
        try {
            val activity = awaitCall { fixture.owner.simulateIncoming() }
            val power = fixture.context.getSystemService(android.os.PowerManager::class.java)
            val wakeDeadline = android.os.SystemClock.elapsedRealtime() + 2000
            while (!power.isInteractive && android.os.SystemClock.elapsedRealtime() < wakeDeadline) android.os.SystemClock.sleep(20)
            assertTrue("resumed call did not wake the display", power.isInteractive)
            assertFalse(activity.isFinishing)
        } finally {
            fixture.shell("input keyevent KEYCODE_WAKEUP")
            fixture.shell("wm dismiss-keyguard")
        }
    }

    @Test fun expiredInitialPresentationDoesNotWakeWhileValidationIsPending() = pendingScreenWake(reusePage = false, activeCall = false)
    @Test fun expiredReusedPresentationDoesNotWakeWhileValidationIsPending() = pendingScreenWake(reusePage = true, activeCall = false)
    @Test fun validReusedPresentationWakesOnlyAfterValidation() = pendingScreenWake(reusePage = true, activeCall = true)

    @Test fun leavingDuringValidationDoesNotRetainTheHiddenPagesSubscription() = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        val key = requireNotNull(fixture.owner.currentState.callKey)
        showExternalSettings(fixture)
        val observers = AppConnection::class.java.getDeclaredField("observers").apply { isAccessible = true }
            .get(fixture.owner) as Set<*>
        val backgroundObservers = observers.size
        val worker = AppConnection::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(fixture.owner) as android.os.Handler
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val checked = CountDownLatch(1)
        worker.post { blocked.countDown(); release.await(15, TimeUnit.SECONDS) }
        try {
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            val shown = awaitCall { instrumentation.runOnMainSync {
                activity.startActivity(IncomingCallActivity.createIntent(fixture.context, key))
            } }
            instrumentation.runOnMainSync {
                assertFalse("Call controls must wait for validation", shown.findViewById<android.view.View>(R.id.call_action).isEnabled)
            }
            showExternalSettings(fixture)
            fixture.owner.checkDeadlines { checked.countDown() }
            release.countDown()
            assertTrue("Pending validation did not complete", checked.await(3, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals("A hidden page retained its synchronous initial subscription", backgroundObservers, observers.size)
            assertEquals(key, fixture.owner.currentState.callKey)
            assertTrue(fixture.owner.currentState.inCall)
            assertEquals("com.android.settings", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        } finally { release.countDown() }
    }

    private fun pendingScreenWake(reusePage: Boolean, activeCall: Boolean) = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val power = fixture.context.getSystemService(android.os.PowerManager::class.java)
        var requested = CallKey("expired-previous-run", 1)
        if (reusePage) {
            awaitCall { fixture.owner.simulateIncoming() }
            requested = requireNotNull(fixture.owner.currentState.callKey)
        }
        showExternalSettings(fixture)
        if (reusePage && !activeCall) {
            fixture.owner.hangUpCall(requested)
            awaitState(fixture.owner) { !it.inCall }
        }
        val worker = AppConnection::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(fixture.owner) as android.os.Handler
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val presented = CountDownLatch(1)
        var shown: IncomingCallActivity? = null
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is IncomingCallActivity && stage == Stage.STARTED) {
                shown = activity
                presented.countDown()
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            // SystemUI can wake for a live full-screen notification independently of this
            // page. Disable that channel to isolate the activity's own wake permission.
            if (activeCall) setCallChannelEnabled(fixture, false)
            worker.post { blocked.countDown(); release.await(15, TimeUnit.SECONDS) }
            assertTrue("Could not queue the owner validation", blocked.await(2, TimeUnit.SECONDS))
            fixture.shell("input keyevent KEYCODE_SLEEP")
            assertFalse("The display must be off before presentation", power.isInteractive)
            fixture.context.startActivity(IncomingCallActivity.createIntent(fixture.context, requested))
            assertTrue("Call presentation was not started", presented.await(3, TimeUnit.SECONDS))
            val waitingUntil = android.os.SystemClock.elapsedRealtime() + 1500
            do {
                assertFalse("Unvalidated call presentation woke the display", power.isInteractive)
                android.os.SystemClock.sleep(20)
            } while (android.os.SystemClock.elapsedRealtime() < waitingUntil)
            release.countDown()
            val settledUntil = android.os.SystemClock.elapsedRealtime() + 4000
            var settled = false
            while (!settled && android.os.SystemClock.elapsedRealtime() < settledUntil) {
                instrumentation.runOnMainSync {
                    settled = if (activeCall) power.isInteractive && shown?.findViewById<android.view.View>(R.id.call_action)?.isEnabled == true
                        else shown?.isFinishing == true || shown?.isDestroyed == true
                }
                if (!settled) android.os.SystemClock.sleep(20)
            }
            assertTrue(if (activeCall) "Validated call did not wake and enable its controls" else "Expired presentation did not close", settled)
            if (!activeCall) assertFalse("Expired presentation woke after validation", power.isInteractive)
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
            fixture.shell("input keyevent KEYCODE_WAKEUP")
            fixture.shell("wm dismiss-keyguard")
            if (activeCall) setCallChannelEnabled(fixture, true)
        }
    }

    @Test fun disablingCallChannelWhileBackgroundedPreventsAutomaticPresentation() = RunningIntercom().use { fixture ->
        showExternalSettings(fixture)
        assertTrue(fixture.owner.currentState.fullScreenAllowed)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val presented = CountDownLatch(1)
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is IncomingCallActivity && stage == Stage.CREATED) presented.countDown()
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            setCallChannelEnabled(fixture, false)
            assertTrue("The test requires the previous foreground capability to remain cached", fixture.owner.currentState.fullScreenAllowed)
            fixture.owner.simulateIncoming()
            awaitState(fixture.owner) { it.call == CallStatus.RINGING }
            assertFalse("A disabled notification must not launch the call page", presented.await(1500, TimeUnit.MILLISECONDS))
            assertEquals("com.android.settings", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        } finally {
            setCallChannelEnabled(fixture, true)
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
        }
    }

    @Test fun backgroundOldNotificationKeepsTheCurrentCallUi() = RunningIntercom().use { fixture ->
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        val key = requireNotNull(fixture.owner.currentState.callKey)
        showExternalSettings(fixture)
        val shown = awaitCall {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.startActivity(IncomingCallActivity.createIntent(fixture.context, key.copy(runId = "obsolete"), answer = true))
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertSame(activity, shown)
        assertFalse(shown.isFinishing)
        assertEquals(key, IncomingCallActivity.readKey(shown.intent))
        assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
    }

    @Test fun backgroundOldPageAcceptsTheNextValidCallNotification() = RunningIntercom().use { fixture ->
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        val old = requireNotNull(fixture.owner.currentState.callKey)
        showExternalSettings(fixture)
        fixture.owner.hangUpCall(old)
        awaitState(fixture.owner) { !it.inCall }
        fixture.owner.simulateIncoming()
        awaitState(fixture.owner) { it.inCall && it.callKey != old }
        val current = requireNotNull(fixture.owner.currentState.callKey)
        val deliveredAt = android.os.SystemClock.elapsedRealtime()
        val shown = awaitCall {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.startActivity(IncomingCallActivity.createIntent(fixture.context, current))
            }
        }
        awaitAdoptedKey(shown, current, deliveredAt)
        assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
    }

    @Test fun staleIntentCannotReplaceTheNextCallWhileBindingIsPending() = pendingIntentOrder(false, false)
    @Test fun pendingCurrentAnswerSurvivesAStaleIntent() = pendingIntentOrder(true, false)
    @Test fun pendingCurrentAnswerSurvivesRecreationAndAStaleIntent() = pendingIntentOrder(true, true)
    @Test fun pendingAnswerSurvivesCurrentPresentation() = pendingIntentOrder(true, false, repeatCurrent = true)
    @Test fun pendingAnswerSurvivesRecreationAndCurrentPresentation() = pendingIntentOrder(true, true, repeatCurrent = true)
    @Test fun returningDuringNextCallBindingTargetsThePresentedCall() = pendingIntentOrder(false, false, finishWhileWaiting = true)

    @Test fun validatedRecreatingPageAcceptsRemoteNextCallWithoutAnotherIntent() = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        val old = requireNotNull(fixture.owner.currentState.callKey)
        awaitAdoptedKey(activity, old)
        showExternalSettings(fixture)
        val returned = awaitCall { instrumentation.runOnMainSync {
            activity.startActivity(IncomingCallActivity.createIntent(fixture.context, old))
        } }
        awaitAdoptedKey(returned, old)
        val worker = AppConnection::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(fixture.owner) as android.os.Handler
        val receive = AppConnection::class.java.getDeclaredMethod("receive", com.maichimod.prototype.transport.ReceivedDatagram::class.java).apply { isAccessible = true }
        val callback = ActivityLifecycleCallback { next, stage ->
            if (next is IncomingCallActivity && next !== returned && stage == Stage.CREATED) {
                val packet = com.maichimod.prototype.protocol.CallPackets.encodeSignal(com.maichimod.prototype.protocol.CallCommand.CLOSE,
                    com.maichimod.prototype.protocol.DEMO_PROFILE, com.maichimod.prototype.protocol.DEMO_PEER_IP)
                val ring = com.maichimod.prototype.protocol.CallPackets.encodeSignal(com.maichimod.prototype.protocol.CallCommand.NEGOTIATE,
                    com.maichimod.prototype.protocol.DEMO_PROFILE, com.maichimod.prototype.protocol.DEMO_PEER_IP).copyOf(256).apply {
                        this[10] = 1; this[14] = 0; this[15] = 1
                    }
                val query = com.maichimod.prototype.protocol.CallPackets.encodeDiscoveryReply(com.maichimod.prototype.protocol.DEMO_PROFILE.indoorId)
                    .apply { this[0] = 1 }
                val lookup = com.maichimod.prototype.protocol.CallPackets.encodeRoster(com.maichimod.prototype.protocol.DEMO_PROFILE)
                    .copyOf(32).apply { this[10] = 1; this[14] = 32; this[15] = 0 }
                worker.post {
                    for ((port, bytes) in listOf(10000 to packet, 10008 to query, 10000 to lookup, 10000 to ring)) {
                        receive.invoke(fixture.owner, com.maichimod.prototype.transport.ReceivedDatagram(
                            com.maichimod.prototype.protocol.DEMO_PEER_IP, port, port, bytes))
                    }
                }
            }
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            val recreated = awaitCall { instrumentation.runOnMainSync { returned.recreate() } }
            awaitState(fixture.owner) { it.inCall && it.callKey != old }
            awaitAdoptedKey(recreated, requireNotNull(fixture.owner.currentState.callKey))
            assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
        } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
    }

    private fun pendingIntentOrder(answerCurrent: Boolean, recreateWhileWaiting: Boolean, repeatCurrent: Boolean = false,
        finishWhileWaiting: Boolean = false) = RunningIntercom().use { fixture ->
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        val old = requireNotNull(fixture.owner.currentState.callKey)
        showExternalSettings(fixture)
        fixture.owner.hangUpCall(old)
        awaitState(fixture.owner) { !it.inCall }
        fixture.owner.simulateIncoming()
        awaitState(fixture.owner) { it.inCall && it.callKey != old }
        val current = requireNotNull(fixture.owner.currentState.callKey)
        val worker = AppConnection::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(fixture.owner) as android.os.Handler
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val passed = CountDownLatch(1)
        worker.post { blocked.countDown(); release.await(15, TimeUnit.SECONDS); passed.countDown() }
        assertTrue(blocked.await(2, TimeUnit.SECONDS))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var shown: IncomingCallActivity
        try {
            shown = awaitCall { instrumentation.runOnMainSync {
                activity.startActivity(IncomingCallActivity.createIntent(fixture.context, current, answer = answerCurrent))
            } }
            instrumentation.runOnMainSync {
                assertEquals(fixture.context.getString(R.string.reception_restoring), shown.findViewById<android.widget.TextView>(R.id.call_state).text.toString())
            }
            if (recreateWhileWaiting) shown = awaitCall { instrumentation.runOnMainSync { shown.recreate() } }
            val repeated = awaitCall { instrumentation.runOnMainSync {
                shown.startActivity(IncomingCallActivity.createIntent(fixture.context, if (repeatCurrent) current else old,
                    answer = !repeatCurrent && !answerCurrent))
            } }
            assertSame(shown, repeated)
            assertEquals("deadline check escaped the controlled wait", 1L, passed.count)
            instrumentation.runOnMainSync {
                assertFalse(shown.findViewById<android.view.View>(R.id.call_action).isEnabled)
                assertFalse(shown.findViewById<android.view.View>(R.id.unlock_call).isEnabled)
                if (finishWhileWaiting) shown.findViewById<android.view.View>(R.id.call_back).performClick()
            }
        } finally { release.countDown() }
        if (finishWhileWaiting) {
            awaitState(fixture.owner) { !it.inCall }
            assertEquals(current, fixture.owner.currentState.callKey)
            return@use
        }
        awaitAdoptedKey(shown, current)
        if (answerCurrent) awaitState(fixture.owner) { it.call == CallStatus.ANSWERED }
        else assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
    }

    @Test fun oldAnsweredAudioRetryWaitsForFreshBinding() = RunningIntercom().use { fixture ->
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        val key = requireNotNull(fixture.owner.currentState.callKey)
        fixture.owner.answerCall(key)
        awaitState(fixture.owner) { it.call == CallStatus.ANSWERED && it.playback == AudioHealth.AVAILABLE }
        val manager = fixture.context.getSystemService(android.media.AudioManager::class.java)
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener { }.build()
        val release = CountDownLatch(1)
        try {
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(focus))
            awaitState(fixture.owner) { it.playback == AudioHealth.UNAVAILABLE }
            showExternalSettings(fixture)
            val worker = AppConnection::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(fixture.owner) as android.os.Handler
            val blocked = CountDownLatch(1)
            worker.post { blocked.countDown(); release.await(15, TimeUnit.SECONDS) }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            val shown = awaitCall { InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.startActivity(IncomingCallActivity.createIntent(fixture.context, key))
            } }
            val problems = mutableListOf<String>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val retry = shown.findViewById<android.view.View>(R.id.retry_call_audio)
                assertEquals(android.view.View.VISIBLE, retry.visibility)
                if (retry.isEnabled) problems.add("audio retry remained enabled while binding")
                retry.callOnClick() // Also covers a click callback already queued before disabling.
                if (IncomingCallActivity::class.java.getDeclaredField("queuedAudioAction").apply { isAccessible = true }.get(shown) != null) {
                    problems.add("loading callback queued an audio action")
                }
            }
            release.countDown()
            assertEquals(emptyList<String>(), problems)
            val due = android.os.SystemClock.elapsedRealtime() + 2000
            var enabled = false
            while (!enabled && android.os.SystemClock.elapsedRealtime() < due) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { enabled = shown.findViewById<android.view.View>(R.id.retry_call_audio).isEnabled }
                if (!enabled) android.os.SystemClock.sleep(20)
            }
            assertTrue("audio retry did not recover after binding", enabled)
        } finally { release.countDown(); manager.abandonAudioFocusRequest(focus) }
    }

    @Test fun staleNotificationDoesNotAnswerOrDismissCurrentCall() = RunningIntercom().use { fixture ->
        val activity = awaitCall { fixture.owner.simulateIncoming() }
        val key = requireNotNull(fixture.owner.currentState.callKey)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.runOnMainSync {
                activity.startActivity(IncomingCallActivity.createIntent(fixture.context, key.copy(runId = "obsolete"), answer = true))
            }
            instrumentation.waitForIdleSync()
            assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
            instrumentation.runOnMainSync {
                assertFalse(activity.isFinishing)
                assertEquals(key, IncomingCallActivity.readKey(activity.intent))
            }
        } finally { fixture.owner.hangUpCall(key) }
    }

    @Test fun callOverExternalAppReturnsToExternalApp() = RunningIntercom().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        showExternalSettings(fixture)
        val ringing = CountDownLatch(1)
        val sub = fixture.owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) { if (state.call == CallStatus.RINGING) ringing.countDown() }
        })
        try {
            val activity = awaitCall { fixture.owner.simulateIncoming() }
            assertTrue(ringing.await(5, TimeUnit.SECONDS))
            val manager = fixture.context.getSystemService(NotificationManager::class.java)
            val noticeDeadline = android.os.SystemClock.elapsedRealtime() + 2000
            var notice = manager.activeNotifications.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }?.notification
            while (notice == null && android.os.SystemClock.elapsedRealtime() < noticeDeadline) {
                android.os.SystemClock.sleep(20)
                notice = manager.activeNotifications.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }?.notification
            }
            requireNotNull(notice) { "Current call notification was not published" }
            instrumentation.runOnMainSync { activity.findViewById<android.view.View>(R.id.call_back).performClick() }
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            var foreground: String? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                foreground = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                if (foreground == "com.android.settings") break
                android.os.SystemClock.sleep(50)
            }
            var resumed = ""
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .joinToString { it.javaClass.simpleName + ":" + it.taskId + ":focus=" + it.hasWindowFocus() }
            }
            assertEquals("return window=$resumed call=${fixture.owner.currentState.call}", "com.android.settings", foreground)
        } finally { sub.cancel() }
    }

    @Test fun lockedBootThemeDoesNotReadAppearancePreferences() = RunningIntercom().use { fixture ->
        var reads = 0
        val context = object : ContextWrapper(fixture.context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                reads++
                error("Credential preferences must not be read while locked")
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { IncomingCallActivity.applyCallTheme(context, false) }
        assertEquals(0, reads)
    }

    private fun awaitCall(action: () -> Unit) = awaitIncomingCall(action = action)

    private fun setCallChannelEnabled(fixture: RunningIntercom, enabled: Boolean) {
        fixture.shell("am start -a android.settings.CHANNEL_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE ${fixture.context.packageName} --es android.provider.extra.CHANNEL_ID ${IntercomService.CALL_CHANNEL}")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var clicked = false
        val manager = fixture.context.getSystemService(NotificationManager::class.java)
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() == "com.android.settings") {
                val toggle = root.findAccessibilityNodeInfosByViewId("android:id/switch_widget").firstOrNull()
                if (toggle != null) {
                    if (toggle.isChecked != enabled && !clicked) {
                        var target: android.view.accessibility.AccessibilityNodeInfo = toggle
                        while (!target.isClickable) target = target.parent ?: break
                        check(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                        clicked = true
                    }
                    val actual = manager.getNotificationChannel(IntercomService.CALL_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
                    if (actual == enabled) return
                }
            }
            android.os.SystemClock.sleep(50)
        }
        error("Call channel did not become enabled=$enabled")
    }

    private fun awaitAdoptedKey(activity: IncomingCallActivity, expected: CallKey,
        deliveredAt: Long = android.os.SystemClock.elapsedRealtime()) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var observed: CallKey? = null
        var finishing = false
        var ready = false
        do {
            instrumentation.runOnMainSync {
                observed = IncomingCallActivity.readKey(activity.intent); finishing = activity.isFinishing
                ready = activity.findViewById<android.view.View>(R.id.call_action).isEnabled
            }
            if (observed == expected && !finishing && ready) return
            android.os.SystemClock.sleep(20)
        } while (android.os.SystemClock.elapsedRealtime() - deliveredAt < 2000)
        assertFalse("Current call activity was dismissed", finishing)
        assertEquals("Current call key not adopted within 2 seconds", expected, observed)
        assertTrue("Current call controls not ready within 2 seconds", ready)
    }

    private fun showExternalSettings(fixture: RunningIntercom) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var previous: Activity? = null
        instrumentation.runOnMainSync {
            val visible = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            checkNotNull(visible) { "No foreground activity to launch settings" }
            previous = visible
            visible.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var observed: String? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            observed = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            var stopped = false
            instrumentation.runOnMainSync { stopped = previous in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED) }
            if (observed == "com.android.settings" && stopped) return
            android.os.SystemClock.sleep(50)
        }
        error("External settings not foreground: package=$observed call=${fixture.owner.currentState.call}")
    }

    private fun awaitState(owner: AppConnection, predicate: (IntercomState) -> Boolean) {
        val ready = CountDownLatch(1)
        val sub = owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) { if (predicate(state)) ready.countDown() }
        })
        try { assertTrue(ready.await(5, TimeUnit.SECONDS)) } finally { sub.cancel() }
    }
}
