package com.maichimod.prototype.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.maichimod.prototype.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Owns only home information. It never changes reception, screen demand or user-interaction time. */
internal class HomeInfoController(private val activity: Activity, private val dashboard: View,
    private val settings: View, root: ViewGroup, onShowDashboard: () -> Unit, restoredState: Bundle? = null) {
    private val store = HomeInfoStore(activity)
    private val readings = HomeReadings(store)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var dashboardVisible = false
    private var active = false
    private var closed = false
    private var returningFromCainiao = false
    private var weatherStatus = WidgetStatus.DISCONNECTED
    private var cainiaoStatus = WidgetStatus.DISCONNECTED
    private var weatherHealthy: Boolean? = null
    private var cainiaoHealthy: Boolean? = null
    private var weatherHost: HomeWidgetHost? = null
    private var cainiaoHost: HomeWidgetHost? = null
    private val weatherSpec = WidgetSpec("weather", ComponentName("com.huawei.android.totemweather",
        "com.huawei.android.totemweather.widget.huaweiwidget.HuaweiWidgetWeatherProvider"), 0x6EA710, 720, 240, true)
    private val cainiaoSpec = WidgetSpec("cainiao", ComponentName("com.cainiao.wireless",
        "com.cainiao.wireless.widget.widgetprovider.widget.LogisticsWidgetSmall"), 0x6CA110, 280, 180, false)
    private val tick = object : Runnable {
        override fun run() {
            if (!active || closed) return
            refresh()
            handler.postDelayed(this, 60_000)
        }
    }
    private val timeEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }

    init {
        @Suppress("DEPRECATION")
        val launch: (Intent, Int) -> Unit = { intent, code -> activity.startActivityForResult(intent, code) }
        weatherHost = HomeWidgetHost(activity, weatherSpec, store, root, launch, { weatherStatus = it; render() }, { fields ->
            weatherHealthy = readings.acceptWeather(fields, Instant.now(), ZoneId.systemDefault())
            render()
        }, restoredPendingId = restoredState?.getInt("weather.pending", -1))
        cainiaoHost = HomeWidgetHost(activity, cainiaoSpec, store, root, launch, { cainiaoStatus = it; render() }, { fields ->
            cainiaoHealthy = readings.acceptCainiao(fields, System.currentTimeMillis())
            render()
        }, restoredPendingId = restoredState?.getInt("cainiao.pending", -1))
        runCatching { activity.packageManager.getApplicationIcon(cainiaoSpec.provider.packageName) }.getOrNull()?.let {
            dashboard.findViewById<ImageView>(R.id.cainiao_icon).setImageDrawable(it)
        }
        dashboard.findViewById<View>(R.id.weather_info).setOnClickListener {
            if (weatherStatus == WidgetStatus.DISCONNECTED) weatherHost?.connect() else open(weatherSpec, R.string.system_weather)
        }
        dashboard.findViewById<View>(R.id.cainiao_tile).setOnClickListener {
            if (cainiaoStatus == WidgetStatus.DISCONNECTED) cainiaoHost?.connect()
            else if (open(cainiaoSpec, R.string.cainiao)) returningFromCainiao = true
        }
        settings.findViewById<View>(R.id.weather_connect).setOnClickListener {
            if (weatherStatus != WidgetStatus.CONNECTING) {
                readings.clearWeather(); weatherHealthy = null
                weatherHost?.disconnect(); weatherHost?.connect(); render()
            }
        }
        settings.findViewById<View>(R.id.cainiao_connect).setOnClickListener {
            if (cainiaoStatus != WidgetStatus.CONNECTING) {
                readings.clearCainiao(); cainiaoHealthy = null
                cainiaoHost?.disconnect(); cainiaoHost?.connect(); render()
            }
        }
        settings.findViewById<View>(R.id.weather_disconnect).setOnClickListener {
            weatherHost?.disconnect(); readings.clearWeather(); weatherHealthy = null; render()
        }
        settings.findViewById<View>(R.id.cainiao_disconnect).setOnClickListener {
            cainiaoHost?.disconnect(); readings.clearCainiao(); cainiaoHealthy = null; render()
        }
        settings.findViewById<View>(R.id.weather_open).setOnClickListener { open(weatherSpec, R.string.system_weather) }
        settings.findViewById<View>(R.id.home_information_refresh).setOnClickListener { onShowDashboard(); refresh() }
        render()
    }

    fun start() {
        if (started || closed) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(timeEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(timeEvents, filter)
        updateVisibility()
    }
    fun setDashboardVisible(visible: Boolean) { dashboardVisible = visible; updateVisibility() }
    private fun updateVisibility() {
        val visible = started && dashboardVisible && !closed
        if (visible == active) return
        active = visible
        handler.removeCallbacks(tick)
        if (visible) {
            weatherHealthy = null; cainiaoHealthy = null
            weatherHost?.start(); cainiaoHost?.start()
            requestCainiaoRefresh(returningFromCainiao)
            returningFromCainiao = false
            handler.postDelayed(tick, 60_000)
        } else { weatherHost?.stop(); cainiaoHost?.stop() }
        render()
    }
    fun stop() {
        if (!started) return
        started = false
        activity.unregisterReceiver(timeEvents)
        updateVisibility()
    }
    fun close() {
        stop(); closed = true
        handler.removeCallbacksAndMessages(null)
        weatherHost?.close(); cainiaoHost?.close()
    }
    fun saveState() = Bundle().apply {
        putInt("weather.pending", store.pendingId(weatherSpec.key))
        putInt("cainiao.pending", store.pendingId(cainiaoSpec.key))
    }
    fun onActivityResult(request: Int, result: Int, data: Intent?): Boolean {
        val handled = weatherHost?.onActivityResult(request, result, data) == true || cainiaoHost?.onActivityResult(request, result, data) == true
        if (handled) { requestCainiaoRefresh(false); render() }
        return handled
    }
    fun refresh() {
        if (closed) return
        if (active) { weatherHost?.requestRead(); cainiaoHost?.requestRead(); requestCainiaoRefresh(false) }
        render()
    }

    @Suppress("DEPRECATION")
    private fun requestCainiaoRefresh(returning: Boolean) {
        if (!active || cainiaoStatus != WidgetStatus.CONNECTED) return
        val info = runCatching { activity.packageManager.getPackageInfo(cainiaoSpec.provider.packageName, 0) }.getOrNull() ?: return
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val now = System.currentTimeMillis()
        if (!shouldRefreshCainiao(info.versionName, code, active, now, store.number("cainiao.refresh"), returning)) return
        if (!store.saveNumber("cainiao.refresh", now)) return
        val id = store.boundId(cainiaoSpec.key)
        try {
            activity.sendBroadcast(Intent("com.cainiao.wireless.widget.refresh").setComponent(cainiaoSpec.provider)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id)))
        } catch (_: RuntimeException) { cainiaoHealthy = false; render() }
    }
    private fun open(spec: WidgetSpec, label: Int): Boolean {
        val name = activity.getString(label)
        val intent = activity.packageManager.getLaunchIntentForPackage(spec.provider.packageName)
        if (intent == null) {
            Toast.makeText(activity, activity.getString(R.string.home_source_missing, name), Toast.LENGTH_SHORT).show()
            return false
        }
        return try { activity.startActivity(intent); true } catch (_: RuntimeException) {
            Toast.makeText(activity, activity.getString(R.string.home_source_open_failed, name), Toast.LENGTH_SHORT).show(); false
        }
    }

    private fun render() {
        if (closed) return
        val now = Instant.now(); val zone = ZoneId.systemDefault()
        val canWeather = weatherStatus == WidgetStatus.CONNECTED || weatherStatus == WidgetStatus.UNAVAILABLE
        val weather = selectWeather(if (canWeather) readings.weather else null, now, zone)
        renderDay("today", weather.today)
        renderDay("tomorrow", weather.tomorrow)
        val weatherMessage = if (weather.city != null) {
            activity.getString(R.string.weather_source_format, weather.city) +
                if (weather.stale || weatherHealthy == false || weatherStatus == WidgetStatus.UNAVAILABLE) " · " + activity.getString(R.string.weather_stale) else ""
        } else sourceMessage(weatherStatus, R.string.system_weather, R.string.weather_connect_hint)
        setText(dashboard, R.id.weather_source, weatherMessage)
        dashboard.findViewById<View>(R.id.weather_info).contentDescription = listOf(
            activity.getString(R.string.weather_today_label), dayDescription(weather.today), activity.getString(R.string.weather_tomorrow_label), dayDescription(weather.tomorrow), weatherMessage).joinToString("，")

        val canCainiao = cainiaoStatus == WidgetStatus.CONNECTED || cainiaoStatus == WidgetStatus.UNAVAILABLE
        val count = selectCainiao(if (canCainiao) readings.cainiao else null, now.toEpochMilli())
        val counts = count.counts
        val value = dashboard.findViewById<TextView>(R.id.cainiao_value)
        val valueSize = if (counts == null) 32f else 56f
        if (value.textSize / activity.resources.displayMetrics.scaledDensity != valueSize) value.textSize = valueSize
        setText(dashboard, R.id.cainiao_value, counts?.awaitingPickup?.toString() ?: activity.getString(R.string.cainiao))
        val detail = if (counts != null) {
            val primary = activity.getString(if (counts.awaitingPickup == 0) R.string.cainiao_no_pickup else R.string.cainiao_count_label)
            primary + (counts.inTransit?.let { " · " + activity.getString(R.string.cainiao_transit, it) } ?: "")
        } else if (cainiaoStatus == WidgetStatus.CONNECTED) activity.getString(if (cainiaoHealthy == null) R.string.home_source_reading else R.string.cainiao_unrecognized)
        else sourceMessage(cainiaoStatus, R.string.cainiao, R.string.cainiao_connect_hint)
        setText(dashboard, R.id.cainiao_detail, detail)
        val stale = count.stale || cainiaoHealthy == false || cainiaoStatus == WidgetStatus.UNAVAILABLE
        val freshness = dashboard.findViewById<TextView>(R.id.cainiao_freshness)
        freshness.visibility = if (counts == null) View.GONE else View.VISIBLE
        val time = readings.cainiao?.readAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)) }.orEmpty()
        val readTime = activity.getString(R.string.cainiao_read_time, time) + if (stale) " · " + activity.getString(R.string.cainiao_stale) else ""
        setText(dashboard, R.id.cainiao_freshness, readTime)
        dashboard.findViewById<View>(R.id.cainiao_tile).contentDescription =
            if (counts == null) activity.getString(R.string.cainiao) + "，" + detail else "${counts.awaitingPickup} $detail，$readTime"
        renderSettings(weatherStatus, R.string.system_weather, R.id.weather_connection, R.id.weather_connect, R.id.weather_disconnect)
        renderSettings(cainiaoStatus, R.string.cainiao, R.id.cainiao_connection, R.id.cainiao_connect, R.id.cainiao_disconnect)
    }
    private fun renderSettings(status: WidgetStatus, label: Int, connection: Int, connect: Int, disconnect: Int) {
        setText(settings, connection, sourceMessage(status, label, if (label == R.string.cainiao) R.string.cainiao_connect_hint else R.string.weather_connect_hint))
        setText(settings, connect, if (status == WidgetStatus.CONNECTED || status == WidgetStatus.UNAVAILABLE)
            activity.getString(R.string.home_source_reconnect, activity.getString(label)) else activity.getString(if (label == R.string.cainiao) R.string.cainiao_connect else R.string.weather_connect_hint))
        settings.findViewById<View>(connect).isEnabled = status != WidgetStatus.CONNECTING && status != WidgetStatus.MISSING
        settings.findViewById<View>(disconnect).isEnabled = status == WidgetStatus.CONNECTED || status == WidgetStatus.CONNECTING || status == WidgetStatus.UNAVAILABLE
    }
    private fun sourceMessage(status: WidgetStatus, label: Int, disconnected: Int) = when (status) {
        WidgetStatus.MISSING -> activity.getString(R.string.home_source_missing, activity.getString(label))
        WidgetStatus.DISCONNECTED -> activity.getString(disconnected)
        WidgetStatus.CONNECTING -> activity.getString(R.string.home_source_authorizing)
        WidgetStatus.CONNECTED -> activity.getString(R.string.home_source_connected, activity.getString(label))
        WidgetStatus.UNAVAILABLE -> activity.getString(R.string.home_source_unavailable, activity.getString(label))
    }
    private fun renderDay(prefix: String, day: DailyWeather?) {
        val textId = if (prefix == "today") R.id.weather_today_text else R.id.weather_tomorrow_text
        val rangeId = if (prefix == "today") R.id.weather_today_range else R.id.weather_tomorrow_range
        val iconId = if (prefix == "today") R.id.weather_today_icon else R.id.weather_tomorrow_icon
        setText(dashboard, textId, day?.text ?: activity.getString(R.string.weather_no_forecast))
        setText(dashboard, rangeId, day?.let { activity.getString(R.string.weather_range, it.minC, it.maxC) } ?: activity.getString(R.string.weather_no_temperature))
        dashboard.findViewById<ImageView>(iconId).setImageResource(weatherIcon(day?.text.orEmpty()))
    }
    private fun dayDescription(day: DailyWeather?) = day?.let { "${it.text}，${it.minC}至${it.maxC}摄氏度" } ?: activity.getString(R.string.weather_no_forecast)
    private fun weatherIcon(text: String) = when {
        "雪" in text -> R.drawable.ic_weather_snow
        "雨" in text -> R.drawable.ic_weather_rain
        "雾" in text || "霾" in text -> R.drawable.ic_weather_fog
        text == "晴" -> R.drawable.ic_weather_sun
        else -> R.drawable.ic_weather_cloud
    }
    private fun setText(root: View, id: Int, text: String) {
        val view = root.findViewById<TextView>(id)
        if (view.text.toString() != text) view.text = text
    }
}
