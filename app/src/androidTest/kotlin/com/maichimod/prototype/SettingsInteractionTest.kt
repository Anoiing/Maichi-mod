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
