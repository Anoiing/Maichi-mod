package com.maichimod.prototype.home

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView

internal data class WidgetSpec(val key: String, val provider: ComponentName, val hostId: Int, val requestCode: Int,
    val widthDp: Int, val heightDp: Int, val keepLaidOut: Boolean)
internal enum class WidgetStatus { MISSING, DISCONNECTED, CONNECTING, CONNECTED, UNAVAILABLE }

internal class HomeWidgetHost(private val activity: Activity, val spec: WidgetSpec, private val store: HomeInfoStore,
    private val parent: ViewGroup, private val launchBind: (Intent, Int) -> Unit,
    private val status: (WidgetStatus) -> Unit, private val fields: (List<WidgetField>) -> Unit) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var closed = false
    private var currentView: ReadingView? = null
    private var listening = false
    private val host = object : AppWidgetHost(activity, spec.hostId) {
        override fun onCreateView(context: Context, id: Int, info: AppWidgetProviderInfo) = ReadingView(context)
        fun releaseViews() = clearViews()
    }
    private val frame = object : FrameLayout(activity) {
        override fun dispatchTouchEvent(event: MotionEvent) = false
        override fun dispatchGenericMotionEvent(event: MotionEvent) = false
    }.apply {
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        visibility = View.GONE
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running || closed) return
            if (validBoundId() >= 0) currentView?.read() else {
                detach()
                publishStatus()
            }
            handler.postDelayed(this, 60_000)
        }
    }

    init {
        parent.addView(frame, 0, ViewGroup.LayoutParams(dp(spec.widthDp), dp(spec.heightDp)))
        reconcile()
        publishStatus()
    }

    private fun options() = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, spec.widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, spec.widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, spec.heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, spec.heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
    }
    private fun ownIds() = runCatching { host.appWidgetIds.toSet() }.getOrDefault(emptySet())
    private fun expected(id: Int) = id >= 0 && runCatching { manager.getAppWidgetInfo(id)?.provider == spec.provider }.getOrDefault(false)
    private fun validBoundId() = store.boundId(spec.key).takeIf { it in ownIds() && expected(it) } ?: -1
    private fun installed() = runCatching { manager.installedProviders.any { it.provider == spec.provider } }.getOrDefault(false)
    private fun delete(id: Int) { if (id in ownIds()) runCatching { host.deleteAppWidgetId(id) } }

    private fun reconcile() {
        val owned = ownIds()
        val bound = store.boundId(spec.key).takeIf { it in owned && expected(it) } ?: -1
        val pending = store.pendingId(spec.key).takeIf { it in owned && expected(it) } ?: -1
        val restored = if (bound >= 0) bound else pending
        // An unbound pending ID cannot be resumed after process death; a completed bind can.
        if (store.saveIds(spec.key, restored, -1)) owned.filter { it != restored }.forEach(::delete)
    }

    fun connect() {
        if (closed) return
        if (!installed()) { status(WidgetStatus.MISSING); return }
        if (validBoundId() >= 0) { mount(); publishStatus(); return }
        if (store.pendingId(spec.key) >= 0) return
        var allocated = -1
        try {
            allocated = host.allocateAppWidgetId()
            if (!store.saveIds(spec.key, -1, allocated)) {
                delete(allocated); status(WidgetStatus.UNAVAILABLE); return
            }
            if (manager.bindAppWidgetIdIfAllowed(allocated, spec.provider, options())) complete(allocated)
            else {
                status(WidgetStatus.CONNECTING)
                launchBind(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, allocated)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, spec.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options()), spec.requestCode)
            }
        } catch (_: RuntimeException) {
            delete(allocated); store.saveIds(spec.key, -1, -1); status(WidgetStatus.UNAVAILABLE)
        }
    }

    fun onActivityResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != spec.requestCode) return false
        if (closed) return true
        val pending = store.pendingId(spec.key)
        if (pending < 0) return true
        val returned = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending) ?: pending
        if (result == Activity.RESULT_OK && returned == pending && pending in ownIds() && expected(pending)) complete(pending)
        else { delete(pending); store.saveIds(spec.key, -1, -1); publishStatus() }
        return true
    }

    private fun complete(id: Int) {
        if (!expected(id) || !store.saveIds(spec.key, id, -1)) {
            delete(id); store.saveIds(spec.key, -1, -1); status(WidgetStatus.UNAVAILABLE); return
        }
        publishStatus()
        if (running) mount()
    }

    fun start() {
        if (closed || running) return
        running = true
        publishStatus()
        mount()
        handler.postDelayed(tick, 60_000)
    }
    fun requestRead() { if (running && !closed) currentView?.scheduleRead() }

    private fun mount() {
        if (!running || closed || currentView != null) return
        val id = validBoundId()
        if (id < 0) return
        try {
            host.startListening(); listening = true
            val info = manager.getAppWidgetInfo(id) ?: return
            val view = host.createView(activity, id, info) as ReadingView
            currentView = view
            frame.visibility = if (spec.keepLaidOut) View.VISIBLE else View.GONE
            frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(options(), spec.widthDp, spec.heightDp, spec.widthDp, spec.heightDp)
            view.scheduleRead()
        } catch (failure: RuntimeException) {
            android.util.Log.w("HomeWidgetHost", "Widget mount failed: ${spec.key}", failure)
            detach(); status(WidgetStatus.UNAVAILABLE)
        }
    }

    private fun publishStatus() {
        status(when {
            !installed() -> WidgetStatus.MISSING
            validBoundId() >= 0 -> WidgetStatus.CONNECTED
            store.pendingId(spec.key) >= 0 -> WidgetStatus.CONNECTING
            else -> WidgetStatus.DISCONNECTED
        })
    }
    private fun detach() {
        currentView = null
        frame.removeAllViews(); frame.visibility = View.GONE
        if (listening) runCatching { host.stopListening() }
        listening = false
        host.releaseViews()
    }
    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        detach()
    }
    fun close() { stop(); closed = true; parent.removeView(frame) }
    fun disconnect() {
        val wasRunning = running
        stop()
        // Only this host's IDs are touched; desktop and the other source's IDs are independent.
        ownIds().forEach(::delete)
        store.saveIds(spec.key, -1, -1)
        publishStatus()
        if (wasRunning) start()
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()

    private inner class ReadingView(context: Context) : AppWidgetHostView(context) {
        private val readTask = Runnable { read() }
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            try { super.updateAppWidget(remoteViews) } catch (_: RuntimeException) { if (running) status(WidgetStatus.UNAVAILABLE) }
            scheduleRead()
        }
        fun scheduleRead() {
            // View.getHandler() is null before attachment; use the owner's main-thread queue.
            this@HomeWidgetHost.handler.removeCallbacks(readTask)
            this@HomeWidgetHost.handler.post(readTask)
            this@HomeWidgetHost.handler.postDelayed(readTask, 400)
            this@HomeWidgetHost.handler.postDelayed(readTask, 2500)
        }
        fun read() {
            if (!running || closed || currentView !== this) return
            val result = ArrayList<WidgetField>()
            var remaining = 1500
            fun visit(view: View) {
                if (--remaining < 0) return
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                if (name != null) {
                    val text = (view as? TextView)?.text?.toString().orEmpty().take(200)
                    val description = view.contentDescription?.toString().orEmpty().take(200)
                    if (text.isNotEmpty() || description.isNotEmpty()) result += WidgetField(name, text, description)
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            try { visit(this); fields(result) } catch (_: RuntimeException) { status(WidgetStatus.UNAVAILABLE) }
        }
    }
}
