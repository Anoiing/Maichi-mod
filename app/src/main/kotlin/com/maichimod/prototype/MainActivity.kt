package com.maichimod.prototype

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ImageView
import android.widget.TextView
import com.maichimod.prototype.session.ElevatorStatus
import com.maichimod.prototype.session.MonitorStatus
import com.maichimod.prototype.session.TerminalState
import com.maichimod.prototype.transport.WiredLink

class MainActivity : Activity() {
    private lateinit var connection: AppConnection
    private lateinit var dashboard: View
    private lateinit var monitorSources: View
    private lateinit var monitorPage: View
    private lateinit var settingsPage: View
    private lateinit var unitMonitor: View
    private lateinit var elevatorTile: View
    private lateinit var preview: ImageView
    private lateinit var connectionState: TextView
    private lateinit var sourcesState: TextView
    private lateinit var elevatorState: TextView
    private lateinit var monitorState: TextView
    private lateinit var monitorMessage: TextView
    private lateinit var monitorOverlay: View
    private lateinit var retry: View
    private lateinit var volumeControls: VolumeControls
    private lateinit var connectionSettings: ConnectionSettingsView

    private var options = ConnectionOptions()
    private var link: LinkStatus? = null
    private var wiredLink: WiredLink? = null
    private var state = TerminalState()
    private var unregisterBack: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets()
        dashboard = findViewById(R.id.dashboard)
        monitorSources = findViewById(R.id.monitor_sources)
        monitorPage = findViewById(R.id.monitor_page)
        settingsPage = findViewById(R.id.settings_page)
        unitMonitor = findViewById(R.id.unit_monitor)
        elevatorTile = findViewById(R.id.elevator_tile)
        preview = findViewById(R.id.preview)
        findViewById<View>(R.id.monitor_frame).clipToOutline = true
        connectionState = findViewById(R.id.connection_state)
        sourcesState = findViewById(R.id.sources_state)
        elevatorState = findViewById(R.id.elevator_state)
        monitorState = findViewById(R.id.monitor_state)
        monitorMessage = findViewById(R.id.monitor_message)
        monitorOverlay = findViewById(R.id.monitor_overlay)
        retry = findViewById(R.id.retry_monitor)
        volumeControls = VolumeControls(settingsPage)
        volumeControlStream = AudioManager.STREAM_MUSIC

