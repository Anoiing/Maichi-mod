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
        fixture.shell("cmd statusbar expand-notifications")
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.android.systemui" && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(50)
        }
        assertEquals("com.android.systemui", instrumentation.uiAutomation.rootInActiveWindow.packageName.toString())
        try {
            fixture.owner.simulateIncoming()
            awaitState(fixture.owner) { it.inCall && it.video == VideoHealth.AVAILABLE }
            val collapsedAt = android.os.SystemClock.elapsedRealtime()
            val activity = awaitCall { fixture.shell("cmd statusbar collapse") }
            assertFalse(activity.isFinishing)
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
            assertTrue(fixture.context.getSystemService(android.os.PowerManager::class.java).isInteractive)
            assertFalse(activity.isFinishing)
        } finally {
            fixture.shell("input keyevent KEYCODE_WAKEUP")
            fixture.shell("wm dismiss-keyguard")
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
        val shown = awaitCall {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activity.startActivity(IncomingCallActivity.createIntent(fixture.context, current))
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse(shown.isFinishing)
        assertEquals(current, IncomingCallActivity.readKey(shown.intent))
        assertEquals(CallStatus.RINGING, fixture.owner.currentState.call)
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
            fixture.owner.simulateIncoming()
            assertTrue(ringing.await(5, TimeUnit.SECONDS))
            val manager = fixture.context.getSystemService(NotificationManager::class.java)
            val noticeDeadline = android.os.SystemClock.elapsedRealtime() + 2000
            var notice = manager.activeNotifications.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }?.notification
            while (notice == null && android.os.SystemClock.elapsedRealtime() < noticeDeadline) {
                android.os.SystemClock.sleep(20)
                notice = manager.activeNotifications.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }?.notification
            }
            val published = requireNotNull(notice) { "Current call notification was not published" }
            val activity = awaitCall {
                val options = android.app.ActivityOptions.makeBasic().apply {
                    if (android.os.Build.VERSION.SDK_INT >= 34) setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                published.contentIntent.send(fixture.context, 0, null, null, null, null, options.toBundle())
            }
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

    private fun showExternalSettings(fixture: RunningIntercom) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val visible = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            checkNotNull(visible) { "No foreground activity to launch settings" }
            visible.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        var observed: String? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            observed = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (observed == "com.android.settings") return
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
