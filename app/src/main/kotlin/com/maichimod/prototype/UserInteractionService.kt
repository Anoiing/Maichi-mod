package com.maichimod.prototype

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.maichimod.prototype.session.Cancellation
import com.maichimod.prototype.session.SessionScheduler
import java.io.Closeable

/** Optional interaction metadata. Reception never depends on this system authorization. */
class UserInteractionService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val idle = IdleReturn(object : SessionScheduler {
        override val nowMs: Long get() = SystemClock.elapsedRealtime()
        override fun schedule(delayMs: Long, action: () -> Unit): Cancellation {
            val callback = Runnable { updateEnvironment(); action() }
            handler.postDelayed(callback, delayMs)
            return Cancellation { handler.removeCallbacks(callback) }
        }
    }, ::returnToApp)
    @Volatile private var active = false
    private var bound = false
    @Volatile private var owner: AppConnection? = null
    private var subscription: Cancellation? = null
    private var foregroundPackage: String? = null
    private var options = IdleReturnOptions(false, valid = false)
    private var lastAvailability: Pair<Boolean, Boolean>? = null
    private var keyboardPackages = emptySet<String>()
    private val returnCheck = Runnable {
        if (visibleActivities.isEmpty()) idle.returnNotObserved()
        publishAvailability()
    }
    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connection = (binder as IntercomService.LocalBinder).service.connection
            connection.checkDeadlines {
                if (!bound) return@checkDeadlines
                owner = connection
                subscription?.cancel()
                subscription = connection.observe(object : IntercomObserver {
                    override fun onState(updated: IntercomState) { updateEnvironment() }
                })
                updateEnvironment()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            subscription?.cancel(); subscription = null; owner = null
            updateEnvironment()
        }
    }
    private val deviceEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_UNLOCKED || intent.action == Intent.ACTION_INPUT_METHOD_CHANGED) reloadSettings()
            else updateEnvironment()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_INPUT_METHOD_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(deviceEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(deviceEvents, filter)
    }

    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS }
        instance = this; active = true
        reloadSettings()
        // Read-only subscription; this optional service never starts/stops reception or sends commands.
        if (!bound) bound = bindService(Intent(this, IntercomService::class.java), binding, Context.BIND_AUTO_CREATE)
        updateEnvironment()
        if (BuildConfig.DEBUG) Log.i(TAG, "connected elapsed=${SystemClock.elapsedRealtime()}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Do not obtain source nodes, text, window content, screenshots, or typed key values.
        resumeDetection()
        val sourcePackage = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !sourcePackage.isNullOrBlank() &&
            sourcePackage !in keyboardPackages && sourcePackage != "com.android.systemui") {
            // Non-focusable IME/volume overlays may disappear without another event from the app below.
            foregroundPackage = sourcePackage.takeUnless { it == packageName }
        }
        val interaction = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> true
            else -> false
        }
        updateEnvironment(userInteraction = interaction)
        if (BuildConfig.DEBUG) Log.i(TAG, "event=${event.eventType} package=${event.packageName} elapsed=${SystemClock.elapsedRealtime()}")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val interaction = event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode !in listOf(KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SLEEP)
        if (interaction) resumeDetection()
        updateEnvironment(userInteraction = interaction)
        if (BuildConfig.DEBUG) Log.i(TAG, "keyAction=${event.action} elapsed=${SystemClock.elapsedRealtime()}")
        return false
    }

    override fun onInterrupt() {
        active = false
        updateEnvironment()
        if (BuildConfig.DEBUG) Log.i(TAG, "interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        active = false; updateEnvironment()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        active = false
        if (instance === this) instance = null
        idle.close(); handler.removeCallbacksAndMessages(null)
        subscription?.cancel(); subscription = null; owner = null
        publishAvailability()
        if (bound) unbindService(binding)
        bound = false
        unregisterReceiver(deviceEvents)
        super.onDestroy()
    }

    private fun reloadSettings() {
        options = AppSettings(this).idleReturnOptions()
        keyboardPackages = getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        updateEnvironment()
    }

    private fun resumeDetection() {
        if (!active) { active = true; reloadSettings() }
    }

    private fun updateEnvironment(userInteraction: Boolean = false) {
        idle.configure(options.enabled && options.valid, options.minutes, active && owner != null)
        idle.update(visibleActivities.isNotEmpty(), getSystemService(PowerManager::class.java).isInteractive,
            getSystemService(UserManager::class.java).isUserUnlocked && !getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            owner?.currentState?.inCall == true, foregroundKnown = foregroundPackage != null || visibleActivities.isNotEmpty(), userInteraction = userInteraction)
        publishAvailability()
    }

    private fun publishAvailability() {
        val next = (active && owner != null) to idle.returnFailed
        if (next == lastAvailability) return
        lastAvailability = next
        visibleActivities.filterIsInstance<MainActivity>().forEach { it.refreshIdleSettings() }
    }

    private fun returnToApp(): Boolean {
        if (!active || visibleActivities.isNotEmpty() || owner == null || owner?.currentState?.inCall == true ||
            !getSystemService(PowerManager::class.java).isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(UserManager::class.java).isUserUnlocked) return false
        return try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            handler.removeCallbacks(returnCheck)
            handler.postDelayed(returnCheck, 1500)
            true
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to return to intercom", failure)
            false
        }
    }

    companion object {
        private const val TAG = "Maichi-Interaction"
        @Volatile private var instance: UserInteractionService? = null
        private val visibleActivities = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
        internal fun activityVisible(activity: Activity, visible: Boolean) {
            if (visible) visibleActivities.add(activity) else visibleActivities.remove(activity)
            instance?.apply {
                if (visible) foregroundPackage = null
                updateEnvironment()
            }
        }
        internal fun refreshSettings() { instance?.reloadSettings() }
        internal fun isAvailable(): Boolean = instance?.let { it.active && it.owner != null } == true
        internal fun returnFailed(): Boolean = instance?.idle?.returnFailed == true
    }
}

