package com.maichimod.prototype

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StandbyInteractionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file get() = File(context.filesDir, "standby.json")
    private var original: ByteArray? = null

    @Before fun configureIsolatedStandby() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        original = file.takeIf { it.exists() }?.readBytes()
        assertTrue(AppSettings(context).saveStandby(StandbyOptions(true, false)))
    }

    @After fun restoreStandby() {
        if (android.os.Build.HARDWARE != "ranchu") return
        original?.let { file.writeBytes(it) } ?: file.delete()
    }

    private fun forceIdle(activity: MainActivity) {
        val field = MainActivity::class.java.getDeclaredField("screenPolicy").apply { isAccessible = true }
        (field.get(activity) as ScreenPolicy).interaction(SystemClock.elapsedRealtime() - 30000)
        activity.refreshScreenPolicy()
    }

    private fun awaitFocus(fixture: RunningIntercom) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var focused = false
        while (!focused && SystemClock.elapsedRealtime() < deadline) {
            fixture.scenario.onActivity { focused = it.hasWindowFocus() }
            if (!focused) SystemClock.sleep(20)
        }
        assertTrue("Main window must be focused", focused)
    }

    @Test fun dimmedHomeStopsDecorativeFramesAndTouchRestoresSystemBrightness(): Unit = RunningIntercom().use { fixture ->
        awaitFocus(fixture)
        fixture.scenario.onActivity { activity ->
            forceIdle(activity)
            val home = activity.findViewById<HomeDashboard>(R.id.dashboard)
            assertEquals(0.05f, activity.window.attributes.screenBrightness)
            assertTrue(home.standbyDimmed)
            assertFalse(home.decorativeAnimationActive)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            activity.onUserInteraction()
            assertEquals(-1f, activity.window.attributes.screenBrightness)
            assertFalse(home.standbyDimmed)
            assertTrue(fixture.owner.currentState.enabled)
            assertTrue(fixture.owner.currentState.ready)
        }
    }

    @Test fun monitorAndIncomingCallInterruptLowBrightnessWithoutReplacingReception() = RunningIntercom().use { fixture ->
        awaitFocus(fixture)
        val key = fixture.owner.currentState.callKey
        fixture.scenario.onActivity { activity ->
            forceIdle(activity)
            activity.findViewById<View>(R.id.monitor_tile).performClick()
            activity.findViewById<View>(R.id.unit_monitor).performClick()
            forceIdle(activity)
            assertEquals(-1f, activity.window.attributes.screenBrightness)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            activity.findViewById<View>(R.id.monitor_back).performClick()
            activity.findViewById<View>(R.id.sources_back).performClick()
            forceIdle(activity)
            assertEquals(0.05f, activity.window.attributes.screenBrightness)
        }
        val call = awaitIncomingCall { fixture.owner.simulateIncoming() }
        var rendered = false
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!rendered && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                rendered = call.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            }
            if (!rendered) SystemClock.sleep(20)
        }
        assertTrue("Call must bind and render its current session", rendered)
        instrumentation.runOnMainSync {
            assertEquals(-1f, call.window.attributes.screenBrightness)
            assertTrue(call.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
        assertTrue(fixture.owner.currentState.inCall)
        assertNotEquals(key, fixture.owner.currentState.callKey)
        assertSame(fixture.owner, fixture.service.connection)
    }

    @Test fun externalAppPausesPresenceButKeepsReception() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        assertTrue(AppSettings(context).saveStandby(StandbyOptions(true, true)))
        RunningIntercom().use { fixture ->
        awaitFocus(fixture)
        val detectorField = MainActivity::class.java.getDeclaredField("detector").apply { isAccessible = true }
        val deadline = SystemClock.elapsedRealtime() + 20000
        var status: PresenceDetector.Status? = null
        while (status != PresenceDetector.Status.RUNNING && SystemClock.elapsedRealtime() < deadline) {
            fixture.scenario.onActivity { status = (detectorField.get(it) as? PresenceDetector)?.status }
            if (status != PresenceDetector.Status.RUNNING) SystemClock.sleep(20)
        }
        assertEquals(PresenceDetector.Status.RUNNING, status)
        val key = fixture.owner.currentState.callKey
        lateinit var activity: MainActivity
        fixture.scenario.onActivity { activity = it; forceIdle(it) }
        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync {
            assertEquals(-1f, activity.window.attributes.screenBrightness)
            assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertEquals(PresenceDetector.Status.STOPPED, (detectorField.get(activity) as PresenceDetector).status)
        }
        assertEquals(key, fixture.owner.currentState.callKey)
        assertTrue(fixture.owner.currentState.enabled)
        assertTrue(fixture.owner.currentState.ready)
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitFocus(fixture)
        fixture.scenario.onActivity { assertEquals(-1f, it.window.attributes.screenBrightness) }
        }
    }

    @Test fun settingsChoicesPersistAndFailedSaveRestoresTheVisibleSwitch(): Unit = RunningIntercom().use { fixture ->
        fixture.scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.settings_button).performClick()
            activity.findViewById<View>(R.id.settings_tab_terminal).performClick()
            val standby = activity.findViewById<CompoundButton>(R.id.standby_enabled)
            assertTrue(standby.isChecked)
            standby.performClick()
            assertFalse(AppSettings(context).standbyOptions().enabled)
            assertFalse(activity.findViewById<CompoundButton>(R.id.presence_enabled).isEnabled)
            val blocked = File(file.path + ".new").apply { mkdirs() }
            try {
                File(blocked, "blocker").writeText("keep")
                standby.performClick()
                assertFalse(standby.isChecked)
                assertFalse(AppSettings(context).standbyOptions().enabled)
            } finally { blocked.deleteRecursively() }
        }
    }

    @Test fun editingADialogRestoresItsBrightnessAndRenewsTheIdleDeadline(): Unit = RunningIntercom().use { fixture ->
        awaitFocus(fixture)
        lateinit var settings: ConnectionSettingsView
        fixture.scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.settings_button).performClick()
            val field = MainActivity::class.java.getDeclaredField("connectionSettings").apply { isAccessible = true }
            settings = field.get(activity) as ConnectionSettingsView
            settings.restoreDialogState(Bundle().apply {
                putString("type", "FIELD"); putString("field", ConnectionField.LOCAL_IP.name)
                putString("value", "192.0.2.10")
            })
        }
        instrumentation.waitForIdleSync()
        val dialogField = ConnectionSettingsView::class.java.getDeclaredField("dialog").apply { isAccessible = true }
        var focused = false
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!focused && SystemClock.elapsedRealtime() < deadline) {
            fixture.scenario.onActivity {
                focused = (dialogField.get(settings) as android.app.AlertDialog).window!!.decorView.hasWindowFocus()
            }
            if (!focused) SystemClock.sleep(20)
        }
        assertTrue("Dialog must receive focus before input is tested", focused)
        fixture.scenario.onActivity { activity ->
            val dialog = dialogField.get(settings) as android.app.AlertDialog
            assertTrue(dialog.window!!.decorView.hasWindowFocus())
            forceIdle(activity)
            assertEquals(0.05f, dialog.window!!.attributes.screenBrightness)
            dialog.findViewById<android.widget.EditText>(R.id.field_input).setText("192.0.2.11")
            assertEquals(-1f, dialog.window!!.attributes.screenBrightness)
            assertEquals(-1f, activity.window.attributes.screenBrightness)
            assertEquals("192.0.2.11", settings.saveDialogState()?.getString("value"))
            settings.dismissDialog()
        }
    }
}
