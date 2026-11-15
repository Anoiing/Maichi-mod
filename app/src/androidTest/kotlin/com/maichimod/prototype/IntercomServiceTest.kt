package com.maichimod.prototype

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maichimod.prototype.session.CallStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntercomServiceTest {
    @Test fun answeredCallSurvivesUiRecreationAndZeroBindings() = RunningIntercom().use { fixture ->
        val owner = fixture.owner
        val ringing = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val sub = owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) {
                if (state.call == CallStatus.RINGING) ringing.countDown()
                if (state.call == CallStatus.ANSWERED) answered.countDown()
                if (state.call == CallStatus.ENDED) ended.countDown()
            }
        })
        try {
            owner.simulateIncoming()
            assertTrue(ringing.await(5, TimeUnit.SECONDS))
            val key = requireNotNull(owner.currentState.callKey)
            owner.answerCall(key)
            assertTrue(answered.await(2, TimeUnit.SECONDS))
            val activity = awaitIncomingCall(allowExisting = true)
            val recreated = awaitIncomingCall { InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.recreate() } }
            assertNotSame(activity, recreated)
            assertEquals(key, owner.currentState.callKey)
            fixture.shell("input keyevent KEYCODE_HOME")
            fixture.scenario.close()
            fixture.unbind()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(CallStatus.ANSWERED, owner.currentState.call)
            assertEquals(key, owner.currentState.callKey)
            assertTrue(owner.currentState.ready)
            owner.hangUpCall(key)
            assertTrue(ended.await(2, TimeUnit.SECONDS))
            assertTrue(owner.currentState.enabled)
            assertTrue(owner.currentState.ready)
        } finally { sub.cancel() }
    }

    @Test fun allPagesAndExternalAppsKeepSameListener() = RunningIntercom().use { fixture ->
        val owner = fixture.owner
        val key = requireNotNull(owner.currentState.callKey)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalTheme = Appearance.current(fixture.context)
        fun click(id: Int) {
            fixture.scenario.onActivity {
                val view = it.findViewById<android.view.View>(id)
                assertTrue(view.isShown)
                assertTrue(view.isEnabled)
                view.performClick()
            }
            assertEquals(key, owner.currentState.callKey)
        }
        try {
            click(R.id.monitor_tile)
            for (source in MonitorSource.entries) {
                click(source.tile)
                click(R.id.monitor_back)
            }
            click(R.id.sources_back)
            click(R.id.settings_button)
            for (id in listOf(R.id.settings_tab_devices, R.id.settings_tab_terminal, R.id.settings_tab_sound, R.id.settings_tab_appearance)) click(id)
            click(R.id.settings_tab_devices)
            click(R.id.edit_setting)
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(200, 5000)
            val cancel = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(fixture.context.getString(R.string.cancel_setting)).first { it.isClickable }
            assertTrue(cancel.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            click(R.id.settings_tab_terminal)
            for (theme in ThemeColor.entries) {
                Appearance.save(fixture.context, theme)
                fixture.scenario.recreate()
                fixture.scenario.onActivity {
                    assertEquals(R.id.settings_tab_terminal, it.findViewById<android.widget.RadioGroup>(R.id.settings_tabs).checkedRadioButtonId)
                }
                assertSame(owner, fixture.service.connection)
                assertEquals(key, owner.currentState.callKey)
            }
            fixture.context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            assertEquals(key, owner.currentState.callKey)
            assertTrue(owner.currentState.enabled)
            fixture.shell("input keyevent KEYCODE_SLEEP")
            assertEquals(key, owner.currentState.callKey)
            fixture.shell("input keyevent KEYCODE_WAKEUP")
            fixture.shell("wm dismiss-keyguard")
            fixture.context.startActivity(Intent(fixture.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            instrumentation.waitForIdleSync()
            assertEquals(key, owner.currentState.callKey)
        } finally { Appearance.save(fixture.context, originalTheme) }
    }

    @Test fun noBoundUiStillReceivesIncomingCall() = RunningIntercom().use { fixture ->
        val owner = fixture.owner
        val ringing = CountDownLatch(1)
        val subscription = owner.observe(object : IntercomObserver {
            override fun onState(state: IntercomState) { if (state.call == CallStatus.RINGING) ringing.countDown() }
        })
        try {
            fixture.scenario.close()
            fixture.unbind()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            owner.simulateIncoming()
            assertTrue(ringing.await(5, TimeUnit.SECONDS))
            assertTrue(owner.currentState.enabled)
            assertEquals(CallStatus.RINGING, owner.currentState.call)
            assertEquals(AudioHealth.INACTIVE, owner.currentState.microphone)
            owner.hangUpCall(requireNotNull(owner.currentState.callKey))
        } finally { subscription.cancel() }
    }
}
