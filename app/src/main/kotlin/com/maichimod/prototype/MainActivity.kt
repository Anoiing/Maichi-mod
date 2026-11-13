package com.maichimod.prototype

import android.annotation.SuppressLint
import android.app.Activity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import com.maichimod.prototype.session.CallStatus
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
    private lateinit var callPage: View
    private lateinit var callPreview: ImageView
    private lateinit var callAudio: CallAudio
    private var callStatus = CallStatus.IDLE
    private var displayedCallToken = 0
    private var callReturnPage: View? = null
    private var callSettingsDraft: Bundle? = null
    private var ringtone: Ringtone? = null
    private var microphoneRequestCall: Int? = null
    private lateinit var sourceTiles: Map<MonitorSource, View>
    private lateinit var elevatorTile: View
    private lateinit var preview: ImageView
    private lateinit var connectionState: ImageView
    private lateinit var sourcesState: ImageView
    private lateinit var elevatorState: TextView
    private lateinit var monitorState: ImageView
    private lateinit var monitorMessage: TextView
    private lateinit var monitorOverlay: View
    private lateinit var retry: View
    private lateinit var volumeControls: VolumeControls
    private lateinit var connectionSettings: ConnectionSettingsView

    private var options = ConnectionOptions()
    private var link: LinkStatus? = null
    private var wiredLink: WiredLink? = null
    private var state = TerminalState()
    private var selectedSource = MonitorSource.UNIT
    private var unregisterBack: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets()
        dashboard = findViewById(R.id.dashboard)
        monitorSources = findViewById(R.id.monitor_sources)
        monitorPage = findViewById(R.id.monitor_page)
        settingsPage = findViewById(R.id.settings_page)
        callPage = findViewById(R.id.call_page)
        callPreview = findViewById(R.id.call_preview)
        callAudio = CallAudio(this) { runOnUiThread {
            if (callStatus == CallStatus.ANSWERED) Toast.makeText(this, R.string.call_audio_unavailable, Toast.LENGTH_LONG).show()
        } }
        sourceTiles = MonitorSource.entries.associateWith { findViewById(it.tile) }
        selectedSource = MonitorSource.entries.firstOrNull {
            it.name == savedInstanceState?.getString("monitor_source")
        } ?: MonitorSource.UNIT
        elevatorTile = findViewById(R.id.elevator_tile)
        val mijiaIcon = findViewById<ImageView>(R.id.mijia_icon)
        try {
            mijiaIcon.setImageDrawable(packageManager.getApplicationIcon("com.xiaomi.smarthome"))
        } catch (_: PackageManager.NameNotFoundException) {
            // 未安装时保留通用智能家居图标，点击入口提供安装提示。
        }
        preview = findViewById(R.id.preview)
        MedioraUi.applySurface(findViewById(R.id.monitor_frame), SurfaceRole.OUTLINE)
        preview.clipToOutline = true
        connectionState = findViewById(R.id.connection_state)
        sourcesState = findViewById(R.id.sources_state)
        elevatorState = findViewById(R.id.elevator_state)
        monitorState = findViewById(R.id.monitor_state)
        MedioraUi.applySurface(findViewById(R.id.call_video_frame), SurfaceRole.OUTLINE)
        callPreview.clipToOutline = true
        monitorMessage = findViewById(R.id.monitor_message)
        monitorOverlay = findViewById(R.id.monitor_overlay)
        retry = findViewById(R.id.retry_monitor)
        volumeControls = VolumeControls(settingsPage)
        volumeControlStream = AudioManager.STREAM_MUSIC

        options = ConnectionOptions.load(getPreferences(MODE_PRIVATE), defaultWired = !BuildConfig.DEBUG)
        connectionSettings = ConnectionSettingsView(settingsPage, { options }, ::saveOptions, ::activateMode, onThemeSelected = { theme ->
            Appearance.save(this, theme)
            recreate()
        }) {
            showPage(dashboard)
            connection.simulateIncoming()
        }
        connectionSettings.renderOptions(options)
        connectionSettings.restoreSection(savedInstanceState?.getInt("settings_section") ?: 0)
        connection = AppConnection(this, onLink = { status, ethernet ->
            link = status
            wiredLink = ethernet
            renderState()
        }, onState = { updated ->
            state = updated
            if (state.monitor == MonitorStatus.ENDED && monitorPage.visibility == View.VISIBLE) {
                showPage(monitorSources)
            }
            renderState()
        }, onFrame = { bitmap ->
            if (monitorPage.visibility == View.VISIBLE &&
                (state.monitor == MonitorStatus.VIEWING || state.monitor == MonitorStatus.ENDED)
            ) preview.setImageBitmap(bitmap)
        }, onCall = ::renderCall, onCallFrame = { bitmap ->
            if (callPage.isShown && (callStatus == CallStatus.RINGING || callStatus == CallStatus.ANSWERED)) {
                callPreview.setImageBitmap(bitmap)
            }
        }, onCallAudio = { pcm -> if (callStatus == CallStatus.ANSWERED) callAudio.enqueue(pcm) })
        findViewById<View>(R.id.call_action).setOnClickListener {
            when (callStatus) {
                CallStatus.RINGING -> answerCall()
                CallStatus.ANSWERED -> connection.hangUpCall(displayedCallToken)
                else -> Unit
            }
        }
        findViewById<View>(R.id.call_back).setOnClickListener { connection.hangUpCall(displayedCallToken) }
        findViewById<View>(R.id.unlock_call).setOnClickListener { button ->
            val token = displayedCallToken
            button.isActivated = true
            button.isEnabled = false
            connection.unlockCall(token, options.autoHangUpAfterUnlock) { sent ->
                if (sent) Toast.makeText(this, if (options.wired) R.string.unlock_sent else R.string.sample_unlock_sent, Toast.LENGTH_LONG).show()
            }
            button.postDelayed({
                if (token == connection.callToken && callPage.isShown) { button.isActivated = false; button.isEnabled = true }
            }, 1500)
        }
        findViewById<View>(R.id.monitor_tile).setOnClickListener { showPage(monitorSources) }
        findViewById<View>(R.id.mijia_tile).setOnClickListener { openMijia() }
        sourceTiles.forEach { (source, tile) -> tile.setOnClickListener { openMonitor(source) } }
        elevatorTile.setOnClickListener { connection.callElevator() }
        findViewById<View>(R.id.sources_back).setOnClickListener { showPage(dashboard) }
        findViewById<View>(R.id.monitor_back).setOnClickListener { showPage(monitorSources) }
        retry.setOnClickListener {
            if (link == LinkStatus.WIRED_ERROR) activateMode() else openMonitor()
        }
        findViewById<View>(R.id.settings_button).setOnClickListener { showSettings() }
        findViewById<View>(R.id.settings_back).setOnClickListener { showPage(dashboard) }
        val restoredPage = savedInstanceState?.getInt("page", R.id.dashboard) ?: R.id.dashboard
        showPage(listOf(dashboard, monitorSources, monitorPage, settingsPage).firstOrNull { it.id == restoredPage } ?: dashboard)
        connectionSettings.restoreDialogState(savedInstanceState?.getBundle("settings_dialog"))
    }

    override fun onStart() {
        super.onStart()
        activateMode()
    }

    override fun onStop() {
        if (monitorPage.visibility == View.VISIBLE) showPage(monitorSources)
        callAudio.stop(); ringtone?.stop(); ringtone = null
        if (callPage.isShown) { connection.hangUpCall(displayedCallToken); renderCall(CallStatus.ENDED) }
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
        val page = listOf(dashboard, monitorSources, monitorPage, settingsPage, callPage).first { it.visibility == View.VISIBLE }
        outState.putInt("page", when (page) {
            monitorPage -> R.id.monitor_sources
            callPage -> callReturnPage?.id ?: R.id.dashboard
            else -> page.id
        })
        outState.putString("monitor_source", selectedSource.name)
        outState.putBundle("settings_dialog", connectionSettings.saveDialogState() ?: callSettingsDraft)
        outState.putInt("settings_section", connectionSettings.selectedSection())
        super.onSaveInstanceState(outState)
    }

    private fun navigateBack(): Boolean {
        when {
            callPage.isShown -> connection.hangUpCall(displayedCallToken)
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

    private fun openMonitor(source: MonitorSource = selectedSource) {
        selectedSource = source
        showPage(monitorPage)
        preview.setImageDrawable(null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (link == LinkStatus.SAMPLE || link == LinkStatus.WIRED_CONNECTED) {
            state = state.copy(monitor = MonitorStatus.DISCOVERING)
            connection.openMonitor(source)
        }
        renderState()
    }

    private fun showPage(page: View) {
        if (monitorPage.visibility == View.VISIBLE && page !== monitorPage) {
            connection.closeMonitor()
            preview.setImageDrawable(null)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        for (candidate in listOf(dashboard, monitorSources, monitorPage, settingsPage, callPage)) {
            candidate.visibility = if (candidate === page) View.VISIBLE else View.GONE
        }
        updateBackNavigation()
    }

    private fun renderCall(status: CallStatus, token: Int = displayedCallToken) {
        val previous = callStatus
        displayedCallToken = token
        callStatus = status
        when (status) {
            CallStatus.RINGING -> {
                ringtone?.stop(); ringtone = null
                callAudio.stop()
                if (!callPage.isShown) {
                    callReturnPage = when {
                        monitorPage.isShown -> monitorSources
                        settingsPage.isShown -> settingsPage
                        monitorSources.isShown -> monitorSources
                        else -> dashboard
                    }
                    callSettingsDraft = connectionSettings.saveDialogState()
                    connectionSettings.dismissDialog()
                    callPreview.setImageDrawable(null)
                    showPage(callPage)
                }
                findViewById<View>(R.id.unlock_call).apply { isActivated = false; isEnabled = true }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                volumeControlStream = AudioManager.STREAM_VOICE_CALL
                if (getSystemService(AudioManager::class.java).ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                    ringtone?.play()
                }
            }
            CallStatus.ANSWERED -> if (previous != status) {
                ringtone?.stop(); ringtone = null
                callAudio.start(options.wired && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    connection.sendCallAudio(it, token)
                }
            }
            else -> {
                ringtone?.stop(); ringtone = null; callAudio.stop()
                callPreview.setImageDrawable(null)
                volumeControlStream = AudioManager.STREAM_MUSIC
                if (callPage.isShown) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    showPage(callReturnPage ?: dashboard)
                    connectionSettings.restoreDialogState(callSettingsDraft)
                }
                callSettingsDraft = null
            }
        }
        findViewById<Button>(R.id.call_action).apply {
            val answered = status == CallStatus.ANSWERED
            setText(if (answered) R.string.hang_up_call else R.string.answer_call)
            val role = if (answered) SurfaceRole.DESTRUCTIVE else SurfaceRole.PRIMARY
            setTextColor(MedioraUi.buttonText(this@MainActivity, role))
            compoundDrawableTintList = MedioraUi.buttonText(this@MainActivity, role)
            setCompoundDrawablesWithIntrinsicBounds(0, if (answered) R.drawable.ic_end_call else R.drawable.ic_answer_call, 0, 0)
            MedioraUi.applySurface(this, role, addGutter = false)
            isActivated = status == CallStatus.RINGING && microphoneRequestCall != null
            isEnabled = answered || (status == CallStatus.RINGING && microphoneRequestCall == null)
        }
        val label = getString(if (status == CallStatus.ANSWERED) R.string.call_answered else R.string.call_ringing)
        findViewById<TextView>(R.id.call_state).text = if (options.wired) label else getString(R.string.call_sample, label)
    }

    @Suppress("DEPRECATION")
    private fun answerCall() {
        if (callStatus != CallStatus.RINGING || microphoneRequestCall != null) return
        if (options.wired && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphoneRequestCall = displayedCallToken
            findViewById<View>(R.id.call_action).apply { isActivated = true; isEnabled = false }
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 21)
        } else connection.answerCall(displayedCallToken)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 21) return
        val requestedCall = microphoneRequestCall
        microphoneRequestCall = null
        if (requestedCall != null && requestedCall == connection.callToken && requestedCall == displayedCallToken && callStatus == CallStatus.RINGING) {
            connection.answerCall(requestedCall)
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.call_microphone_denied, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<View>(R.id.call_action).apply {
            isActivated = false
            isEnabled = callStatus == CallStatus.RINGING || callStatus == CallStatus.ANSWERED
        }
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
            LinkStatus.SAMPLE, LinkStatus.SAMPLE_MISSING -> R.color.secondary_text
            LinkStatus.WIRED_CONNECTED -> R.color.status_ready
            LinkStatus.WIRED_ERROR, LinkStatus.WIRED_PROFILE_MISMATCH -> R.color.status_error
            else -> R.color.muted_text
        }
        setNetworkStatus(connectionState, status, statusColor)
        setNetworkStatus(sourcesState, status, statusColor)
        setNetworkStatus(monitorState, status, statusColor)
        connectionSettings.renderConnection(status, statusColor, wiredLink, link == LinkStatus.WIRED_ERROR)
        val ready = link == LinkStatus.SAMPLE || link == LinkStatus.SAMPLE_MISSING || link == LinkStatus.WIRED_CONNECTED
        sourceTiles.forEach { (source, tile) ->
            val configured = !options.wired || options.hasMonitorIds(source)
            tile.isEnabled = ready && configured
            tile.findViewById<TextView>(R.id.source_detail).setText(
                if (configured) R.string.view_camera else R.string.source_needs_configuration,
            )
        }
        findViewById<TextView>(R.id.monitor_title).setText(selectedSource.label)
        preview.contentDescription = getString(R.string.monitor_preview_description, getString(selectedSource.label))
        val waiting = state.elevator == ElevatorStatus.DISCOVERING || state.elevator == ElevatorStatus.WAITING
        elevatorTile.isActivated = waiting
        elevatorTile.isEnabled = ready && !waiting
        elevatorState.setText(when (state.elevator) {
            ElevatorStatus.IDLE -> R.string.call_home_floor
            ElevatorStatus.DISCOVERING, ElevatorStatus.WAITING -> R.string.waiting_for_reply
            ElevatorStatus.RESPONDED -> if (options.wired) R.string.device_responded else R.string.sample_responded
            ElevatorStatus.TIMED_OUT -> R.string.elevator_timeout
            ElevatorStatus.DISCONNECTED -> R.string.wired_disconnected
        })
        elevatorState.setTextColor(getColor(when (state.elevator) {
            ElevatorStatus.DISCOVERING, ElevatorStatus.WAITING -> R.color.status_running
            ElevatorStatus.RESPONDED -> if (options.wired) R.color.status_success else R.color.secondary_text
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
            state.monitor == MonitorStatus.BUSY -> if (options.wired) R.string.monitor_busy else R.string.sample_monitor_busy
            else -> R.string.monitor_ready_to_retry
        }
        val showImage = canPlayMonitor && (state.monitor == MonitorStatus.VIEWING || state.monitor == MonitorStatus.ENDED)
        monitorMessage.setText(message)
        monitorOverlay.visibility = if (showImage) View.GONE else View.VISIBLE
        val canRetry = link == LinkStatus.WIRED_ERROR ||
            (canPlayMonitor && (monitorInactive || state.monitor == MonitorStatus.TIMED_OUT || state.monitor == MonitorStatus.BUSY))
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        if (!showImage) preview.setImageDrawable(null)
    }

    private fun setNetworkStatus(view: ImageView, label: Int, color: Int) {
        view.background = null
        view.isClickable = false
        view.isFocusable = false
        view.setImageResource(when {
            link == LinkStatus.WIRED_CONNECTED -> R.drawable.ic_network_connected
            else -> R.drawable.ic_network_disconnected
        })
        view.imageTintList = ColorStateList.valueOf(getColor(color))
        view.contentDescription = getString(R.string.network_status_description, getString(label))
        view.tooltipText = null
    }

    private fun openMijia() {
        val launch = packageManager.getLaunchIntentForPackage("com.xiaomi.smarthome")
        if (launch == null) {
            Toast.makeText(this, R.string.mijia_not_installed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(launch)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.mijia_unavailable, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.mijia_unavailable, Toast.LENGTH_LONG).show()
        }
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
        window.insetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.statusBars())
        }
        val root = findViewById<View>(R.id.app_root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            // A revealed status bar overlays this fullscreen page; it must not shrink the workspace.
            val types = WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar() or WindowInsets.Type.displayCutout()
            val safe = insets.getInsetsIgnoringVisibility(types)
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(maxOf(safe.left, ime.left), maxOf(safe.top, ime.top),
                maxOf(safe.right, ime.right), maxOf(safe.bottom, ime.bottom))
            insets
        }
        root.requestApplyInsets()
    }
}
