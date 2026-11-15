package com.maichimod.prototype

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeDashboardLayoutTest {
    @Test fun tabletPlacesWeatherBelowWeekdayAndUsesTwoThenThreeEqualCards() = onMain {
        val root = dashboard(1024, 640, 1f)
        val mijia = bounds(root, "mijia_tile")
        val cainiao = bounds(root, "cainiao_tile")
        val elevator = bounds(root, "elevator_tile")
        val monitor = bounds(root, "monitor_tile")
        val settings = bounds(root, "settings_button")
        assertTrue(mijia.right <= cainiao.left)
        assertTrue(kotlin.math.abs(mijia.width() - cainiao.width()) <= 1)
        assertEquals(mijia.top, cainiao.top)
        assertTrue(cainiao.bottom <= elevator.top)
        assertTrue(elevator.right <= monitor.left && monitor.right <= settings.left)
        assertEquals(elevator.top, monitor.top)
        assertEquals(elevator.top, settings.top)
        assertTrue(kotlin.math.abs(elevator.width() - monitor.width()) <= 1)
        assertTrue(kotlin.math.abs(settings.width() - monitor.width()) <= 1)
        assertTrue(bounds(root, "home_weekday").bottom < bounds(root, "weather_today").top)
        assertTrue(bounds(root, "weather_today").bottom <= bounds(root, "weather_tomorrow").top)
        assertTrue("Default tablet cards must fit one screen", settings.bottom <= root.height)
        assertTrue(bounds(root, "weather_tomorrow").bottom <= root.height)
    }

    @Test fun narrowAndLargeTextKeepAllFiveEntrancesSeparateAndWeatherReadable() = onMain {
        for ((width, font) in listOf(600 to 1f, 420 to 1.3f, 1024 to 1.5f)) {
            val root = dashboard(width, 900, font)
            val cards = listOf("mijia_tile", "cainiao_tile", "elevator_tile", "monitor_tile", "settings_button")
                .map { bounds(root, it) }
            for (i in cards.indices) {
                assertTrue(cards[i].width() > 0 && cards[i].height() >= dp(root.context, 48))
                for (j in 0 until i) assertFalse("Overlapping entrances at $width / $font", Rect.intersects(cards[i], cards[j]))
            }
            assertTrue(bounds(root, "home_weekday").bottom < bounds(root, "weather_today").top)
            assertTrue(bounds(root, "weather_today").bottom <= bounds(root, "weather_tomorrow").top)
        }
    }

    private fun dashboard(width: Int, height: Int, font: Float): ViewGroup {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val configured = app.createConfigurationContext(Configuration(app.resources.configuration).apply { fontScale = font })
        val context = ContextThemeWrapper(configured, R.style.Theme_Maichi)
        val root = LayoutInflater.from(context).inflate(R.layout.page_dashboard, null) as ViewGroup
        root.measure(View.MeasureSpec.makeMeasureSpec(dp(context, width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(context, height), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }
    private fun bounds(root: ViewGroup, name: String): Rect {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        val view = if (id == 0) null else root.findViewById<View>(id)
        assertNotNull("Missing home element: $name", view)
        return Rect(0, 0, view!!.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
    }
    private fun dp(context: Context, n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