        options = ConnectionOptions.load(getPreferences(MODE_PRIVATE), defaultWired = !BuildConfig.DEBUG)
        connectionSettings = ConnectionSettingsView(settingsPage, { options }, ::saveOptions, ::activateMode)
        connectionSettings.renderOptions(options)
        connection = AppConnection(this, { status, ethernet ->
            link = status
            wiredLink = ethernet
            renderState()
        }, { updated ->
            state = updated
            renderState()
        }, { bitmap ->
            if (monitorPage.visibility == View.VISIBLE &&
                (state.monitor == MonitorStatus.VIEWING || state.monitor == MonitorStatus.ENDED)
            ) preview.setImageBitmap(bitmap)
        })
        findViewById<View>(R.id.monitor_tile).setOnClickListener { showPage(monitorSources) }
        unitMonitor.setOnClickListener { openMonitor() }
        elevatorTile.setOnClickListener { connection.callElevator() }
        findViewById<View>(R.id.sources_back).setOnClickListener { showPage(dashboard) }
        findViewById<View>(R.id.monitor_back).setOnClickListener { showPage(monitorSources) }
        retry.setOnClickListener {
            if (link == LinkStatus.WIRED_ERROR) activateMode() else openMonitor()
        }
        findViewById<View>(R.id.settings_button).setOnClickListener { showSettings() }
        findViewById<View>(R.id.settings_back).setOnClickListener { showPage(dashboard) }
        val restoredPage = savedInstanceState?.getInt("page", R.id.dashboard) ?: R.id.dashboard
        showPage(findViewById(restoredPage))
        connectionSettings.restoreDialogState(savedInstanceState?.getBundle("settings_dialog"))
    }

    override fun onStart() {
        super.onStart()
        activateMode()
    }

    override fun onStop() {
        if (monitorPage.visibility == View.VISIBLE) showPage(monitorSources)
        connection.stop()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterBack?.invoke()
        connectionSettings.dismissDialog()
        connection.close()
        super.onDestroy()
    }

    // API 26–32 dispatch back here. API 33+ uses the callback in updateBackNavigation().
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!navigateBack()) super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val page = listOf(dashboard, monitorSources, monitorPage, settingsPage).first { it.visibility == View.VISIBLE }
        outState.putInt("page", if (page === monitorPage) R.id.monitor_sources else page.id)
        outState.putBundle("settings_dialog", connectionSettings.saveDialogState())
        super.onSaveInstanceState(outState)
    }

    private fun navigateBack(): Boolean {
        when {
            monitorPage.isShown -> showPage(monitorSources)
            monitorSources.isShown || settingsPage.isShown -> showPage(dashboard)
            else -> return false
        }
        return true
    }

    private fun updateBackNavigation() {
        if (Build.VERSION.SDK_INT < 33) return
        unregisterBack?.invoke()
        unregisterBack = null
        if (dashboard.visibility == View.VISIBLE) return
        val dispatcher = onBackInvokedDispatcher
        val callback = OnBackInvokedCallback { navigateBack() }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        unregisterBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::volumeControls.isInitialized && settingsPage.isShown) volumeControls.refresh()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val handled = super.onKeyUp(keyCode, event)
        if (settingsPage.isShown &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)
        ) {
            settingsPage.postDelayed({ if (settingsPage.isShown) volumeControls.refresh() }, 150L)
        }
        return handled
    }

    private fun activateMode() {
        link = null
        wiredLink = null
        state = TerminalState()
        renderState()
        connection.start(options)
    }

    private fun openMonitor() {
        showPage(monitorPage)
        preview.setImageDrawable(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (link == LinkStatus.SAMPLE || link == LinkStatus.WIRED_CONNECTED) {
            state = state.copy(monitor = MonitorStatus.DISCOVERING)
            connection.openMonitor()
        }
        renderState()
    }

    private fun showPage(page: View) {
        if (monitorPage.visibility == View.VISIBLE && page !== monitorPage) {
            connection.closeMonitor()
            preview.setImageDrawable(null)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        for (candidate in listOf(dashboard, monitorSources, monitorPage, settingsPage)) {
            candidate.visibility = if (candidate === page) View.VISIBLE else View.GONE
        }
        updateBackNavigation()
    }

    private fun renderState() {
        val status = when (link) {
            LinkStatus.SAMPLE -> R.string.sample_mode
            LinkStatus.SAMPLE_MISSING -> R.string.no_offline_samples
            LinkStatus.WIRED_CONNECTED -> R.string.wired_connected
            LinkStatus.WIRED_WAITING -> R.string.wired_disconnected
            LinkStatus.WIRED_RECONNECTING -> R.string.wired_reconnecting
            LinkStatus.WIRED_NEEDS_IDS -> R.string.wired_needs_ids
            LinkStatus.WIRED_PROFILE_MISMATCH -> R.string.wired_profile_mismatch
            LinkStatus.WIRED_ERROR -> R.string.wired_open_failed
            null -> when {
                !options.wired -> R.string.loading_samples
                !options.hasTerminalIds -> R.string.wired_needs_ids
                else -> R.string.wired_disconnected
            }
        }
        val statusColor = when (link) {
            LinkStatus.SAMPLE, LinkStatus.SAMPLE_MISSING -> R.color.amber
            LinkStatus.WIRED_CONNECTED -> R.color.status_ready
            LinkStatus.WIRED_ERROR, LinkStatus.WIRED_PROFILE_MISMATCH -> R.color.status_error
            else -> R.color.muted_text
        }
        setStatus(connectionState, status, statusColor)
        setStatus(sourcesState, status, statusColor)
        connectionSettings.renderConnection(status, statusColor, wiredLink, link == LinkStatus.WIRED_ERROR)
        val ready = link == LinkStatus.SAMPLE || link == LinkStatus.SAMPLE_MISSING || link == LinkStatus.WIRED_CONNECTED
        unitMonitor.isEnabled = ready
        unitMonitor.alpha = if (ready) 1f else 0.65f
        val waiting = state.elevator == ElevatorStatus.DISCOVERING || state.elevator == ElevatorStatus.WAITING
        elevatorTile.isEnabled = ready && !waiting
        elevatorTile.alpha = if (ready) 1f else 0.65f
        elevatorState.setText(when (state.elevator) {
            ElevatorStatus.IDLE -> R.string.call_home_floor
            ElevatorStatus.DISCOVERING, ElevatorStatus.WAITING -> R.string.waiting_for_reply
            ElevatorStatus.RESPONDED -> if (options.wired) R.string.device_responded else R.string.sample_responded
            ElevatorStatus.TIMED_OUT -> R.string.elevator_timeout
            ElevatorStatus.DISCONNECTED -> R.string.wired_disconnected
        })
        elevatorState.setTextColor(getColor(when (state.elevator) {
            ElevatorStatus.DISCOVERING, ElevatorStatus.WAITING -> R.color.amber
            ElevatorStatus.RESPONDED -> if (options.wired) R.color.status_ready else R.color.amber
            ElevatorStatus.TIMED_OUT -> R.color.status_error
            else -> R.color.secondary_text
        }))

        val canPlayMonitor = link == LinkStatus.SAMPLE || link == LinkStatus.WIRED_CONNECTED
        val monitorInactive = state.monitor == MonitorStatus.IDLE || state.monitor == MonitorStatus.DISCONNECTED
        val message = when {
            link == LinkStatus.SAMPLE_MISSING -> R.string.missing_monitor_sample
            !canPlayMonitor -> status
            state.monitor == MonitorStatus.DISCOVERING -> R.string.finding_peer
            state.monitor == MonitorStatus.CONNECTING -> R.string.waiting_for_video
            state.monitor == MonitorStatus.VIEWING -> if (options.wired) R.string.monitor_live else R.string.sample_mode
            state.monitor == MonitorStatus.ENDED -> if (options.wired) R.string.monitor_ended else R.string.sample_ended
            state.monitor == MonitorStatus.TIMED_OUT -> R.string.monitor_timeout
            else -> R.string.monitor_ready_to_retry
        }
        val showImage = canPlayMonitor && (state.monitor == MonitorStatus.VIEWING || state.monitor == MonitorStatus.ENDED)
        val monitorColor = if (state.monitor == MonitorStatus.ENDED) R.color.muted_text else statusColor
        setStatus(monitorState, if (showImage) message else status, monitorColor)
        monitorMessage.setText(message)
        monitorOverlay.visibility = if (showImage) View.GONE else View.VISIBLE
        val canRetry = link == LinkStatus.WIRED_ERROR ||
            (canPlayMonitor && (monitorInactive || state.monitor == MonitorStatus.TIMED_OUT))
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        if (!showImage) preview.setImageDrawable(null)
    }

    private fun setStatus(view: TextView, label: Int, color: Int) {
        view.setText(label)
        view.compoundDrawableTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun showSettings() {
        showPage(settingsPage)
        settingsPage.requestFocus()
        volumeControls.refresh()
    }

    private fun saveOptions(updated: ConnectionOptions) {
        if (updated == options) return
        val reconnect = updated.requiresReconnect(options)
        updated.save(getPreferences(MODE_PRIVATE))
        options = updated
        connectionSettings.renderOptions(updated)
        if (reconnect) activateMode()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            return
        }
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        val root = findViewById<View>(R.id.app_root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            val safe = insets.getInsets(types)
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        root.requestApplyInsets()
    }
}
