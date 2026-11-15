package com.maichimod.prototype.home

import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

internal class HomeReadings(private val store: HomeInfoStore) {
    var cainiao: CainiaoReading? = runCatching {
        val json = JSONObject(store.text("cainiao.cache") ?: return@runCatching null)
        val count = json.getInt("count").takeIf { it in 0..9999 } ?: return@runCatching null
        val transit = if (json.isNull("transit")) null else json.getInt("transit").takeIf { it in 0..9999 } ?: return@runCatching null
        CainiaoReading(CainiaoCounts(count, transit), json.getLong("readAt").takeIf { it > 0 } ?: return@runCatching null)
    }.getOrNull()
        private set
    var weather: WeatherSnapshot? = runCatching {
        val json = JSONObject(store.text("weather.cache") ?: return@runCatching null)
        val city = json.getString("city").takeIf { it.isNotBlank() && it.length <= 80 } ?: return@runCatching null
        val zone = ZoneId.of(json.getString("zone"))
        val array = json.getJSONArray("days").takeIf { it.length() in 1..4 } ?: return@runCatching null
        val days = (0 until array.length()).map { index ->
            val day = array.getJSONObject(index)
            val low = day.getInt("min"); val high = day.getInt("max")
            require(low in -70..60 && high in low..60)
            val text = day.getString("text"); require(text.isNotBlank() && text.length <= 40)
            DailyWeather(LocalDate.parse(day.getString("date")), low, high, text)
        }
        require(days.map { it.date }.toSet().size == days.size)
        WeatherSnapshot(city, zone.id, days, Instant.ofEpochMilli(json.getLong("readAt")))
    }.getOrNull()
        private set

    fun acceptCainiao(fields: List<WidgetField>, now: Long): Boolean {
        fun field(name: String) = fields.filter { it.name == name }.singleOrNull()?.text
        val counts = parseCainiaoCounts(field("tv_pkg_status"), field("tv_pkg_num"), field("tv_pkg_arrive")) ?: return false
        val previous = cainiao
        if (previous != null && previous.counts == counts && previous.readAtMillis <= now) return true
        cainiao = CainiaoReading(counts, now)
        store.saveText("cainiao.cache", JSONObject().put("count", counts.awaitingPickup).put("transit", counts.inTransit ?: JSONObject.NULL).put("readAt", now).toString())
        return true
    }
    fun acceptWeather(fields: List<WidgetField>, now: Instant, zone: ZoneId): Boolean {
        val incoming = parseSystemWeather(fields, now, zone) ?: return false
        val previous = weather
        if (previous != null && previous.city == incoming.city && previous.zoneId == incoming.zoneId &&
            incoming.sourceDate < previous.sourceDate && selectWeather(previous, now, zone).today != null) return false
        if (previous != null && previous.readAt <= now && previous.copy(readAt = now) == incoming) return true
        weather = incoming
        val days = JSONArray()
        incoming.days.forEach { day -> days.put(JSONObject().put("date", day.date.toString()).put("min", day.minC).put("max", day.maxC).put("text", day.text)) }
        store.saveText("weather.cache", JSONObject().put("city", incoming.city).put("zone", incoming.zoneId).put("readAt", incoming.readAt.toEpochMilli()).put("days", days).toString())
        return true
    }
    fun clearCainiao() { cainiao = null; store.saveText("cainiao.cache", null) }
    fun clearWeather() { weather = null; store.saveText("weather.cache", null) }
}
