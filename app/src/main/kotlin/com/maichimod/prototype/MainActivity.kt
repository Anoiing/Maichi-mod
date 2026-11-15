package com.maichimod.prototype

import android.annotation.SuppressLint
import android.app.Activity
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.maichimod.prototype.session.ElevatorStatus
import com.maichimod.prototype.session.MonitorStatus
import com.maichimod.prototype.session.TerminalState
import com.maichimod.prototype.transport.WiredLink

class MainActivity : Activity() {
    private var connection: AppConnection? = null
    private var subscription: com.maichimod.prototype.session.Cancellation? = null
    private var service: IntercomService? = null
    private var bound = false
    private var started = false
    private var intercom = IntercomState(enabled = false)
    private var resumed = false
    private var presentedCallKey: CallKey? = null
    private var notificationAsked = false
    private var cameraAsked = false
    private var standbyOptions = StandbyOptions()
    private val screenPolicy = ScreenPolicy(SystemClock.elapsedRealtime())
    private val screenHandler = Handler(Looper.getMainLooper())
    private val screenTick = Runnable { refreshScreenPolicy() }
    private var detector: PresenceDetector? = null
    private var detectorGeneration: Long? = null
    private var batteryPercent = 100
    private var externalPower = true
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryPercent = (level * 100 / scale).coerceIn(0, 100)
                externalPower = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
            refreshScreenPolicy()
        }
    }
    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!started) return
            val owner = (binder as IntercomService.LocalBinder).service
            owner.connection.checkDeadlines {
                if (!started || !bound) return@checkDeadlines
                service = owner
                connection = owner.connection
                subscription?.cancel()
                subscription = owner.connection.observe(observer)
                owner.refreshAvailability()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            subscription?.cancel(); subscription = null
            connection = null; service = null
            link = null
            renderState()
        }
    }
    private lateinit var dashboard: View
    private lateinit var monitorSources: View
    private lateinit var monitorPage: View
    private lateinit var settingsPage: View
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
    private var idleOptions = IdleReturnOptions()
    private var link: LinkStatus? = null
    private var wiredLink: WiredLink? = null
    private var state = TerminalState()
    private var selectedSource = MonitorSource.UNIT
    private var unregisterBack: (() -> Unit)? = null

    private val observer = object : IntercomObserver {
        override fun onState(updated: IntercomState) {
            intercom = updated
            if (updated.configurationLoaded) options = updated.options
            link = updated.link
            wiredLink = updated.ethernet
            state = updated.terminal
            connectionSettings.renderOptions(options)
            if (state.monitor == MonitorStatus.ENDED && monitorPage.visibility == View.VISIBLE) showPage(monitorSources)
            showCurrentCall()
            renderState()
        }
        override fun onMonitorFrame(frame: android.graphics.Bitmap) {
            if (monitorPage.isShown && state.monitor == MonitorStatus.VIEWING) preview.setImageBitmap(frame)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        MedioraUi.applyWindowInsets(this, findViewById(R.id.app_root))
        notificationAsked = savedInstanceState?.getBoolean("notificationAsked") ?: false
        cameraAsked = savedInstanceState?.getBoolean("cameraAsked") ?: false
        dashboard = findViewById(R.id.dashboard)
        monitorSources = findViewById(R.id.monitor_sources)
        monitorPage = findViewById(R.id.monitor_page)
        settingsPage = findViewById(R.id.settings_page)
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
        monitorMessage = findViewById(R.id.monitor_message)
        monitorOverlay = findViewById(R.id.monitor_overlay)
        retry = findViewById(R.id.retry_monitor)
        volumeControls = VolumeControls(settingsPage)
        volumeControlStream = AudioManager.STREAM_MUSIC

        idleOptions = AppSettings(this).idleReturnOptions()
        connectionSettings = ConnectionSettingsView(settingsPage, { options }, ::saveOptions, { service?.retryReception() }, onThemeSelected = { theme ->
            Appearance.save(this, theme)
            recreate()
        }, onWindowFocusGained = { showCurrentCall(); refreshIdleSettings(); refreshStandbySettings() }, currentIdleOptions = { idleOptions },
            onSaveIdleReturn = { updated, done ->
                val saved = AppSettings(this).saveIdleReturn(updated.enabled, updated.minutes)
                if (saved) UserInteractionService.refreshSettings()
                refreshIdleSettings()
                done(saved)
            }, currentStandbyOptions = { standbyOptions }, onSaveStandby = ::saveStandby,
            onRetryPresence = {
                if (cameraAllowed()) { detector?.stop(); refreshScreenPolicy() }
                else requestPresenceCamera()
            }, onUserInteraction = { onUserInteraction() }) { delayMs ->
            showPage(dashboard)
            connection?.simulateIncoming(delayMs)
        }
        connectionSettings.renderOptions(options)
        findViewById<View>(R.id.idle_detection_settings).setOnClickListener {
            openSystemSettings(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        connectionSettings.restoreSection(savedInstanceState?.getInt("settings_section") ?: 0)
        findViewById<android.widget.CompoundButton>(R.id.reception_enabled).setOnCheckedChangeListener { _, checked ->
            if (checked != intercom.enabled) {
                val owner = service
                if (owner == null) { connectionSettings.renderReception(intercom, false); return@setOnCheckedChangeListener }
                connectionSettings.setReceptionSaving(true)
                owner.setReceptionEnabled(checked) { saved ->
                    if (!isDestroyed) {
                        connectionSettings.setReceptionSaving(false)
                        connectionSettings.renderReception(intercom, service != null)
                        if (!saved) Toast.makeText(this, R.string.reception_save_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        findViewById<View>(R.id.presentation_settings).setOnClickListener { checkPresentationPermissions() }
        findViewById<View>(R.id.background_settings).setOnClickListener {
            openSystemSettings(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<View>(R.id.reset_configuration).setOnClickListener {
            connectionSettings.confirmReset { complete ->
                val owner = connection
                if (owner == null) complete(false)
                else owner.resetConnectionOptions { saved -> if (!isDestroyed) complete(saved) }
            }
        }
        findViewById<View>(R.id.monitor_tile).setOnClickListener { showPage(monitorSources) }
        findViewById<View>(R.id.mijia_tile).setOnClickListener { openMijia() }
        sourceTiles.forEach { (source, tile) -> tile.setOnClickListener { openMonitor(source) } }
        elevatorTile.setOnClickListener { connection?.callElevator() }
        findViewById<View>(R.id.sources_back).setOnClickListener { showPage(dashboard) }
        findViewById<View>(R.id.monitor_back).setOnClickListener { showPage(monitorSources) }
        retry.setOnClickListener {
            if (link == LinkStatus.WIRED_ERROR || link == LinkStatus.RECEPTION_BLOCKED) service?.retryReception() else openMonitor()
        }
        findViewById<View>(R.id.settings_button).setOnClickListener { showSettings() }
        findViewById<View>(R.id.settings_back).setOnClickListener { showPage(dashboard) }
        val restoredPage = savedInstanceState?.getInt("page", R.id.dashboard) ?: R.id.dashboard
        showPage(listOf(dashboard, monitorSources, monitorPage, settingsPage).firstOrNull { it.id == restoredPage } ?: dashboard)
        connectionSettings.restoreDialogState(savedInstanceState?.getBundle("settings_dialog"))
    }

    override fun onStart() {
        super.onStart()
        started = true
        val events = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenEvents, events, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenEvents, events)
        UserInteractionService.activityVisible(this, true)
        renderState()
        if (!IntercomService.ensureStarted(this)) Toast.makeText(this, R.string.reception_start_failed, Toast.LENGTH_LONG).show()
        bound = bindService(Intent(this, IntercomService::class.java), binding, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshIdleSettings()
        refreshStandbySettings()
        service?.refreshAvailability()
        showCurrentCall()
    }

    override fun onPause() {
        resumed = false
        refreshScreenPolicy()
        presentedCallKey = null
        super.onPause()
    }

    override fun onStop() {
        if (monitorPage.isShown) showPage(monitorSources)
        started = false
        unregisterReceiver(screenEvents)
        UserInteractionService.activityVisible(this, false)
        subscription?.cancel(); subscription = null
        if (bound) unbindService(binding)
        bound = false
        connection = null; service = null
        super.onStop()
    }

    override fun onDestroy() {
        screenHandler.removeCallbacks(screenTick)
        detector?.close()
        unregisterBack?.invoke()
        connectionSettings.dismissDialog()
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
        outState.putInt("page", when (page) {
            monitorPage -> R.id.monitor_sources
            else -> page.id
        })
        outState.putString("monitor_source", selectedSource.name)
        outState.putBundle("settings_dialog", connectionSettings.saveDialogState())
        outState.putInt("settings_section", connectionSettings.selectedSection())
        outState.putBoolean("notificationAsked", notificationAsked)
        outState.putBoolean("cameraAsked", cameraAsked)
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
        if (::connectionSettings.isInitialized) refreshStandbySettings()
        if (hasFocus && ::volumeControls.isInitialized) {
            refreshIdleSettings()
            if (settingsPage.isShown) volumeControls.refresh()
            service?.refreshAvailability()
            showCurrentCall()
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val handled = super.onKeyUp(keyCode, event)
        if (settingsPage.isShown &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)
        ) {
            settingsPage.postDelayed({ if (settingsPage.isShown) volumeControls.refresh(); service?.refreshAvailability() }, 150L)
        }
        return handled
    }

    private fun openMonitor(source: MonitorSource = selectedSource) {
        selectedSource = source
        showPage(monitorPage)
        preview.setImageDrawable(null)
        if (link == LinkStatus.SAMPLE || link == LinkStatus.WIRED_CONNECTED) {
            state = state.copy(monitor = MonitorStatus.DISCOVERING)
            connection?.openMonitor(source)
        }
        renderState()
    }

    private fun showPage(page: View) {
        if (monitorPage.visibility == View.VISIBLE && page !== monitorPage) {
            connection?.closeMonitor()
            preview.setImageDrawable(null)
        }
        for (candidate in listOf(dashboard, monitorSources, monitorPage, settingsPage)) {
            candidate.visibility = if (candidate === page) View.VISIBLE else View.GONE
        }
        updateBackNavigation()
        refreshScreenPolicy()
    }

    private fun showCurrentCall() {
        val current = intercom.callKey
        if (!resumed || (!hasWindowFocus() && !connectionSettings.hasWindowFocus()) || connection == null || service == null || !intercom.inCall || intercom.callEnding || current == null || current == presentedCallKey) return
        presentedCallKey = current
        try { startActivity(IncomingCallActivity.createIntent(this, current)) }
        catch (_: RuntimeException) { connection?.reportIssue(getString(R.string.call_screen_unavailable)) }
    }

    @Suppress("DEPRECATION")
    private fun checkPresentationPermissions() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!notificationAsked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                notificationAsked = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
                return
            }
        }
        val intent = when {
            !manager.areNotificationsEnabled() -> Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent() -> Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                android.net.Uri.parse("package:$packageName"))
            else -> Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, IntercomService.CALL_CHANNEL)
        }
        openSystemSettings(intent)
    }

    private fun openSystemSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: RuntimeException) { Toast.makeText(this, R.string.permission_settings_unavailable, Toast.LENGTH_LONG).show() }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 31) service?.refreshAvailability()
        if (requestCode == 41) refreshStandbySettings()
    }

    private fun renderState() {
        val status = if (intercom.configurationLoaded && !intercom.enabled) {
            if (intercom.configurationError) R.string.settings_read_failed else R.string.reception_stopped
        } else when (link) {
            LinkStatus.SAMPLE -> R.string.sample_mode
            LinkStatus.SAMPLE_MISSING -> R.string.no_offline_samples
            LinkStatus.WIRED_CONNECTED -> R.string.wired_connected
            LinkStatus.WIRED_WAITING -> R.string.wired_disconnected
            LinkStatus.WIRED_ADDRESS_PENDING -> R.string.wired_address_pending
            LinkStatus.WIRED_RECONNECTING -> R.string.wired_reconnecting
            LinkStatus.WIRED_NEEDS_IDS -> R.string.wired_needs_ids
            LinkStatus.WIRED_PROFILE_MISMATCH -> R.string.wired_profile_mismatch
            LinkStatus.WIRED_ERROR -> R.string.wired_open_failed
            LinkStatus.RECEPTION_BLOCKED -> R.string.reception_start_failed
            null -> when {
                !options.wired -> R.string.loading_samples
                !options.hasTerminalIds -> R.string.wired_needs_ids
                else -> R.string.wired_disconnected
            }
        }
        val statusColor = when (link) {
            LinkStatus.SAMPLE, LinkStatus.SAMPLE_MISSING -> R.color.secondary_text
            LinkStatus.WIRED_CONNECTED -> R.color.status_ready
            LinkStatus.WIRED_ERROR, LinkStatus.WIRED_PROFILE_MISMATCH, LinkStatus.RECEPTION_BLOCKED -> R.color.status_error
            else -> R.color.muted_text
        }
        setNetworkStatus(connectionState, status, statusColor)
        setNetworkStatus(sourcesState, status, statusColor)
        setNetworkStatus(monitorState, status, statusColor)
        connectionSettings.renderConnection(status, statusColor, wiredLink, link == LinkStatus.WIRED_ERROR || link == LinkStatus.RECEPTION_BLOCKED)
        connectionSettings.renderReception(intercom, service != null)
        val ready = intercom.canMonitor && connection != null
        sourceTiles.forEach { (source, tile) ->
            val configured = !options.wired || options.hasMonitorIds(source)
            tile.isEnabled = ready && configured
            tile.findViewById<TextView>(R.id.source_detail).setText(
                when {
                    !configured -> R.string.source_needs_configuration
                    intercom.inCall -> R.string.monitor_unavailable_during_call
                    !ready -> R.string.monitor_unavailable_until_connected
                    else -> R.string.view_camera
                },
            )
        }
        findViewById<TextView>(R.id.monitor_title).setText(selectedSource.label)
        preview.contentDescription = getString(R.string.monitor_preview_description, getString(selectedSource.label))
        val waiting = state.elevator == ElevatorStatus.DISCOVERING || state.elevator == ElevatorStatus.WAITING
        elevatorTile.isActivated = waiting
        elevatorTile.isEnabled = intercom.canCallElevator && connection != null && !waiting
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
        val canRetry = link == LinkStatus.WIRED_ERROR || link == LinkStatus.RECEPTION_BLOCKED ||
            (canPlayMonitor && (monitorInactive || state.monitor == MonitorStatus.TIMED_OUT || state.monitor == MonitorStatus.BUSY))
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        if (!showImage) preview.setImageDrawable(null)
        refreshScreenPolicy()
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

    private fun saveOptions(updated: ConnectionOptions, onSaved: (Boolean) -> Unit) {
        val owner = connection
        if (owner == null) { onSaved(false); return }
        owner.updateOptions(updated, options) { saved ->
            if (isDestroyed) return@updateOptions
            if (saved) {
                options = updated
                connectionSettings.renderOptions(updated)
                IntercomService.ensureStarted(this)
            } else Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
            onSaved(saved)
        }
    }

    internal fun refreshIdleSettings() {
        if (!::connectionSettings.isInitialized) return
        idleOptions = AppSettings(this).idleReturnOptions()
        connectionSettings.renderIdleReturn(idleOptions, UserInteractionService.isAvailable(), UserInteractionService.returnFailed())
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        screenPolicy.interaction(SystemClock.elapsedRealtime())
        refreshScreenPolicy()
    }

    private fun cameraAllowed() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun refreshStandbySettings() {
        standbyOptions = AppSettings(this).standbyOptions()
        refreshScreenPolicy()
    }

    private fun saveStandby(updated: StandbyOptions) {
        val previouslyActive = standbyOptions.enabled && standbyOptions.presenceEnabled
        if (!AppSettings(this).saveStandby(updated)) {
            Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_LONG).show()
            refreshStandbySettings()
            return
        }
        detector?.stop()
        refreshStandbySettings()
        if (updated.enabled && updated.presenceEnabled && !previouslyActive && !cameraAllowed()) requestPresenceCamera()
    }

    @Suppress("DEPRECATION")
    private fun requestPresenceCamera() {
        if (!resumed) return
        if (!cameraAsked || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            cameraAsked = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
        } else openSystemSettings(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$packageName")))
    }

    /** One adapter applies the policy to this activity and its own settings dialog only. */
    internal fun refreshScreenPolicy() {
        if (!::connectionSettings.isInitialized || !::dashboard.isInitialized) return
        screenHandler.removeCallbacks(screenTick)
        val now = SystemClock.elapsedRealtime()
        val environment = ScreenEnvironment(
            visible = resumed && (hasWindowFocus() || connectionSettings.hasWindowFocus()),
            interactive = getSystemService(PowerManager::class.java).isInteractive,
            unlocked = !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked,
            engaged = intercom.inCall || monitorPage.isShown,
            cameraAllowed = cameraAllowed(), externalPower = externalPower, batteryPercent = batteryPercent,
            hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
        )
        val demand = screenPolicy.update(now, standbyOptions, environment)
        window.applyScreenDemand(demand)
        connectionSettings.applyScreenDemand(demand)
        (dashboard as HomeDashboard).standbyDimmed = demand.dimmed
        val interval = demand.detectionIntervalMs
        if (interval == null) {
            if (detector?.status != null && detector?.status != PresenceDetector.Status.STOPPED) detector?.stop()
            detectorGeneration = null
        } else {
            val sensor = detector ?: PresenceDetector(this).also { detector = it }
            val generation = screenPolicy.sensingGeneration
            if (detectorGeneration != generation) { sensor.stop(); detectorGeneration = generation }
            sensor.intervalMs = interval
            sensor.start { presence, capturedAtMs ->
                screenPolicy.onPresence(presence, capturedAtMs, generation)
                refreshScreenPolicy()
            }
        }
        connectionSettings.renderStandby(standbyOptions, detector?.status ?: PresenceDetector.Status.STOPPED,
            cameraAllowed(), batteryPercent, externalPower)
        if (environment.visible && (standbyOptions.enabled || environment.engaged)) {
            val delay = demand.nextCheckAtMs?.let { (it - now).coerceIn(1, 1000) } ?: 1000
            screenHandler.postDelayed(screenTick, delay)
        }
    }
}

internal fun android.view.Window.applyScreenDemand(demand: ScreenDemand) {
    if (attributes.screenBrightness != demand.brightness) {
        attributes = attributes.apply { screenBrightness = demand.brightness }
    }
    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    val keepingOn = attributes.flags and flag != 0
    if (demand.keepScreenOn && !keepingOn) addFlags(flag)
    else if (!demand.keepScreenOn && keepingOn) clearFlags(flag)
}
