package com.maichimod.prototype.home

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HomeInformationTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-11-15T16:30:00Z")

    @Test fun aPriorDaySourceIsMarkedStaleEvenWhenItsRemainingForecastIncludesToday() {
        val old = fields().map { when (it.name) {
            "widget_city_date" -> it.copy(text = "11月15日星期日")
            "small_dayOfWeek" -> it.copy(text = "星期一")
            else -> it
        } }
        val selected = selectWeather(parseSystemWeather(old, now, zone), now, zone)
        assertNotNull(selected.today)
        assertTrue("Source date is old even though it was just read", selected.stale)
    }

    @Test fun vendorRefreshIsLimitedToVerifiedVersionAndRespectsForegroundAndReturnCooldowns() {
        val time = now.toEpochMilli()
        assertTrue(shouldRefreshCainiao("8.11.1105", 478, true, time, 0, false))
        assertFalse(shouldRefreshCainiao("8.11.1106", 479, true, time, 0, false))
        assertFalse(shouldRefreshCainiao("8.11.1105", 478, false, time, 0, false))
        assertFalse(shouldRefreshCainiao("8.11.1105", 478, true, time, time - 1_799_999, false))
        assertTrue(shouldRefreshCainiao("8.11.1105", 478, true, time, time - 1_800_000, false))
        assertFalse(shouldRefreshCainiao("8.11.1105", 478, true, time, time - 29_999, true))
        assertTrue(shouldRefreshCainiao("8.11.1105", 478, true, time, time - 30_000, true))
    }

    @Test fun clockCorrectionRecoversScheduledRefreshAndRestartsItsCooldown() {
        val corrected = now.toEpochMilli()
        val future = now.plusSeconds(7 * 86400).toEpochMilli()
        assertTrue("A persisted future timestamp must not block refresh for seven days",
            shouldRefreshCainiao("8.11.1105", 478, true, corrected, future, false))
        // The controller persists the corrected time before sending the recovery refresh.
        assertFalse(shouldRefreshCainiao("8.11.1105", 478, true, corrected, corrected, false))
        assertFalse(shouldRefreshCainiao("8.11.1105", 478, true, corrected + 1_799_999, corrected, false))
        assertTrue(shouldRefreshCainiao("8.11.1105", 478, true, corrected + 1_800_000, corrected, false))
    }

    @Test fun clockCorrectionRecoversReturnRefreshWithoutBypassingVisibilityOrVersion() {
        val corrected = now.toEpochMilli()
        val future = corrected + 1000
        assertTrue(shouldRefreshCainiao("8.11.1105", 478, true, corrected, future, true))
        assertFalse(shouldRefreshCainiao("8.11.1105", 478, true, corrected + 29_999, corrected, true))
        assertTrue(shouldRefreshCainiao("8.11.1105", 478, true, corrected + 30_000, corrected, true))
        assertFalse(shouldRefreshCainiao("8.11.1105", 478, false, corrected, future, true))
        assertFalse(shouldRefreshCainiao("8.11.1106", 479, true, corrected, future, true))
    }

    @Test fun countsRequirePickupSemanticsAndNeverConvertUnknownToZero() {
        assertEquals(CainiaoCounts(2, 1), parseCainiaoCounts("待取件", "2", "还有1个包裹在路上"))
        assertEquals(CainiaoCounts(0, null), parseCainiaoCounts("待取件", "0", null))
        for ((status, number) in listOf("运输中" to "1", "请登录" to "0", "待取件" to "-1", "待取件" to "99999999", "" to "2", "待取件" to "2个")) {
            assertNull("$status / $number", parseCainiaoCounts(status, number, null))
        }
        assertNull(parseCainiaoCounts(null, null, null))
        assertEquals(CainiaoCounts(2, null), parseCainiaoCounts("待取件", "2", "取件码 123456"))
    }

    @Test fun weatherUsesCardDateAndFutureWeekdayRatherThanReadTimeOrArrayPosition() {
        val snapshot = parseSystemWeather(fields(), now, zone)!!
        assertEquals("拱墅区", snapshot.city)
        val selected = selectWeather(snapshot, now, zone)
        assertEquals(LocalDate.of(2026, 11, 16), selected.today!!.date)
        assertEquals("雾", selected.today!!.text)
        assertEquals(12, selected.today!!.minC)
        assertEquals(18, selected.today!!.maxC)
        assertEquals(LocalDate.of(2026, 11, 17), selected.tomorrow!!.date)
        assertEquals("小雨", selected.tomorrow!!.text)
        assertEquals(14, selected.tomorrow!!.maxC)
        val nextDay = selectWeather(snapshot, Instant.parse("2026-11-16T16:01:00Z"), zone)
        assertEquals("小雨", nextDay.today!!.text)
        assertNull("Missing Wednesday must not reuse Tuesday", nextDay.tomorrow)
    }

    @Test fun malformedTemperatureWrongDateAndAmbiguousFutureRowsAreNotInvented() {
        assertNull(parseSystemWeather(fields().map { if (it.name == "widget_highlow_temperature") it.copy(text = "12 / 18") else it }, now, zone))
        assertNull(parseSystemWeather(fields().map { if (it.name == "widget_city_date") it.copy(text = "11月16日星期日") else it }, now, zone))
        assertNull(parseSystemWeather(fields().map { if (it.name == "widget_current_temperature_unit") it.copy(text = "℉") else it }, now, zone))
        val duplicate = fields() + fields().filter { it.name.startsWith("small_") || it.name == "highlow_temperature" }
        assertNull(selectWeather(parseSystemWeather(duplicate, now, zone), now, zone).tomorrow)
        val badTomorrow = fields().map { if (it.name == "highlow_temperature") it.copy(text = "99 / 12") else it }
        val state = selectWeather(parseSystemWeather(badTomorrow, now, zone), now, zone)
        assertNotNull(state.today)
        assertNull(state.tomorrow)
    }

    @Test fun cachesExpireAndClockRollbackOrZoneChangeDoNotShowFreshValues() {
        val snapshot = parseSystemWeather(fields(), now, zone)!!
        assertFalse(selectWeather(snapshot, now.plusSeconds(6 * 3600 - 1), zone).stale)
        assertTrue(selectWeather(snapshot, now.plusSeconds(6 * 3600), zone).stale)
        assertNull(selectWeather(snapshot, now.plusSeconds(24 * 3600), zone).today)
        assertNull(selectWeather(snapshot, now.minusSeconds(1), zone).today)
        assertNull(selectWeather(snapshot, now, ZoneId.of("UTC")).today)
        val count = CainiaoReading(CainiaoCounts(2, 1), now.toEpochMilli())
        assertEquals(2, selectCainiao(count, now.toEpochMilli()).counts!!.awaitingPickup)
        assertTrue(selectCainiao(count, now.plusSeconds(3600).toEpochMilli()).stale)
        assertNull(selectCainiao(count, now.plusSeconds(86400).toEpochMilli()).counts)
        assertNull(selectCainiao(count, now.minusSeconds(1).toEpochMilli()).counts)
    }

    @Test fun yearBoundaryResolvesDecemberAndJanuaryWithoutChangingTheSourceDay() {
        val december = fields().map {
            when (it.name) {
                "widget_city_date" -> it.copy(text = "12月31日星期四")
                "small_dayOfWeek" -> it.copy(text = "星期五")
                else -> it
            }
        }
        val time = Instant.parse("2026-12-31T16:02:00Z")
        val snapshot = parseSystemWeather(december, time, zone)!!
        assertEquals(LocalDate.of(2027, 1, 1), selectWeather(snapshot, time, zone).today!!.date)
    }

    private fun fields() = listOf(
        WidgetField("widget_city_name", "拱墅区"),
        WidgetField("widget_city_date", "11月16日星期一"),
        WidgetField("widget_current_temperature_unit", "℃"),
        WidgetField("widget_currentweather_smallicon", description = "雾"),
        WidgetField("widget_highlow_temperature", "18 / 12"),
        WidgetField("small_dayOfWeek", "星期二"),
        WidgetField("small_icon", description = "小雨"),
        WidgetField("highlow_temperature", "14 / 12"),
    )
}
