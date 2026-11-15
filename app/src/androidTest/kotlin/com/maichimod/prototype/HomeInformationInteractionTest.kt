package com.maichimod.prototype

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class HomeInformationInteractionTest {
    @Test fun homeInformationSettingsLiveInAppearanceAndDoNotReplaceTheReceptionOwner(): Unit = RunningIntercom().use { fixture ->
        fixture.scenario.onActivity { activity ->
            activity.findViewById<View>(R.id.settings_button).performClick()
            activity.findViewById<View>(R.id.settings_tab_appearance).performClick()
            for (name in listOf("weather_connection", "weather_connect", "weather_open", "cainiao_connection", "cainiao_connect", "cainiao_disconnect")) {
                val id = activity.resources.getIdentifier(name, "id", activity.packageName)
                val view = if (id == 0) null else activity.findViewById<View>(id)
                assertNotNull("Missing home setting: $name", view)
                assertTrue(view!!.isShown)
            }
            activity.findViewById<View>(R.id.settings_back).performClick()
            assertTrue(activity.findViewById<View>(R.id.weather_today).isShown)
            assertSame(fixture.owner, fixture.service.connection)
            assertTrue(fixture.owner.currentState.ready)
        }
    }

    @Test fun automaticHomeRefreshDoesNotWakeTheDimmedWindowOrResetIdleTime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.filesDir, "standby.json")
        val original = file.takeIf { it.exists() }?.readBytes()
        try {
            assertTrue(AppSettings(context).saveStandby(StandbyOptions(true, false)))
            RunningIntercom().use { fixture ->
                val deadline = SystemClock.elapsedRealtime() + 5000
                var focused = false
                while (!focused && SystemClock.elapsedRealtime() < deadline) {
                    fixture.scenario.onActivity { focused = it.hasWindowFocus() }
                    if (!focused) Thread.sleep(20)
                }
                assertTrue(focused)
                fixture.scenario.onActivity { activity ->
                    val policy = MainActivity::class.java.getDeclaredField("screenPolicy").apply { isAccessible = true }.get(activity) as ScreenPolicy
                    policy.interaction(SystemClock.elapsedRealtime() - 30_000)
                    activity.refreshScreenPolicy()
                    assertEquals(.05f, activity.window.attributes.screenBrightness)
                    val field = MainActivity::class.java.declaredFields.singleOrNull { it.name == "homeInfo" }
                    assertNotNull("Main activity must own the home information lifecycle", field)
                    val controller = field!!.apply { isAccessible = true }.get(activity)
                    controller.javaClass.getDeclaredMethod("refresh").invoke(controller)
                    activity.refreshScreenPolicy()
                    assertEquals(.05f, activity.window.attributes.screenBrightness)
                    assertTrue((activity.findViewById<View>(R.id.dashboard) as HomeDashboard).standbyDimmed)
                    assertSame(fixture.owner, fixture.service.connection)
                }
            }
        } finally { original?.let { file.writeBytes(it) } ?: file.delete() }
    }
}
