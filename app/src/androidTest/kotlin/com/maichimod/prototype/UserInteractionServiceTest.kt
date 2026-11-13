package com.maichimod.prototype

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserInteractionServiceTest {
    @Test fun readinessUpdatesVisibleSettingsAndKeysResumeAfterInterrupt() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        val idleFile = File(context.filesDir, "idle-return.json")
        val originalIdle = idleFile.takeIf { it.isFile }?.readBytes()
        val problems = mutableListOf<String>()
        try {
            assertTrue(AppSettings(context).saveIdleReturn(true, 3))
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            shell("settings delete secure enabled_accessibility_services"); shell("settings put secure accessibility_enabled 0")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<android.view.View>(R.id.settings_button).performClick() }
                val component = "${context.packageName}/${UserInteractionService::class.java.name}"
                shell("settings put secure enabled_accessibility_services $component"); shell("settings put secure accessibility_enabled 1")
                val due = SystemClock.elapsedRealtime() + 40000
                while (!UserInteractionService.isAvailable() && SystemClock.elapsedRealtime() < due) Thread.sleep(100)
                assertTrue("Detection did not bind", UserInteractionService.isAvailable())
                scenario.onActivity { activity ->
                    val hint = activity.findViewById<android.widget.TextView>(R.id.idle_return_hint)
                    if (hint.text.toString() != activity.getString(R.string.idle_return_limited)) problems.add("readiness did not refresh the visible hint")
                    // Synchronize the initial hint so the reverse transition is checked independently.
                    activity.refreshIdleSettings()
                    val service = UserInteractionService::class.java.getDeclaredField("instance").apply { isAccessible = true }.get(null) as UserInteractionService
                    service.onInterrupt()
                    if (hint.text.toString() != activity.getString(R.string.idle_authorization_required)) problems.add("interruption did not refresh the visible hint")
                    val consumed = UserInteractionService::class.java.getDeclaredMethod("onKeyEvent", android.view.KeyEvent::class.java)
                        .apply { isAccessible = true }.invoke(service, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP))
                    assertEquals(false, consumed)
                    if (!UserInteractionService.isAvailable()) problems.add("a delivered key did not resume interrupted detection")
                }
                assertEquals(emptyList<String>(), problems)
            }
        } finally {
            if (originalServices == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $originalServices")
            shell("settings put secure accessibility_enabled $originalEnabled")
            if (originalIdle == null) idleFile.delete() else idleFile.writeBytes(originalIdle)
        }
    }

    @Test fun returnKeepsTheTaskAndListenerAndExternalClickRestartsTheMinute() {
        assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
            val due = SystemClock.elapsedRealtime() + timeoutMs
            while (!condition() && SystemClock.elapsedRealtime() < due) Thread.sleep(100)
            assertTrue("Condition not met within $timeoutMs ms", condition())
        }
        fun mainIsResumed(): Boolean {
            var resumed = false
            onMain { resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity } }
            return resumed
        }
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        val idleFile = File(context.filesDir, "idle-return.json")
        val originalIdle = idleFile.takeIf { it.isFile }?.readBytes()
        val originalOptions = AppSettings(context).load()
        val originalReception = AppSettings(context).isReceptionEnabled()
        var scenario: ActivityScenario<MainActivity>? = null
        var bound = false
        var service: IntercomService? = null
        val connected = CountDownLatch(1)
        val binding = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                service = (binder as IntercomService.LocalBinder).service; connected.countDown()
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
        }
        try {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            assertTrue(AppSettings(context).save(originalOptions.copy(wired = false, simulateTimeout = false)))
            assertTrue(AppSettings(context).setReceptionEnabled(true))
            assertTrue(AppSettings(context).saveIdleReturn(true, 1))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            lateinit var originalActivity: MainActivity
            scenario.onActivity { activity ->
                originalActivity = activity
                activity.findViewById<android.view.View>(R.id.settings_button).performClick()
                activity.findViewById<android.view.View>(R.id.edit_setting).performClick()
            }
            bound = context.bindService(Intent(context, IntercomService::class.java), binding, Context.BIND_AUTO_CREATE)
            assertTrue(bound && connected.await(5, TimeUnit.SECONDS))
            val owner = requireNotNull(service).connection
            waitUntil(40000) { owner.currentState.ready }
            val key = owner.currentState.callKey
            val component = "${context.packageName}/${UserInteractionService::class.java.name}"
            val services = listOf(originalServices.takeUnless { it == "null" || it.isBlank() }, component).filterNotNull().joinToString(":")
            shell("settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled 1")
            waitUntil { UserInteractionService.isAvailable() }
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitUntil { !mainIsResumed() }
            // This is an isolated OS input/metadata test, not proof of MatePad/Mijia input coverage.
            Thread.sleep(53_000)
            automation.waitForIdle(200, 5000)
            val settingsRoot = requireNotNull(automation.rootInActiveWindow)
            fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isClickable && node.isEnabled && node.isVisibleToUser && node.className == "android.widget.LinearLayout") return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { findClickable(it) }?.let { return it }
                return null
            }
            val control = requireNotNull(findClickable(settingsRoot)) { "No external Settings row" }
            assertTrue(control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val clickedAt = SystemClock.elapsedRealtime()
            Thread.sleep(12_000)
            assertFalse("External operation failed to reset the original minute", mainIsResumed())
            // A temporary non-focusable SystemUI volume panel must not lose the external window.
            shell("input keyevent KEYCODE_VOLUME_UP")
            waitUntil(75000) { mainIsResumed() }
            assertTrue("Returned before one minute after external operation", SystemClock.elapsedRealtime() - clickedAt >= 59_000)
            scenario.onActivity { assertSame(originalActivity, it); assertTrue(it.findViewById<android.view.View>(R.id.settings_page).isShown) }
            assertNotNull("Existing edit dialog was lost", automation.rootInActiveWindow.findAccessibilityNodeInfosByText(context.getString(R.string.save_setting)).firstOrNull())
            assertSame(owner, requireNotNull(service).connection)
            assertEquals(key, owner.currentState.callKey)
            assertTrue(owner.currentState.ready)
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
            waitUntil { !UserInteractionService.isAvailable() }
            assertSame(owner, requireNotNull(service).connection)
            assertTrue(owner.currentState.enabled)
        } finally {
            if (originalServices == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $originalServices")
            shell("settings put secure accessibility_enabled $originalEnabled")
            scenario?.close()
            if (bound) context.unbindService(binding)
            if (originalIdle == null) idleFile.delete() else idleFile.writeBytes(originalIdle)
            AppSettings(context).save(originalOptions)
            AppSettings(context).setReceptionEnabled(originalReception)
            context.stopService(Intent(context, IntercomService::class.java))
        }
    }

    @Test fun operationDetectionRequestsMetadataWithoutChangingTouchMode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val installed = manager.installedAccessibilityServiceList.firstOrNull {
            it.resolveInfo.serviceInfo.name == "${context.packageName}.UserInteractionService"
        }
        assertNotNull("Operation detection service is not registered", installed)
        val info = requireNotNull(installed)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", info.resolveInfo.serviceInfo.permission)
        assertEquals(0, info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT)
        assertEquals(0, info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
        assertNotEquals(0, info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS)
    }
}
