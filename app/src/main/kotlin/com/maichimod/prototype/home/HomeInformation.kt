package com.maichimod.prototype.home

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal data class WidgetField(val name: String, val text: String = "", val description: String = "")
internal data class CainiaoCounts(val awaitingPickup: Int, val inTransit: Int?)
internal data class CainiaoReading(val counts: CainiaoCounts, val readAtMillis: Long)
internal data class CainiaoSelection(val counts: CainiaoCounts?, val stale: Boolean)
internal data class DailyWeather(val date: LocalDate, val minC: Int, val maxC: Int, val text: String)
internal data class WeatherSnapshot(val city: String, val zoneId: String, val days: List<DailyWeather>, val readAt: Instant)
internal data class WeatherSelection(val city: String?, val today: DailyWeather?, val tomorrow: DailyWeather?, val stale: Boolean)

internal fun parseCainiaoCounts(status: String?, number: String?, arrival: String?): CainiaoCounts? {
    if (status?.trim() != "待取件" || number == null || !number.trim().matches(Regex("[0-9]{1,4}"))) return null
    val count = number.trim().toIntOrNull()?.takeIf { it in 0..9999 } ?: return null
    val transit = arrival?.trim()?.let { Regex("还有([0-9]{1,4})个包裹在路上").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
    return CainiaoCounts(count, transit)
}

internal fun selectCainiao(reading: CainiaoReading?, nowMillis: Long): CainiaoSelection {
    val age = reading?.let { nowMillis - it.readAtMillis } ?: -1
    if (reading == null || age < 0 || age >= 86_400_000) return CainiaoSelection(null, true)
    return CainiaoSelection(reading.counts, age >= 3_600_000)
}

/** Only the validated Huawei widget fields are understood. Unknown layouts stay unavailable. */
internal fun parseSystemWeather(fields: List<WidgetField>, now: Instant, zone: ZoneId): WeatherSnapshot? {
    fun one(name: String) = fields.filter { it.name == name }.singleOrNull()
    val city = one("widget_city_name")?.text?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
    if (one("widget_current_temperature_unit")?.text?.trim() != "℃") return null
    val dateText = one("widget_city_date")?.text?.trim() ?: return null
    val match = Regex("(\\d{1,2})月(\\d{1,2})日(星期[一二三四五六日天])").matchEntire(dateText) ?: return null
    val today = now.atZone(zone).toLocalDate()
    val date = (today.year - 1..today.year + 1).mapNotNull { year ->
        runCatching { LocalDate.of(year, match.groupValues[1].toInt(), match.groupValues[2].toInt()) }.getOrNull()
    }.filter { abs(ChronoUnit.DAYS.between(today, it)) <= 7 && weekday(it) == normalizeWeekday(match.groupValues[3]) }.singleOrNull() ?: return null
    val temperature = temperature(one("widget_highlow_temperature")?.text) ?: return null
    val condition = condition(one("widget_currentweather_smallicon")) ?: return null
    val days = mutableListOf(DailyWeather(date, temperature.first, temperature.second, condition))
    // Repeated resource IDs belong to ordered day/icon/range triples. Never reuse another day's range.
    val starts = fields.indices.filter { fields[it].name == "small_dayOfWeek" }
    val future = starts.mapIndexedNotNull { index, start ->
        val end = starts.getOrNull(index + 1) ?: fields.size
        val group = fields.subList(start, end)
        val day = (1L..3L).map { date.plusDays(it) }.singleOrNull { weekday(it) == normalizeWeekday(fields[start].text) } ?: return@mapIndexedNotNull null
        val range = temperature(group.filter { it.name == "highlow_temperature" }.singleOrNull()?.text) ?: return@mapIndexedNotNull null
        val text = condition(group.filter { it.name == "small_icon" }.singleOrNull()) ?: return@mapIndexedNotNull null
        DailyWeather(day, range.first, range.second, text)
    }
    days += future.groupBy { it.date }.values.filter { it.size == 1 }.map { it.single() }
    return WeatherSnapshot(city, zone.id, days.sortedBy { it.date }, now)
}

private fun temperature(text: String?): Pair<Int, Int>? {
    val match = Regex("([−-]?[0-9]{1,2})\\s*[/／]\\s*([−-]?[0-9]{1,2})").matchEntire(text?.trim() ?: "") ?: return null
    val high = match.groupValues[1].replace('−', '-').toIntOrNull() ?: return null
    val low = match.groupValues[2].replace('−', '-').toIntOrNull() ?: return null
    return if (low in -70..60 && high in low..60) low to high else null
}
private fun condition(field: WidgetField?): String? = field?.let { it.description.ifBlank { it.text }.trim() }
    ?.takeIf { it.isNotEmpty() && it.length <= 40 && !it.any(Char::isDigit) }
private fun normalizeWeekday(text: String) = text.trim().replace("星期天", "星期日")
private fun weekday(date: LocalDate) = "星期" + "一二三四五六日"[date.dayOfWeek.value - 1]

internal fun selectWeather(snapshot: WeatherSnapshot?, now: Instant, zone: ZoneId): WeatherSelection {
    val age = snapshot?.let { now.toEpochMilli() - it.readAt.toEpochMilli() } ?: -1
    if (snapshot == null || snapshot.zoneId != zone.id || age < 0 || age >= 86_400_000) return WeatherSelection(null, null, null, true)
    val today = now.atZone(zone).toLocalDate()
    return WeatherSelection(snapshot.city, snapshot.days.singleOrNull { it.date == today },
        snapshot.days.singleOrNull { it.date == today.plusDays(1) }, age >= 21_600_000)
}
