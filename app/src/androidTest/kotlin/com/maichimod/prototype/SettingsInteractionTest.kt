package com.maichimod.prototype

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsInteractionTest {
    @Test fun idleSaveFailureAndInvalidMinutesKeepTheDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var options = IdleReturnOptions()
        var accept = false
        var saves = 0
        lateinit var settings: ConnectionSettingsView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val page = activity.findViewById<android.view.View>(R.id.settings_page)
                settings = ConnectionSettingsView(page, { ConnectionOptions() }, { _, done -> done(false) }, {},
                    currentIdleOptions = { options }, onSaveIdleReturn = { updated, done ->
                        saves++; if (accept) options = updated; done(accept)
                    })
                settings.renderIdleReturn(options, authorized = false, returnFailed = false)
                val toggle = page.findViewById<android.widget.CompoundButton>(R.id.idle_return_enabled)
                assertTrue(toggle.isChecked)
                toggle.performClick()
                assertTrue("Failed save must restore the saved choice", toggle.isChecked)
                assertEquals(1, saves)
                assertTrue(page.findViewById<android.widget.TextView>(R.id.idle_return_hint).text.toString()
                    .contains(activity.getString(R.string.idle_authorization_required)))
                settings.restoreDialogState(Bundle().apply { putString("type", "IDLE"); putString("value", "0") })
            }
            fun save() {
                instrumentation.waitForIdleSync()
                val automation = instrumentation.uiAutomation
                automation.waitForIdle(200, 5000)
                assertTrue(automation.rootInActiveWindow.findAccessibilityNodeInfosByText("保存")
                    .first { it.isClickable }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
            }
            save()
            scenario.onActivity {
                assertEquals(1, saves)
                assertEquals("0", settings.saveDialogState()?.getString("value"))
                assertTrue(settings.saveDialogState()!!.getBoolean("error"))
                settings.restoreDialogState(Bundle().apply {
                    putString("type", "IDLE"); putString("value", "8")
                    putInt("selection_start", 0); putInt("selection_end", 1)
                })
            }
            save()
            scenario.onActivity {
                val draft = requireNotNull(settings.saveDialogState())
                assertEquals("8", draft.getString("value"))
                assertTrue(draft.getBoolean("error"))
                settings.dismissDialog(); settings.restoreDialogState(draft)
                assertEquals("8", settings.saveDialogState()?.getString("value"))
                accept = true
            }
            save()
            scenario.onActivity {
                assertEquals(8, options.minutes)
                assertNull(settings.saveDialogState())
            }
        }
    }

    @Test fun networkRecoveryExplanationAndPermissionGuidanceAreVisibleTogether() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val page = activity.findViewById<android.view.View>(R.id.settings_page)
                val settings = ConnectionSettingsView(page, { wiredTestOptions() }, { _, done -> done(false) }, {})
                val message = activity.getString(R.string.wired_address_delayed)
                val state = IntercomState(options = wiredTestOptions(), configurationLoaded = true,
                    link = LinkStatus.WIRED_ADDRESS_PENDING, issue = message, notificationsAllowed = false)
                settings.renderReception(state, true)
                val hint = page.findViewById<android.widget.TextView>(R.id.reception_hint)
                assertTrue(hint.text.toString().contains(message))
                assertTrue(hint.text.toString().contains(activity.getString(R.string.call_notifications_blocked)))
                settings.renderReception(state.copy(link = LinkStatus.WIRED_RECONNECTING, issue = null,
                    retryAtMs = android.os.SystemClock.elapsedRealtime() + 5000), true)
                assertTrue(hint.text.toString().contains(activity.getString(R.string.wired_retry_after, 5L)))
            }
        }
    }

    @Before fun prepareIsolatedEmulator() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu" || android.os.Build.HARDWARE == "goldfish")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (command in listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard")) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    @Test fun saveFailureKeepsDialogDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var settings: ConnectionSettingsView
            var saves = 0
            scenario.onActivity { activity ->
                settings = ConnectionSettingsView(activity.findViewById(R.id.settings_page), { ConnectionOptions() },
                    { _, complete -> saves++; complete(false) }, {})
                settings.restoreDialogState(Bundle().apply {
                    putString("field", "INDOOR_ID")
                    putString("value", "00000000023")
                })
            }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(300, 5000)
            val save = instrumentation.uiAutomation.rootInActiveWindow
                .findAccessibilityNodeInfosByText("保存").first { it.isClickable }
            assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(1, saves)
                assertEquals("00000000023", settings.saveDialogState()?.getString("value"))
                settings.dismissDialog()
            }
        }
    }
}