/** One inactivity deadline; window refreshes only update constraints and never extend it. */
internal class IdleReturn(
    private val scheduler: SessionScheduler,
    private val returnToApp: () -> Boolean,
) : Closeable {
    private var enabled = false
    private var authorized = false
    private var timeoutMs = 180_000L
    private var ownAppVisible = true
    private var interactive = true
    private var unlocked = true
    private var inCall = false
    private var foregroundKnown = true
    private var deadline: Long? = null
    private var task: Cancellation? = null
    private var attempted = false
    private var closed = false
    var returnFailed = false
        private set

    fun configure(enabled: Boolean, minutes: Int, authorized: Boolean) {
        if (closed) return
        val validEnabled = enabled && minutes in 1..30
        val timeout = minutes.coerceIn(1, 30) * 60_000L
        if (this.enabled == validEnabled && this.authorized == authorized && timeoutMs == timeout) return
        this.enabled = validEnabled; this.authorized = authorized; timeoutMs = timeout
        deadline = null; attempted = false; returnFailed = false
        evaluate()
    }

    fun update(ownAppVisible: Boolean, interactive: Boolean, unlocked: Boolean, inCall: Boolean, foregroundKnown: Boolean = true,
        userInteraction: Boolean = false) {
        if (closed) return
        if (this.ownAppVisible != ownAppVisible || (this.inCall && !inCall)) { deadline = null; attempted = false }
        this.ownAppVisible = ownAppVisible; this.interactive = interactive
        this.unlocked = unlocked; this.inCall = inCall
        this.foregroundKnown = foregroundKnown
        if (userInteraction && enabled && authorized && !ownAppVisible && !inCall && interactive && unlocked) {
            deadline = scheduler.nowMs + timeoutMs
            attempted = false; returnFailed = false
        }
        evaluate()
    }

    fun returnNotObserved() {
        if (!closed && attempted && !ownAppVisible && !inCall) returnFailed = true
    }

    fun interaction() {
        update(ownAppVisible, interactive, unlocked, inCall, foregroundKnown, userInteraction = true)
    }

    private fun evaluate() {
        task?.cancel(); task = null
        if (closed || !enabled || !authorized || ownAppVisible || inCall) {
            deadline = null; attempted = false
            return
        }
        if (attempted) return
        val due = deadline ?: (scheduler.nowMs + timeoutMs).also { deadline = it }
        if (!interactive || !unlocked || !foregroundKnown) return
        val remaining = due - scheduler.nowMs
        if (remaining <= 0) {
            attempted = true
            returnFailed = !returnToApp()
        } else task = scheduler.schedule(remaining) { evaluate() }
    }

    override fun close() {
        closed = true
        task?.cancel(); task = null; deadline = null
    }
}
