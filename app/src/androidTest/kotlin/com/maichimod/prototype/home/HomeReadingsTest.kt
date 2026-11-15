package com.maichimod.prototype.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeReadingsTest {
    @Test fun olderWeatherCannotReplaceNewerSourceDateOrRestampItFresh() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("home-weather-order-test", 0).edit().clear().commit()
        val store = HomeInfoStore(context, "home-weather-order-test")
        val readings = HomeReadings(store)
        val now = Instant.parse("2026-11-15T17:00:00Z")
        val zone = ZoneId.of("Asia/Shanghai")
        val current = listOf(WidgetField("widget_city_name", "拱墅区"), WidgetField("widget_city_date", "11月16日星期一"),
            WidgetField("widget_current_temperature_unit", "℃"), WidgetField("widget_currentweather_smallicon", description = "雾"),
            WidgetField("widget_highlow_temperature", "18 / 12"), WidgetField("small_dayOfWeek", "星期二"),
            WidgetField("small_icon", description = "小雨"), WidgetField("highlow_temperature", "14 / 12"))
        val older = current.map { when (it.name) {
            "widget_city_date" -> it.copy(text = "11月15日星期日")
            "widget_highlow_temperature" -> it.copy(text = "19 / 13")
            "small_dayOfWeek" -> it.copy(text = "星期一")
            "highlow_temperature" -> it.copy(text = "18 / 12")
            else -> it
        } }
        assertTrue(readings.acceptWeather(current, now, zone))
        val expected = readings.weather
        assertFalse(readings.acceptWeather(older, now.plusSeconds(60), zone))
        assertEquals(expected, readings.weather)
        assertEquals(expected, HomeReadings(store).weather)
    }
    @Test fun rereadingUnchangedWidgetsCannotExtendCacheLifeAndBadFieldsCannotBecomeZero() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("home-readings-test", 0).edit().clear().commit()
        val store = HomeInfoStore(context, "home-readings-test")
        val readings = HomeReadings(store)
        val now = Instant.parse("2026-11-15T16:30:00Z")
        val fields = listOf(WidgetField("tv_pkg_num", "2"), WidgetField("tv_pkg_status", "待取件"))
        assertTrue(readings.acceptCainiao(fields, now.toEpochMilli()))
        assertTrue(readings.acceptCainiao(fields, now.plusSeconds(600).toEpochMilli()))
        assertEquals(now.toEpochMilli(), readings.cainiao!!.readAtMillis)
        assertFalse(readings.acceptCainiao(fields.map { if (it.name == "tv_pkg_status") it.copy(text = "运输中") else it }, now.toEpochMilli()))
        assertEquals(2, readings.cainiao!!.counts.awaitingPickup)
        assertEquals(readings.cainiao, HomeReadings(store).cainiao)
        readings.clearCainiao()
        assertNull(HomeReadings(store).cainiao)
        val weather = listOf(WidgetField("widget_city_name", "拱墅区"), WidgetField("widget_city_date", "11月16日星期一"),
            WidgetField("widget_current_temperature_unit", "℃"), WidgetField("widget_currentweather_smallicon", description = "雾"),
            WidgetField("widget_highlow_temperature", "18 / 12"), WidgetField("small_dayOfWeek", "星期二"),
            WidgetField("small_icon", description = "小雨"), WidgetField("highlow_temperature", "14 / 12"))
        val zone = ZoneId.of("Asia/Shanghai")
        assertTrue(readings.acceptWeather(weather, now, zone))
        assertTrue(readings.acceptWeather(weather, now.plusSeconds(600), zone))
        assertEquals(now, readings.weather!!.readAt)
        assertEquals(readings.weather, HomeReadings(store).weather)
        assertFalse(readings.acceptWeather(emptyList(), now, zone))
        assertEquals("拱墅区", readings.weather!!.city)
        readings.clearWeather()
        assertNull(HomeReadings(store).weather)
    }

    @Test fun malformedPersistedDataDoesNotCrashOrInventAReading() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = HomeInfoStore(context, "home-broken-readings-test")
        store.saveText("weather.cache", "{broken")
        store.saveText("cainiao.cache", "{\"count\":-1,\"readAt\":99}")
        val readings = HomeReadings(store)
        assertNull(readings.weather)
        assertNull(readings.cainiao)
    }
}
